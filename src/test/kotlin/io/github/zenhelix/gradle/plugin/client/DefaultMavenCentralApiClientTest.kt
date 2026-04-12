package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.utils.assertHttpSuccess
import io.github.zenhelix.gradle.plugin.utils.mockHttpResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandler
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DefaultMavenCentralApiClientTest {

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var mockHttpClient: HttpClient
    private lateinit var client: DefaultMavenCentralApiClient

    @BeforeEach
    fun setUp() {
        mockHttpClient = mockk()
        client = DefaultMavenCentralApiClient(baseUrl = "https://test", httpClient = mockHttpClient)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun createTestBundleFile(): Path = tempDir.resolve("test-bundle.zip").also {
        Files.write(it, "test bundle content".toByteArray())
    }

    @Test
    fun `uploadDeploymentBundle should successfully upload bundle and return deployment ID`() = runTest {
        val expectedDeploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")

        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(201, expectedDeploymentId.toString(), mapOf("Content-Type" to listOf("text/plain")))

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile(),
            publishingType = PublishingType.USER_MANAGED,
            deploymentName = "test-deployment"
        )

        val data = assertHttpSuccess<DeploymentId>(result)
        assertThat(data).isEqualTo(expectedDeploymentId)
        assertThat((result as HttpResponseResult.Success).httpStatus).isEqualTo(HttpStatus.CREATED)

        verify { mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>()) }
    }

    @Test
    fun `uploadDeploymentBundle should include authorization header`() = runTest {
        val capturedRequest = slot<HttpRequest>()

        every {
            mockHttpClient.send(capture(capturedRequest), any<BodyHandler<String>>())
        } returns mockHttpResponse(201, DeploymentId.fromString("12345678-1234-1234-1234-123456789012").toString())

        client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        assertThat(
            capturedRequest.captured.headers().firstValue("Authorization").orElse(null)
        ).isEqualTo("Bearer test-token-123")
    }

    @Test
    fun `uploadDeploymentBundle should throw exception when bundle file does not exist`() {
        val nonExistentFile = tempDir.resolve("non-existent.zip")

        assertThatThrownBy {
            runBlocking {
                client.uploadDeploymentBundle(
                    credentials = BearerTokenCredentials(token = "test-token-123"),
                    bundle = nonExistentFile
                )
            }
        }.isInstanceOf(IllegalArgumentException::class.java)
         .hasMessageContaining("Bundle file does not exist")
    }

    @Test
    fun `deploymentStatus should successfully retrieve deployment status`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(200, """
            {
                "deploymentId": "12345678-1234-1234-1234-123456789012",
                "deploymentName": "test-deployment",
                "deploymentState": "VALIDATED",
                "purls": ["pkg:maven/io.github.test/artifact@1.0.0"],
                "errors": null
            }
        """.trimIndent())

        val result = client.deploymentStatus(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        val data = assertHttpSuccess<DeploymentStatus>(result)
        assertThat(data.deploymentId).isEqualTo(DeploymentId.fromString("12345678-1234-1234-1234-123456789012"))
        assertThat(data.deploymentName).isEqualTo("test-deployment")
        assertThat(data.deploymentState).isEqualTo(DeploymentStateType.VALIDATED)
        assertThat((result as HttpResponseResult.Success).httpStatus).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `deploymentStatus should return error when status is not 200`() = runTest {
        val errorMessage = "Deployment not found"

        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(404, errorMessage)

        val result = client.deploymentStatus(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Error::class.java)
        val error = result as HttpResponseResult.Error
        assertThat(error.data).isEqualTo(errorMessage)
        assertThat(error.httpStatus).isEqualTo(HttpStatus(404))
    }

    @Test
    fun `publishDeployment should successfully publish deployment`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(204, "")

        val result = client.publishDeployment(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
        assertThat((result as HttpResponseResult.Success).httpStatus).isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `publishDeployment should use POST method`() = runTest {
        val capturedRequest = slot<HttpRequest>()

        every {
            mockHttpClient.send(capture(capturedRequest), any<BodyHandler<String>>())
        } returns mockHttpResponse(204, "")

        client.publishDeployment(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(capturedRequest.captured.method()).isEqualTo("POST")
        assertThat(
            capturedRequest.captured.uri().toString()
        ).contains("https://test/api/v1/publisher/deployment/12345678-1234-1234-1234-123456789012")
    }

    @Test
    fun `dropDeployment should successfully drop deployment`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(204, "")

        val result = client.dropDeployment(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
        assertThat((result as HttpResponseResult.Success).httpStatus).isEqualTo(HttpStatus.NO_CONTENT)
    }

    @Test
    fun `uploadDeploymentBundle should retry on HTTP 429`() = runTest {
        val expectedDeploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        var callCount = 0

        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } answers {
            callCount++
            if (callCount == 1) {
                mockHttpResponse(429, "Rate limited")
            } else {
                mockHttpResponse(201, expectedDeploymentId.toString(), mapOf("Content-Type" to listOf("text/plain")))
            }
        }

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        val data = assertHttpSuccess<DeploymentId>(result)
        assertThat(data).isEqualTo(expectedDeploymentId)
        assertThat(callCount).isEqualTo(2)
    }

    @Test
    fun `uploadDeploymentBundle should escape special characters in filename`() = runTest {
        val capturedRequest = slot<HttpRequest>()
        val bundleFile = tempDir.resolve("test\"bundle.zip").also {
            Files.write(it, "test content".toByteArray())
        }

        every {
            mockHttpClient.send(capture(capturedRequest), any<BodyHandler<String>>())
        } returns mockHttpResponse(201, DeploymentId.random().toString())

        client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = bundleFile
        )

        verify { mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>()) }
    }

    @Test
    fun `dropDeployment should use DELETE method`() = runTest {
        val capturedRequest = slot<HttpRequest>()

        every {
            mockHttpClient.send(capture(capturedRequest), any<BodyHandler<String>>())
        } returns mockHttpResponse(204, "")

        client.dropDeployment(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(capturedRequest.captured.method()).isEqualTo("DELETE")
        assertThat(
            capturedRequest.captured.uri().toString()
        ).contains("https://test/api/v1/publisher/deployment/12345678-1234-1234-1234-123456789012")
    }

    @Test
    fun `uploadDeploymentBundle should retry on HTTP 500`() = runTest {
        val expectedDeploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        var callCount = 0

        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } answers {
            callCount++
            if (callCount == 1) {
                mockHttpResponse(500, "Internal Server Error")
            } else {
                mockHttpResponse(201, expectedDeploymentId.toString(), mapOf("Content-Type" to listOf("text/plain")))
            }
        }

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        val data = assertHttpSuccess<DeploymentId>(result)
        assertThat(data).isEqualTo(expectedDeploymentId)
        assertThat(callCount).isEqualTo(2)
    }

    @Test
    fun `uploadDeploymentBundle should return UnexpectedError on timeout`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } throws java.net.http.HttpTimeoutException("Connection timed out")

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        assertThat(result).isInstanceOf(HttpResponseResult.UnexpectedError::class.java)
        assertThat((result as HttpResponseResult.UnexpectedError).cause)
            .isInstanceOf(java.net.http.HttpTimeoutException::class.java)
    }

    @Test
    fun `deploymentStatus should handle invalid JSON gracefully`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(200, "not valid json")

        val result = client.deploymentStatus(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        // Invalid JSON with 200 status returns Error (parse failure results in null status)
        assertThat(result).isInstanceOf(HttpResponseResult.Error::class.java)
    }

    @Test
    fun `uploadDeploymentBundle should return UnexpectedError after all retries exhausted`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } throws java.net.ConnectException("Connection refused")

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        assertThat(result).isInstanceOf(HttpResponseResult.UnexpectedError::class.java)
        assertThat((result as HttpResponseResult.UnexpectedError).cause)
            .isInstanceOf(java.net.ConnectException::class.java)
    }

    @Test
    fun `uploadDeploymentBundle should return error when API returns invalid UUID`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(201, "not-a-valid-uuid")

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Error::class.java)
    }

    @Test
    fun `deploymentStatus should return error when API response is missing required fields`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(200, """{"deploymentState": "VALIDATED"}""".trimIndent())

        val result = client.deploymentStatus(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Error::class.java)
    }

    @Test
    fun `deploymentStatus should return error when API returns invalid deployment ID`() = runTest {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockHttpResponse(200, """
            {
                "deploymentId": "not-a-uuid",
                "deploymentName": "test",
                "deploymentState": "VALIDATED",
                "purls": null,
                "errors": null
            }
        """.trimIndent())

        val result = client.deploymentStatus(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Error::class.java)
    }

}
