package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MavenCentralApiClientImplTest {

    @TempDir
    private lateinit var tempDir: Path

    private lateinit var mockHttpClient: HttpClient
    private lateinit var client: MavenCentralApiClientImpl

    @BeforeEach
    fun setUp() {
        mockHttpClient = mockk()
        client = MavenCentralApiClientImpl(baseUrl = "https://test", httpClient = mockHttpClient)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun createTestBundleFile(): Path = tempDir.resolve("test-bundle.zip").also {
        Files.write(it, "test bundle content".toByteArray())
    }

    @Test
    fun `uploadDeploymentBundle should successfully upload bundle and return deployment ID`() {
        val expectedDeploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")

        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 201
            every { body() } returns expectedDeploymentId.toString()
            every { headers() } returns mockk { every { map() } returns mapOf("Content-Type" to listOf("text/plain")) }
        }

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile(),
            publishingType = PublishingType.USER_MANAGED,
            deploymentName = "test-deployment"
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
        val success = result as HttpResponseResult.Success
        assertThat(success.data).isEqualTo(expectedDeploymentId)
        assertThat(success.httpStatus).isEqualTo(201)

        verify { mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>()) }
    }

    @Test
    fun `uploadDeploymentBundle should include authorization header`() {
        val capturedRequest = slot<HttpRequest>()

        every {
            mockHttpClient.send(capture(capturedRequest), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 201
            every { body() } returns UUID.fromString("12345678-1234-1234-1234-123456789012").toString()
            every { headers() } returns mockk { every { map() } returns emptyMap() }
        }

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
            client.uploadDeploymentBundle(
                credentials = BearerTokenCredentials(token = "test-token-123"),
                bundle = nonExistentFile
            )
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("Bundle file does not exist")
    }

    @Test
    fun `deploymentStatus should successfully retrieve deployment status`() {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 200
            every { body() } returns """
            {
                "deploymentId": "12345678-1234-1234-1234-123456789012",
                "deploymentName": "test-deployment",
                "deploymentState": "VALIDATED",
                "purls": ["pkg:maven/io.github.test/artifact@1.0.0"],
                "errors": null
            }
        """.trimIndent()
            every { headers() } returns mockk { every { map() } returns emptyMap() }
        }

        val result = client.deploymentStatus(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
        val success = result as HttpResponseResult.Success<DeploymentStatus>
        assertThat(success.data.deploymentId).isEqualTo(UUID.fromString("12345678-1234-1234-1234-123456789012"))
        assertThat(success.data.deploymentName).isEqualTo("test-deployment")
        assertThat(success.data.deploymentState).isEqualTo(DeploymentStateType.VALIDATED)
        assertThat(success.httpStatus).isEqualTo(200)
    }

    @Test
    fun `deploymentStatus should return error when status is not 200`() {
        val errorMessage = "Deployment not found"

        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 404
            every { body() } returns errorMessage
            every { headers() } returns mockk { every { map() } returns emptyMap() }
        }

        val result = client.deploymentStatus(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Error::class.java)
        val error = result as HttpResponseResult.Error
        assertThat(error.data).isEqualTo(errorMessage)
        assertThat(error.httpStatus).isEqualTo(404)
    }

    @Test
    fun `publishDeployment should successfully publish deployment`() {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 204
            every { body() } returns ""
            every { headers() } returns mockk { every { map() } returns emptyMap() }
        }

        val result = client.publishDeployment(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
        assertThat((result as HttpResponseResult.Success).httpStatus).isEqualTo(204)
    }

    @Test
    fun `publishDeployment should use POST method`() {
        val capturedRequest = slot<HttpRequest>()

        every {
            mockHttpClient.send(capture(capturedRequest), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 204
            every { body() } returns ""
            every { headers() } returns mockk { every { map() } returns emptyMap() }
        }

        client.publishDeployment(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(capturedRequest.captured.method()).isEqualTo("POST")
        assertThat(
            capturedRequest.captured.uri().toString()
        ).contains("https://test/api/v1/publisher/deployment/12345678-1234-1234-1234-123456789012")
    }

    @Test
    fun `dropDeployment should successfully drop deployment`() {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 204
            every { body() } returns ""
            every { headers() } returns mockk { every { map() } returns emptyMap() }
        }

        val result = client.dropDeployment(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
        assertThat((result as HttpResponseResult.Success).httpStatus).isEqualTo(204)
    }

    @Test
    fun `dropDeployment should use DELETE method`() {
        val capturedRequest = slot<HttpRequest>()

        every {
            mockHttpClient.send(capture(capturedRequest), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 204
            every { body() } returns ""
            every { headers() } returns mockk { every { map() } returns emptyMap() }
        }

        client.dropDeployment(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
        )

        assertThat(capturedRequest.captured.method()).isEqualTo("DELETE")
        assertThat(
            capturedRequest.captured.uri().toString()
        ).contains("https://test/api/v1/publisher/deployment/12345678-1234-1234-1234-123456789012")
    }

}
