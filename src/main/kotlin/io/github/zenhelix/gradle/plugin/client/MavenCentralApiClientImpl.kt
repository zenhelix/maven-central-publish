package io.github.zenhelix.gradle.plugin.client

import groovy.json.JsonSlurper
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.net.URI
import java.net.URLEncoder.encode
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers.noBody
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID

public class MavenCentralApiClientImpl(private val baseUrl: String) : MavenCentralApiClient {

    private val httpClient: HttpClient = HttpClient.newHttpClient()

    /**
     * [Uploading a Deployment Bundle](https://central.sonatype.org/publish/publish-portal-api/#uploading-a-deployment-bundle)
     */
    override fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<UUID, String> {
        val path = "/api/v1/publisher/upload"
        val query = listOfNotNull(
            deploymentName?.let { "name=${encode(it, StandardCharsets.UTF_8)}" },
            publishingType?.id?.let { "publishingType=${encode(it, StandardCharsets.UTF_8)}" }
        ).takeIf { it.isNotEmpty() }?.let { "?${it.joinToString("&")}" } ?: ""

        val boundary = UUID.randomUUID().toString().replace("-", "")

        val request = HttpRequest.newBuilder(URI("$baseUrl$path$query"))
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(filePart(BUNDLE_FILE_PART_NAME, boundary, bundle))
            .build()

        return try {
            val response = httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8))

            val statusCode = response.statusCode()
            if (statusCode == 201) {
                HttpResponseResult.Success(
                    data = response.body().let { UUID.fromString(it) },
                    httpStatus = statusCode,
                    httpHeaders = response.headers().map()
                )
            } else {
                HttpResponseResult.Error(data = response.body(), httpStatus = statusCode, httpHeaders = response.headers().map())
            }
        } catch (e: Exception) {
            HttpResponseResult.UnexpectedError(cause = e)
        }
    }

    override fun deploymentStatus(credentials: Credentials, deploymentId: UUID): HttpResponseResult<DeploymentStatus, String> {
        val path = "/api/v1/publisher/status"
        val query = "?id=${encode(deploymentId.toString(), StandardCharsets.UTF_8)}"

        val request = HttpRequest.newBuilder(URI("$baseUrl$path$query"))
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .POST(noBody())
            .build()

        return try {
            val response = httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8))

            val statusCode = response.statusCode()
            if (statusCode == 200) {
                @Suppress("UNCHECKED_CAST")
                val jsonObject = JsonSlurper().parseText(response.body()) as Map<String, Any?>

                @Suppress("UNCHECKED_CAST")
                HttpResponseResult.Success(
                    data = DeploymentStatus(
                        deploymentId = (jsonObject["deploymentId"] as String).let { UUID.fromString(it) },
                        deploymentName = jsonObject["deploymentName"] as String,
                        deploymentState = (jsonObject["deploymentState"] as String).let { DeploymentStateType.Companion.of(it) },
                        purls = jsonObject["purls"] as List<String>?,
                        errors = jsonObject["errors"] as Map<String, Any?>?
                    ),
                    httpStatus = statusCode,
                    httpHeaders = response.headers().map()
                )
            } else {
                HttpResponseResult.Error(data = response.body(), httpStatus = statusCode, httpHeaders = response.headers().map())
            }
        } catch (e: Exception) {
            HttpResponseResult.UnexpectedError(cause = e)
        }
    }

    /**
     * [Publish the Deployment](https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment)
     */
    override fun publishDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String> {
        val path = "/api/v1/publisher/deployment/${encode(deploymentId.toString(), StandardCharsets.UTF_8)}"

        val request = HttpRequest.newBuilder(URI("$baseUrl$path"))
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .POST(noBody())
            .build()

        return try {
            val response = httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8))

            val statusCode = response.statusCode()
            if (statusCode == 204) {
                HttpResponseResult.Success(
                    data = Unit,
                    httpStatus = statusCode,
                    httpHeaders = response.headers().map()
                )
            } else {
                HttpResponseResult.Error(data = response.body(), httpStatus = statusCode, httpHeaders = response.headers().map())
            }
        } catch (e: Exception) {
            HttpResponseResult.UnexpectedError(cause = e)
        }
    }

    /**
     * [Drop the Deployment](https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment)
     */
    override fun dropDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String> {
        val path = "/api/v1/publisher/deployment/${encode(deploymentId.toString(), StandardCharsets.UTF_8)}"

        val request = HttpRequest.newBuilder(URI("$baseUrl$path"))
            .header("Authorization", "Bearer ${credentials.bearerToken}")
            .DELETE()
            .build()

        return try {
            val response = httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8))

            val statusCode = response.statusCode()
            if (statusCode == 204) {
                HttpResponseResult.Success(
                    data = Unit,
                    httpStatus = statusCode,
                    httpHeaders = response.headers().map()
                )
            } else {
                HttpResponseResult.Error(data = response.body(), httpStatus = statusCode, httpHeaders = response.headers().map())
            }
        } catch (e: Exception) {
            HttpResponseResult.UnexpectedError(cause = e)
        }
    }

    private companion object {
        private const val CRLF = "\r\n"
        private const val BUNDLE_FILE_PART_NAME = "bundle"

        private fun filePart(partName: String, boundary: String, file: Path): HttpRequest.BodyPublisher {
            return HttpRequest.BodyPublishers.concat(
                HttpRequest.BodyPublishers.ofString(
                    StringBuilder()
                        .append(CRLF).append("--$boundary").append(CRLF)
                        .append("Content-Disposition: form-data; name=\"$partName\"; filename=\"").append(file.fileName.toString()).append("\"").append(CRLF)
                        .append("Content-Type: application/octet-stream").append(CRLF)
                        .append(CRLF)
                        .toString()
                ),
                HttpRequest.BodyPublishers.ofFile(file),
                HttpRequest.BodyPublishers.ofString("$CRLF--$boundary--")
            )
        }
    }

}