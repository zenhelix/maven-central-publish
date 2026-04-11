package io.github.zenhelix.gradle.plugin.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.utils.RetryHandler
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpRequest.BodyPublishers.noBody
import java.net.http.HttpRequest.newBuilder
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

public class MavenCentralApiClientImpl(
    private val baseUrl: String,
    httpClient: HttpClient? = null,
    private val requestTimeout: Duration = Duration.ofMinutes(5),
    private val connectTimeout: Duration = Duration.ofSeconds(30),
    maxRetries: Int = 3,
    retryDelay: Duration = Duration.ofSeconds(2)
) : MavenCentralApiClient {

    private val logger: Logger = Logging.getLogger(MavenCentralApiClientImpl::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val retryHandler: RetryHandler = RetryHandler(maxRetries, retryDelay, logger)

    private val httpClient: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build()

    /**
     * [Uploading a Deployment Bundle](https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle)
     */
    override fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<UUID, String> {
        require(Files.exists(bundle)) { "Bundle file does not exist: $bundle" }
        require(Files.isRegularFile(bundle)) { "Bundle path is not a file: $bundle" }
        require(Files.size(bundle) > 0) { "Bundle file is empty: $bundle" }

        val query = buildQueryString(
            "name" to deploymentName,
            "publishingType" to publishingType?.id
        )

        val uri = URI("$baseUrl/api/v1/publisher/upload$query")
        val boundary = UUID.randomUUID().toString().replace("-", "")

        val request = newBuilder(uri)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(filePart(BUNDLE_FILE_PART_NAME, boundary, bundle))
            .build()

        logger.debug("Sending upload request to: {}", uri)

        return executeRequestWithRetry(request, "uploadDeploymentBundle") { response, body ->
            if (response.statusCode() == HTTP_CREATED) {
                val deploymentId = UUID.fromString(body)
                logger.debug("Bundle uploaded successfully. DeploymentId: {}", deploymentId)
                HttpResponseResult.Success(
                    data = deploymentId,
                    httpStatus = response.statusCode(),
                    httpHeaders = response.headers().map()
                )
            } else {
                logger.warn("Failed to upload bundle. Status: {}, Response: {}", response.statusCode(), body)
                HttpResponseResult.Error(
                    data = body,
                    httpStatus = response.statusCode(),
                    httpHeaders = response.headers().map()
                )
            }
        }
    }

    override fun deploymentStatus(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<DeploymentStatus, String> {
        val uri = URI("$baseUrl/api/v1/publisher/status?id=${urlEncode(deploymentId.toString())}")
        val request = newBuilder(uri)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .POST(noBody())
            .build()

        logger.debug("Sending status request to: {}", uri)

        return executeRequestWithRetry(request, "deploymentStatus") { response, body ->
            if (response.statusCode() == HTTP_OK) {
                val status = parseDeploymentStatus(body)
                if (status != null) {
                    logger.debug(
                        "Deployment status retrieved: deploymentId={}, state={}",
                        status.deploymentId, status.deploymentState
                    )
                    HttpResponseResult.Success(
                        data = status,
                        httpStatus = response.statusCode(),
                        httpHeaders = response.headers().map()
                    )
                } else {
                    logger.warn("Failed to parse deployment status. Response: {}", body)
                    HttpResponseResult.Error(
                        data = body,
                        httpStatus = response.statusCode(),
                        httpHeaders = response.headers().map()
                    )
                }
            } else {
                logger.warn("Failed to fetch deployment status. Status: {}, Response: {}", response.statusCode(), body)
                HttpResponseResult.Error(
                    data = body,
                    httpStatus = response.statusCode(),
                    httpHeaders = response.headers().map()
                )
            }
        }
    }

    /**
     * [Publish the Deployment](https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment)
     */
    override fun publishDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String> {
        val uri = URI("$baseUrl/api/v1/publisher/deployment/${urlEncode(deploymentId.toString())}")
        val request = newBuilder(uri)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .POST(noBody())
            .build()

        logger.debug("Sending publish request to: {}", uri)

        return executeRequestWithRetry(request, "publishDeployment") { response, body ->
            if (response.statusCode() == HTTP_NO_CONTENT) {
                logger.debug("Deployment published successfully: {}", deploymentId)
                HttpResponseResult.Success(
                    data = Unit,
                    httpStatus = response.statusCode(),
                    httpHeaders = response.headers().map()
                )
            } else {
                logger.warn("Failed to publish deployment. Status: {}, Response: {}", response.statusCode(), body)
                HttpResponseResult.Error(
                    data = body,
                    httpStatus = response.statusCode(),
                    httpHeaders = response.headers().map()
                )
            }
        }
    }

    /**
     * [Drop the Deployment](https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment)
     */
    override fun dropDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String> {
        val uri = URI("$baseUrl/api/v1/publisher/deployment/${urlEncode(deploymentId.toString())}")
        val request = newBuilder(uri)
            .timeout(requestTimeout)
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .DELETE()
            .build()

        logger.debug("Sending drop request to: {}", uri)

        return executeRequestWithRetry(request, "dropDeployment") { response, body ->
            if (response.statusCode() == HTTP_NO_CONTENT) {
                logger.debug("Deployment dropped successfully: {}", deploymentId)
                HttpResponseResult.Success(
                    data = Unit,
                    httpStatus = response.statusCode(),
                    httpHeaders = response.headers().map()
                )
            } else {
                logger.warn("Failed to drop deployment. Status: {}, Response: {}", response.statusCode(), body)
                HttpResponseResult.Error(
                    data = body,
                    httpStatus = response.statusCode(),
                    httpHeaders = response.headers().map()
                )
            }
        }
    }

    override fun close() {
        // HttpClient implements AutoCloseable starting from Java 21
        // For Java 17 and earlier, close() method doesn't exist
        @Suppress("USELESS_IS_CHECK")
        if (httpClient is AutoCloseable) {
            try {
                httpClient.close()
                logger.debug("HttpClient closed successfully")
            } catch (e: Exception) {
                logger.warn("Failed to close HttpClient", e)
            }
        }
    }

    private fun <T : Any> executeRequestWithRetry(
        request: HttpRequest,
        operationName: String,
        responseHandler: (HttpResponse<String>, String) -> HttpResponseResult<T, String>
    ): HttpResponseResult<T, String> = try {
        retryHandler.executeWithRetry(
            operation = { attempt ->
                val startTime = System.currentTimeMillis()
                val response = httpClient.send(request, BodyHandlers.ofString(UTF_8))
                val duration = System.currentTimeMillis() - startTime

                logger.debug(
                    "HTTP request completed: operation={}, status={}, duration={}ms, attempt={}",
                    operationName, response.statusCode(), duration, attempt
                )

                val result = responseHandler(response, response.body())

                if (result is HttpResponseResult.Error && response.statusCode() >= 500) {
                    throw RetriableHttpException(response.statusCode(), "Server error")
                }

                result
            },
            shouldRetry = { exception -> isRetriableException(exception) },
            onRetry = { attempt, exception ->
                logger.warn(
                    "HTTP request failed: operation={}, attempt={}, error={}",
                    operationName, attempt, exception.message
                )
            }
        )
    } catch (e: Exception) {
        logger.error("HTTP request failed: operation={}", operationName, e)
        HttpResponseResult.UnexpectedError(cause = e)
    }

    private fun isRetriableException(e: Exception): Boolean = when (e) {
        is HttpTimeoutException -> true
        is java.net.ConnectException -> true
        is java.net.SocketTimeoutException -> true
        is java.io.IOException -> true
        is RetriableHttpException -> true
        else -> false
    }

    private class RetriableHttpException(statusCode: Int, message: String) : Exception("HTTP $statusCode: $message")

    private fun parseDeploymentStatus(json: String): DeploymentStatus? = try {
        objectMapper.readValue<DeploymentStatusDto>(json).toModel()
    } catch (e: Exception) {
        logger.error("Failed to parse deployment status: {}", json, e)
        null
    }

    private fun buildQueryString(vararg params: Pair<String, String?>): String {
        val query = params
            .filter { it.second != null }
            .joinToString("&") { "${it.first}=${urlEncode(it.second!!)}" }

        return if (query.isNotEmpty()) {
            "?$query"
        } else {
            ""
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, UTF_8)

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DeploymentStatusDto(
        @param:JsonProperty("deploymentId")
        val deploymentId: String,
        @param:JsonProperty("deploymentName")
        val deploymentName: String,
        @param:JsonProperty("deploymentState")
        val deploymentState: String,
        @param:JsonProperty("purls")
        val purls: List<String>?,
        @param:JsonProperty("errors")
        val errors: Map<String, Any?>?
    ) {
        fun toModel() = DeploymentStatus(
            deploymentId = UUID.fromString(deploymentId),
            deploymentName = deploymentName,
            deploymentState = DeploymentStateType.of(deploymentState),
            purls = purls,
            errors = errors
        )
    }

    private companion object {
        private const val CRLF = "\r\n"
        private const val BUNDLE_FILE_PART_NAME = "bundle"

        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val HTTP_NO_CONTENT = 204

        private fun filePart(
            partName: String, boundary: String, file: Path
        ): HttpRequest.BodyPublisher = BodyPublishers.concat(
            BodyPublishers.ofString(
                buildString {
                    append(CRLF).append("--$boundary").append(CRLF)
                    append("Content-Disposition: form-data; name=\"$partName\"; filename=\"")
                    append(file.fileName.toString()).append("\"").append(CRLF)
                    append("Content-Type: application/octet-stream").append(CRLF)
                    append(CRLF)
                }
            ),
            BodyPublishers.ofFile(file),
            BodyPublishers.ofString("$CRLF--$boundary--")
        )
    }
}