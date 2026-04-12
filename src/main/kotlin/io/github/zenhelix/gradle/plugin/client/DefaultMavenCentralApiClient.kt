package io.github.zenhelix.gradle.plugin.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.utils.RetryHandler
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpRequest.BodyPublishers.noBody
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.api.logging.Logger
import org.gradle.api.logging.Logging

public class DefaultMavenCentralApiClient(
    private val baseUrl: String,
    httpClient: HttpClient? = null,
    private val requestTimeout: Duration = Duration.ofMinutes(5),
    private val connectTimeout: Duration = Duration.ofSeconds(30),
    maxRetries: Int = 3,
    retryDelay: Duration = Duration.ofSeconds(2)
) : MavenCentralApiClient {

    private val logger: Logger = Logging.getLogger(DefaultMavenCentralApiClient::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()
    private val retryHandler: RetryHandler = RetryHandler(maxRetries, retryDelay, logger)

    private val httpClient: HttpClient = httpClient ?: HttpClient.newBuilder()
        .connectTimeout(connectTimeout)
        .build()

    /**
     * [Uploading a Deployment Bundle](https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle)
     */
    override suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<DeploymentId, String> {
        require(Files.exists(bundle)) { "Bundle file does not exist: $bundle" }
        require(Files.isRegularFile(bundle)) { "Bundle path is not a file: $bundle" }
        require(Files.size(bundle) > 0) { "Bundle file is empty: $bundle" }

        val query = buildQueryString(
            "name" to deploymentName,
            "publishingType" to publishingType?.id
        )

        val boundary = UUID.randomUUID().toString().replace("-", "")

        return apiCall("uploadDeploymentBundle") {
            uri = URI("$baseUrl/api/v1/publisher/upload$query")
            authorize(credentials)
            post(filePart(BUNDLE_FILE_PART_NAME, boundary, bundle))
            header("Content-Type", "multipart/form-data; boundary=$boundary")

            expectStatus(HttpStatus.CREATED)
            parseSuccess { body -> DeploymentId.fromString(body) }
            onSuccessLog { data -> "Bundle uploaded successfully. DeploymentId: $data" }
            onErrorLog { status, body -> "Failed to upload bundle. Status: ${status.code}, Response: $body" }
        }
    }

    override suspend fun deploymentStatus(
        credentials: Credentials, deploymentId: DeploymentId
    ): HttpResponseResult<DeploymentStatus, String> {
        return apiCall("deploymentStatus") {
            uri = URI("$baseUrl/api/v1/publisher/status?id=${urlEncode(deploymentId.toString())}")
            authorize(credentials)
            post()

            expectStatus(HttpStatus.OK)
            parseSuccess { body ->
                parseDeploymentStatus(body)?.also { status ->
                    logger.debug(
                        "Deployment status retrieved: deploymentId={}, state={}",
                        status.deploymentId, status.deploymentState
                    )
                }
            }
            onErrorLog { status, body -> "Failed to fetch deployment status. Status: ${status.code}, Response: $body" }
        }
    }

    /**
     * [Publish the Deployment](https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment)
     */
    override suspend fun publishDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String> {
        return apiCall("publishDeployment") {
            uri = URI("$baseUrl/api/v1/publisher/deployment/${urlEncode(deploymentId.toString())}")
            authorize(credentials)
            post()

            expectStatus(HttpStatus.NO_CONTENT)
            parseSuccess { Unit }
            onSuccessLog { "Deployment published successfully: $deploymentId" }
            onErrorLog { status, body -> "Failed to publish deployment. Status: ${status.code}, Response: $body" }
        }
    }

    /**
     * [Drop the Deployment](https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment)
     */
    override suspend fun dropDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String> {
        return apiCall("dropDeployment") {
            uri = URI("$baseUrl/api/v1/publisher/deployment/${urlEncode(deploymentId.toString())}")
            authorize(credentials)
            delete()

            expectStatus(HttpStatus.NO_CONTENT)
            parseSuccess { Unit }
            onSuccessLog { "Deployment dropped successfully: $deploymentId" }
            onErrorLog { status, body -> "Failed to drop deployment. Status: ${status.code}, Response: $body" }
        }
    }

    /**
     * Java 17-20 HttpClient doesn't implement AutoCloseable — the `is` check
     * avoids calling close() on versions where it doesn't exist.
     * Java 21+ requires explicit close for proper connection pool cleanup.
     */
    override fun close() {
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

    private enum class HttpMethod { POST, DELETE }

    /**
     * Fluent builder for HTTP API calls. Isolates per-request configuration
     * and provides a DSL for common patterns (auth, status expectation, logging).
     */
    private inner class ApiCallBuilder<T : Any> {
        lateinit var uri: URI
        private var method: HttpMethod = HttpMethod.POST
        private var body: HttpRequest.BodyPublisher = noBody()
        private val headers: MutableMap<String, String> = mutableMapOf()

        var expectedSuccessStatus: HttpStatus = HttpStatus.OK
        lateinit var successParser: (String) -> T?
        var successLogMessage: ((T) -> String)? = null
        var errorLogMessage: ((HttpStatus, String) -> String)? = null

        fun authorize(credentials: Credentials) {
            headers["Authorization"] = "Bearer ${credentials.bearerToken}"
        }

        fun post(bodyPublisher: HttpRequest.BodyPublisher = noBody()) {
            method = HttpMethod.POST
            body = bodyPublisher
        }

        fun delete() {
            method = HttpMethod.DELETE
        }

        fun header(name: String, value: String) {
            headers[name] = value
        }

        fun expectStatus(status: HttpStatus) {
            expectedSuccessStatus = status
        }

        fun parseSuccess(parser: (String) -> T?) {
            successParser = parser
        }

        fun onSuccessLog(message: (T) -> String) {
            successLogMessage = message
        }

        fun onErrorLog(message: (HttpStatus, String) -> String) {
            errorLogMessage = message
        }

        fun handleResponse(response: HttpResponse<String>, body: String): HttpResponseResult<T, String> {
            val responseHeaders = response.headers().map()
            val status = HttpStatus(response.statusCode())

            if (status != expectedSuccessStatus) {
                errorLogMessage?.let { logger.warn(it(status, body)) }
                return HttpResponseResult.Error(data = body, httpStatus = status, httpHeaders = responseHeaders)
            }

            val parsed = successParser(body)
                ?: run {
                    errorLogMessage?.let { logger.warn(it(status, body)) }
                    return HttpResponseResult.Error(data = body, httpStatus = status, httpHeaders = responseHeaders)
                }

            successLogMessage?.let { logger.debug(it(parsed)) }
            return HttpResponseResult.Success(data = parsed, httpStatus = status, httpHeaders = responseHeaders)
        }

        fun buildRequest(): HttpRequest {
            val builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)

            headers.forEach { (name, value) -> builder.header(name, value) }

            when (method) {
                HttpMethod.DELETE -> builder.DELETE()
                HttpMethod.POST -> builder.POST(body)
            }

            return builder.build()
        }
    }

    private suspend fun <T : Any> apiCall(
        operationName: String,
        configure: ApiCallBuilder<T>.() -> Unit
    ): HttpResponseResult<T, String> {
        val builder = ApiCallBuilder<T>().apply(configure)
        val request = builder.buildRequest()

        logger.debug("Sending {} request to: {}", operationName, builder.uri)

        return executeRequestWithRetry(request, operationName) { response, body ->
            builder.handleResponse(response, body)
        }
    }

    private suspend fun <T : Any> executeRequestWithRetry(
        request: HttpRequest,
        operationName: String,
        responseHandler: (HttpResponse<String>, String) -> HttpResponseResult<T, String>
    ): HttpResponseResult<T, String> {
        val result = retryHandler.executeWithRetry(
            operation = { attempt ->
                try {
                    val startTime = System.currentTimeMillis()
                    val response = withContext(Dispatchers.IO) {
                        httpClient.send(request, BodyHandlers.ofString(UTF_8))
                    }
                    val duration = System.currentTimeMillis() - startTime

                    logger.debug(
                        "HTTP request completed: operation={}, status={}, duration={}ms, attempt={}",
                        operationName, response.statusCode(), duration, attempt
                    )

                    val httpResult = responseHandler(response, response.body())

                    if (httpResult is HttpResponseResult.Error && isRetriableStatus(response.statusCode())) {
                        Failure(java.io.IOException("Retriable HTTP ${response.statusCode()}"))
                    } else {
                        Success(httpResult)
                    }
                } catch (e: Exception) {
                    Failure(e)
                }
            },
            shouldRetry = { exception -> isRetriableException(exception) },
            onRetry = { attempt, exception ->
                logger.warn(
                    "HTTP request failed: operation={}, attempt={}, error={}",
                    operationName, attempt, exception.message
                )
            }
        )

        return result.fold(
            onSuccess = { it },
            onFailure = { HttpResponseResult.UnexpectedError(cause = it) }
        )
    }

    private fun isRetriableStatus(statusCode: Int): Boolean =
        statusCode >= 500 || statusCode == HttpStatus.TOO_MANY_REQUESTS.code

    private fun isRetriableException(e: Exception): Boolean =
        e is HttpTimeoutException || e is java.net.ConnectException ||
        e is java.net.SocketTimeoutException || e is java.io.IOException

    private fun parseDeploymentStatus(json: String): DeploymentStatus? = try {
        objectMapper.readValue<DeploymentStatusDto>(json).toModel()
    } catch (e: Exception) {
        logger.error("Failed to parse deployment status: {}", json, e)
        null
    }

    private fun buildQueryString(vararg params: Pair<String, String?>): String =
        params
            .mapNotNull { (key, value) -> value?.let { "$key=${urlEncode(it)}" } }
            .joinToString("&")
            .takeIf { it.isNotEmpty() }
            ?.let { "?$it" }
            .orEmpty()

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
            deploymentId = DeploymentId.fromString(deploymentId),
            deploymentName = deploymentName,
            deploymentState = DeploymentStateType.of(deploymentState),
            purls = purls,
            errors = errors
        )
    }

    private companion object {
        private const val CRLF = "\r\n"
        private const val BUNDLE_FILE_PART_NAME = "bundle"

        private fun filePart(
            partName: String, boundary: String, file: Path
        ): HttpRequest.BodyPublisher {
            val sanitizedFilename = file.fileName.toString()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")

            return BodyPublishers.concat(
                BodyPublishers.ofString(
                    buildString {
                        append(CRLF).append("--$boundary").append(CRLF)
                        append("Content-Disposition: form-data; name=\"$partName\"; filename=\"")
                        append(sanitizedFilename).append("\"").append(CRLF)
                        append("Content-Type: application/octet-stream").append(CRLF)
                        append(CRLF)
                    }
                ),
                BodyPublishers.ofFile(file),
                BodyPublishers.ofString("$CRLF--$boundary--")
            )
        }
    }
}
