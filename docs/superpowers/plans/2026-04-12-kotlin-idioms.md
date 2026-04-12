# Kotlin Idioms Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the maven-central-publish codebase more idiomatic Kotlin through type-safety improvements (value classes), production code idioms (sealed interface, scope functions), test helpers, and DSL cleanup.

**Architecture:** The changes are largely mechanical refactoring with no architectural changes. Value classes (`DeploymentId`, `HttpStatus`) propagate through the client API layer and task layer. Test utilities are extracted into a shared file. All changes preserve existing behavior.

**Tech Stack:** Kotlin 2.3, Gradle 8.x, JUnit Jupiter, AssertJ, MockK, Kotlinx Coroutines

---

## File Structure

### New files
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentId.kt` — value class wrapping UUID
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpStatus.kt` — value class wrapping Int with standard constants
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/TestAssertions.kt` — reified assertion helpers + mock HTTP response builder

### Modified files (production)
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResult.kt` — `Int` → `HttpStatus` for httpStatus fields, foldHttp signatures
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentStatus.kt` — `UUID` → `DeploymentId`
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentError.kt` — `UUID` → `DeploymentId`, `Int` → `HttpStatus`, deduplicate toGradleException
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClient.kt` — `UUID` → `DeploymentId` in signatures
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/DefaultMavenCentralApiClient.kt` — `DeploymentId`, `HttpStatus`, `?.also`, `handleResponse` extraction
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/NoOpMavenCentralApiClient.kt` — `UUID` → `DeploymentId`
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandler.kt` — `UUID` → `DeploymentId`
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelper.kt` — `UUID` → `DeploymentId`, remove string matching
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/BundleChunker.kt` — functional validation style
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderExtension.kt` — credential sealed interface, SizeExtensions for bundle size
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/CredentialMapping.kt` — adapt to sealed interface properties
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt` — remove dead else branch
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt` — `UUID` → `DeploymentId`
- `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt` — `UUID` → `DeploymentId`

### Modified files (tests)
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/DefaultMavenCentralApiClientTest.kt` — mock builder, assertion helpers, assertThatThrownBy
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderCredentialExtensionTest.kt` — assertion helpers
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResultTest.kt` — `HttpStatus` adaptation
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelperTest.kt` — `DeploymentId`, update state conflict test
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandlerTest.kt` — `DeploymentId`
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentErrorTest.kt` — `DeploymentId`, `HttpStatus`
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleDropBehaviorTest.kt` — `DeploymentId`
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleDropBehaviorTest.kt` — `DeploymentId`

---

### Task 1: Create `DeploymentId` value class

**Files:**
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentId.kt`

- [ ] **Step 1: Create DeploymentId value class**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentId.kt
package io.github.zenhelix.gradle.plugin.client.model

import java.util.UUID

@JvmInline
public value class DeploymentId(val value: UUID) {
    override fun toString(): String = value.toString()

    public companion object {
        public fun fromString(value: String): DeploymentId = DeploymentId(UUID.fromString(value))
        public fun random(): DeploymentId = DeploymentId(UUID.randomUUID())
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentId.kt
git commit -m "feat: add DeploymentId value class"
```

---

### Task 2: Create `HttpStatus` value class

**Files:**
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpStatus.kt`

- [ ] **Step 1: Create HttpStatus value class**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpStatus.kt
package io.github.zenhelix.gradle.plugin.client.model

@JvmInline
public value class HttpStatus(val code: Int) {
    override fun toString(): String = code.toString()

    public companion object {
        public val OK: HttpStatus = HttpStatus(200)
        public val CREATED: HttpStatus = HttpStatus(201)
        public val NO_CONTENT: HttpStatus = HttpStatus(204)
        public val BAD_REQUEST: HttpStatus = HttpStatus(400)
        public val TOO_MANY_REQUESTS: HttpStatus = HttpStatus(429)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpStatus.kt
git commit -m "feat: add HttpStatus value class"
```

---

### Task 3: Propagate `HttpStatus` through `HttpResponseResult`

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResult.kt`

- [ ] **Step 1: Replace `Int` with `HttpStatus` in HttpResponseResult**

In `HttpResponseResult.kt`, change the sealed class and all subclasses:

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResult.kt
package io.github.zenhelix.gradle.plugin.client.model

public sealed class HttpResponseResult<out S : Any, out E : Any>(
    public open val httpStatus: HttpStatus?,
    public open val httpHeaders: Map<String, List<String>>?
) : Outcome<S, E?> {

    override fun <R> fold(onSuccess: (S) -> R, onFailure: (E?) -> R): R = when (this) {
        is Success         -> onSuccess(data)
        is Error           -> onFailure(data)
        is UnexpectedError -> onFailure(null)
    }

    public fun <R> foldHttp(
        onSuccess: (data: S, httpStatus: HttpStatus, httpHeaders: Map<String, List<String>>) -> R,
        onError: (data: E?, cause: Exception?, httpStatus: HttpStatus, httpHeaders: Map<String, List<String>>) -> R,
        onUnexpected: (cause: Exception, httpStatus: HttpStatus?, httpHeaders: Map<String, List<String>>?) -> R
    ): R = when (this) {
        is Success         -> onSuccess(data, httpStatus, httpHeaders)
        is Error           -> onError(data, cause, httpStatus, httpHeaders)
        is UnexpectedError -> onUnexpected(cause, httpStatus, httpHeaders)
    }

    override fun getOrNull(): S? = when (this) {
        is Success -> data
        else       -> null
    }

    override fun errorOrNull(): E? = when (this) {
        is Error -> data
        else     -> null
    }

    public fun causeOrNull(): Exception? = when (this) {
        is Error           -> cause
        is UnexpectedError -> cause
        is Success         -> null
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R> map(transform: (S) -> R): Outcome<R, E?> = when (this) {
        is Success         -> Success(data = transform(data) as Any, httpStatus = httpStatus, httpHeaders = httpHeaders) as Outcome<R, E?>
        is Error           -> this
        is UnexpectedError -> this
    }

    override fun <R> flatMap(transform: (S) -> Outcome<R, @UnsafeVariance E?>): Outcome<R, E?> = when (this) {
        is Success         -> transform(data)
        is Error           -> this
        is UnexpectedError -> this
    }

    public data class Success<out D : Any>(
        val data: D,
        override val httpStatus: HttpStatus = HttpStatus.OK,
        override val httpHeaders: Map<String, List<String>> = emptyMap()
    ) : HttpResponseResult<D, Nothing>(httpStatus = httpStatus, httpHeaders = httpHeaders)

    public data class Error<out E : Any>(
        val data: E? = null,
        val cause: Exception? = null,
        override val httpStatus: HttpStatus,
        override val httpHeaders: Map<String, List<String>> = emptyMap()
    ) : HttpResponseResult<Nothing, E>(httpStatus = httpStatus, httpHeaders = httpHeaders)

    public data class UnexpectedError(
        val cause: Exception,
        override val httpStatus: HttpStatus? = null,
        override val httpHeaders: Map<String, List<String>>? = null
    ) : HttpResponseResult<Nothing, Nothing>(httpStatus = httpStatus, httpHeaders = httpHeaders)

    public fun <OS : Any> copySuccess(
        data: (S) -> OS
    ): HttpResponseResult<OS, E> = when (val current = this) {
        is Success         -> Success(data = data(current.data), httpStatus = current.httpStatus, httpHeaders = current.httpHeaders)
        is Error           -> current
        is UnexpectedError -> current
    }

    public fun <OE : Any> copyError(
        error: (E?) -> OE?
    ): HttpResponseResult<S, OE> = when (val current = this) {
        is Success         -> current
        is Error           -> Error(data = error(current.data), cause = current.cause, httpStatus = current.httpStatus, httpHeaders = current.httpHeaders)
        is UnexpectedError -> current
    }
}
```

- [ ] **Step 2: Verify it compiles (expect compilation errors in dependent files — that's OK for now)**

Run: `./gradlew compileKotlin 2>&1 | head -50`
Expected: compilation errors in files that use `HttpResponseResult` with raw Int — these will be fixed in later tasks.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResult.kt
git commit -m "refactor: use HttpStatus value class in HttpResponseResult"
```

---

### Task 4: Propagate `HttpStatus` and `DeploymentId` through `DeploymentError`

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentError.kt`

- [ ] **Step 1: Update DeploymentError to use HttpStatus, DeploymentId, and deduplicate toGradleException**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentError.kt
package io.github.zenhelix.gradle.plugin.client.model

public sealed class DeploymentError(public val message: String) {
    public data class UploadFailed(val httpStatus: HttpStatus, val response: String?)
        : DeploymentError("Failed to upload bundle: HTTP ${httpStatus.code}")

    public data class UploadUnexpected(val cause: Exception)
        : DeploymentError("Unexpected error during bundle upload")

    public data class DeploymentFailed(val state: DeploymentStateType, val errors: Map<String, Any?>?)
        : DeploymentError(buildString {
            append("Deployment failed with status: $state")
            if (!errors.isNullOrEmpty()) append("\nErrors: $errors")
        })

    public data class StatusCheckFailed(val httpStatus: HttpStatus, val response: String?)
        : DeploymentError("Failed to check deployment status: HTTP ${httpStatus.code}")

    public data class StatusCheckUnexpected(val cause: Exception)
        : DeploymentError("Unexpected error while checking deployment status")

    public data class Timeout(val state: DeploymentStateType, val maxChecks: Int)
        : DeploymentError("Deployment did not complete after $maxChecks status checks. Current status: $state. Check Maven Central Portal for current status.")

    public data class PublishFailed(val deploymentId: DeploymentId, val httpStatus: HttpStatus)
        : DeploymentError("Failed to publish deployment $deploymentId: HTTP ${httpStatus.code}")

    public data class PublishUnexpected(val deploymentId: DeploymentId, val cause: Exception)
        : DeploymentError("Unexpected error publishing deployment $deploymentId")

    public val isDroppable: Boolean get() = when (this) {
        is DeploymentFailed -> state.isDroppable
        is Timeout -> state.isDroppable
        is StatusCheckFailed, is StatusCheckUnexpected -> true
        is UploadFailed, is UploadUnexpected, is PublishFailed, is PublishUnexpected -> false
    }
}

public fun DeploymentError.toGradleException(): MavenCentralDeploymentException {
    val cause = when (this) {
        is DeploymentError.UploadUnexpected -> cause
        is DeploymentError.StatusCheckUnexpected -> cause
        is DeploymentError.PublishUnexpected -> cause
        else -> null
    }
    return MavenCentralDeploymentException(error = this, message = message, cause = cause)
}

internal val DeploymentStateType.isDroppable: Boolean
    get() = when (this) {
        DeploymentStateType.PENDING,
        DeploymentStateType.VALIDATING,
        DeploymentStateType.VALIDATED,
        DeploymentStateType.FAILED,
        DeploymentStateType.UNKNOWN -> true
        DeploymentStateType.PUBLISHING,
        DeploymentStateType.PUBLISHED -> false
    }
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentError.kt
git commit -m "refactor: use HttpStatus and DeploymentId in DeploymentError, deduplicate toGradleException"
```

---

### Task 5: Propagate `DeploymentId` through `DeploymentStatus`

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentStatus.kt`

- [ ] **Step 1: Replace UUID with DeploymentId**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentStatus.kt
package io.github.zenhelix.gradle.plugin.client.model

public data class DeploymentStatus(
    val deploymentId: DeploymentId,
    val deploymentName: String,
    val deploymentState: DeploymentStateType,
    val purls: List<String>?,
    val errors: Map<String, Any?>?
)
```

Remove the `import java.util.UUID` line.

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentStatus.kt
git commit -m "refactor: use DeploymentId in DeploymentStatus"
```

---

### Task 6: Propagate `DeploymentId` through `MavenCentralApiClient` interface and implementations

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClient.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/DefaultMavenCentralApiClient.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/NoOpMavenCentralApiClient.kt`

- [ ] **Step 1: Update MavenCentralApiClient interface**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClient.kt
package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path

public interface MavenCentralApiClient : AutoCloseable {

    public suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType? = null, deploymentName: String? = null
    ): HttpResponseResult<DeploymentId, String>

    public suspend fun deploymentStatus(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<DeploymentStatus, String>

    public suspend fun publishDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String>

    public suspend fun dropDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String>

}
```

- [ ] **Step 2: Update DefaultMavenCentralApiClient**

In `DefaultMavenCentralApiClient.kt`, apply all changes together:
1. Replace `UUID` with `DeploymentId` in method signatures and return types
2. Replace `private const val HTTP_*` with `HttpStatus` constants
3. Replace verbose null-handling in `deploymentStatus` with `?.also`
4. Extract `handleResponse` into `ApiCallBuilder`

Full file:

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/DefaultMavenCentralApiClient.kt
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
```

- [ ] **Step 3: Update NoOpMavenCentralApiClient**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/NoOpMavenCentralApiClient.kt
package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path

/**
 * No-op API client for functional testing. Returns successful responses
 * without making any HTTP calls. Used when [TEST_BASE_URL] is set as the base URL.
 */
internal class NoOpMavenCentralApiClient : MavenCentralApiClient {

    override suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<DeploymentId, String> = HttpResponseResult.Success(DeploymentId.random())

    override suspend fun deploymentStatus(
        credentials: Credentials, deploymentId: DeploymentId
    ): HttpResponseResult<DeploymentStatus, String> = HttpResponseResult.Success(
        DeploymentStatus(
            deploymentId = DeploymentId.random(),
            deploymentName = "",
            deploymentState = DeploymentStateType.PUBLISHED,
            purls = null, errors = null,
        )
    )

    override suspend fun publishDeployment(
        credentials: Credentials, deploymentId: DeploymentId
    ): HttpResponseResult<Unit, String> = HttpResponseResult.Success(Unit)

    override suspend fun dropDeployment(
        credentials: Credentials, deploymentId: DeploymentId
    ): HttpResponseResult<Unit, String> = HttpResponseResult.Success(Unit)

    override fun close() {
        // No resources to close in no-op implementation
    }

}

/**
 * Sentinel URL that triggers the no-op [NoOpMavenCentralApiClient] in publish tasks.
 * Used exclusively in functional tests to avoid real HTTP calls.
 */
internal const val TEST_BASE_URL = "https://test.invalid"

/**
 * Creates an [MavenCentralApiClient] for the given [url].
 * Returns [NoOpMavenCentralApiClient] when [url] matches [TEST_BASE_URL],
 * otherwise creates a real [DefaultMavenCentralApiClient].
 */
internal fun createApiClient(url: String): MavenCentralApiClient =
    if (url == TEST_BASE_URL) NoOpMavenCentralApiClient() else DefaultMavenCentralApiClient(url)
```

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClient.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/client/DefaultMavenCentralApiClient.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/client/NoOpMavenCentralApiClient.kt
git commit -m "refactor: propagate DeploymentId and HttpStatus through API client layer"
```

---

### Task 7: Propagate `DeploymentId` through recovery layer

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandler.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelper.kt`

- [ ] **Step 1: Update DeploymentRecoveryHandler**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandler.kt
package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.isDroppable
import org.gradle.api.logging.Logger

internal class DeploymentRecoveryHandler(
    private val client: MavenCentralApiClient,
    private val credentials: Credentials,
    private val logger: Logger
) {
    suspend fun recover(deploymentId: DeploymentId, error: DeploymentError): DeploymentError {
        if (error.isDroppable) {
            logger.warn("Deployment failed, attempting to drop deployment {}", deploymentId)
            client.tryDropDeployment(credentials, deploymentId, logger)
        } else {
            logger.warn(
                "Deployment {} cannot be dropped. Check Maven Central Portal.",
                deploymentId
            )
        }
        return error
    }

    suspend fun recoverAll(
        deploymentIds: List<DeploymentId>,
        lastKnownStates: Map<DeploymentId, DeploymentStateType>,
        error: DeploymentError
    ): DeploymentError {
        val (droppable, nonDroppable) = deploymentIds.partition { id ->
            val state = lastKnownStates[id]
            state == null || state.isDroppable
        }

        if (nonDroppable.isNotEmpty()) {
            logger.warn(
                "Deployments {} are in non-droppable state. Check Maven Central Portal.",
                nonDroppable
            )
        }

        droppable.forEach { client.tryDropDeployment(credentials, it, logger) }
        return error
    }

    suspend fun recoverPublishFailure(
        allIds: List<DeploymentId>,
        publishedIds: Set<DeploymentId>,
        failedId: DeploymentId,
        error: DeploymentError
    ): DeploymentError {
        val unpublished = allIds.filter { it !in publishedIds && it != failedId }
        unpublished.forEach { client.tryDropDeployment(credentials, it, logger) }

        logger.warn(
            "{} deployment(s) may already be published and cannot be rolled back. Dropped {} remaining.",
            publishedIds.size, unpublished.size
        )
        return error
    }
}
```

- [ ] **Step 2: Update DeploymentDropHelper — DeploymentId + remove string matching**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelper.kt
package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import kotlin.coroutines.cancellation.CancellationException
import org.gradle.api.logging.Logger

/**
 * Best-effort attempt to drop a single deployment. Logs warnings on failure
 * but never throws (except for [CancellationException] which propagates for coroutine cancellation).
 *
 * HTTP 400 is treated as a state conflict (race condition): the deployment may have
 * transitioned to PUBLISHING/PUBLISHED between our last status check and the drop attempt.
 */
internal suspend fun MavenCentralApiClient.tryDropDeployment(
    creds: Credentials, deploymentId: DeploymentId, logger: Logger
) {
    try {
        when (val result = dropDeployment(creds, deploymentId)) {
            is HttpResponseResult.Success -> {
                logger.lifecycle("Dropped deployment {}", deploymentId)
            }
            is HttpResponseResult.Error -> {
                if (result.httpStatus == HttpStatus.BAD_REQUEST) {
                    logger.lifecycle(
                        "Deployment {} likely transitioned to non-droppable state. " +
                            "Check Maven Central Portal for current status.", deploymentId
                    )
                } else {
                    logger.warn("Failed to drop deployment {}: HTTP {}, Response: {}", deploymentId, result.httpStatus, result.data)
                }
            }
            is HttpResponseResult.UnexpectedError -> {
                logger.warn("Failed to drop deployment {}: {}", deploymentId, result.cause.message)
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Failed to drop deployment {}: {}", deploymentId, e.message)
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandler.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelper.kt
git commit -m "refactor: propagate DeploymentId through recovery layer, remove string matching"
```

---

### Task 8: Propagate `DeploymentId` through publish tasks

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt`

- [ ] **Step 1: Update PublishBundleMavenCentralTask**

Replace `import java.util.UUID` with `import io.github.zenhelix.gradle.plugin.client.model.DeploymentId`. Remove `import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult`. The only UUID reference is in `waitForDeploymentCompletion`'s `deploymentId: UUID` parameter — change to `DeploymentId`. The `uploadResult` already returns `DeploymentId` after the API change so the `when` match `is HttpResponseResult.Success` → `uploadResult.data` is already `DeploymentId`.

In `PublishBundleMavenCentralTask.kt`:
- Replace `import java.util.UUID` with `import io.github.zenhelix.gradle.plugin.client.model.DeploymentId`
- In `waitForDeploymentCompletion` signature: `deploymentId: UUID` → `deploymentId: DeploymentId`

- [ ] **Step 2: Update PublishSplitBundleMavenCentralTask**

In `PublishSplitBundleMavenCentralTask.kt`:
- Replace `import java.util.UUID` with `import io.github.zenhelix.gradle.plugin.client.model.DeploymentId`
- `val lastKnownStates = mutableMapOf<UUID, DeploymentStateType>()` → `mutableMapOf<DeploymentId, DeploymentStateType>()`
- `Outcome<List<UUID>, DeploymentError>` → `Outcome<List<DeploymentId>, DeploymentError>`
- `val deploymentIds = mutableListOf<UUID>()` → `mutableListOf<DeploymentId>()`
- `deploymentIds: List<UUID>` → `List<DeploymentId>` in `waitForAllDeploymentsValidated` and `publishAllDeployments` signatures
- `lastKnownStates: MutableMap<UUID, DeploymentStateType>` → `MutableMap<DeploymentId, DeploymentStateType>`
- `val publishedIds = mutableSetOf<UUID>()` → `mutableSetOf<DeploymentId>()`
- `val terminalStates = mutableMapOf<UUID, DeploymentStateType>()` → `mutableMapOf<DeploymentId, DeploymentStateType>()`

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (all production code now compiles)

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt
git commit -m "refactor: propagate DeploymentId through publish tasks"
```

---

### Task 9: Production code idioms — credential sealed interface, RetryHandler, BundleChunker, SizeExtensions

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderExtension.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/BundleChunker.kt`

- [ ] **Step 1: Replace boolean flags with sealed interface in MavenCentralUploaderCredentialExtension**

```kotlin
// In MavenCentralUploaderExtension.kt, replace the MavenCentralUploaderCredentialExtension class:
public open class MavenCentralUploaderCredentialExtension @Inject constructor(objects: ObjectFactory) {

    private sealed interface CredentialMode {
        data object None : CredentialMode
        data object Bearer : CredentialMode
        data object UsernamePassword : CredentialMode
        data object Both : CredentialMode
    }

    private var mode: CredentialMode = CredentialMode.None

    public val bearer: BearerCredentialExtension = objects.newInstance<BearerCredentialExtension>()
    public val usernamePassword: UsernamePasswordCredentialExtension =
        objects.newInstance<UsernamePasswordCredentialExtension>()

    public fun bearer(configure: Action<BearerCredentialExtension>) {
        mode = when (mode) {
            CredentialMode.None -> CredentialMode.Bearer
            CredentialMode.UsernamePassword -> CredentialMode.Both
            else -> mode
        }
        configure.execute(bearer)
    }

    public fun usernamePassword(configure: Action<UsernamePasswordCredentialExtension>) {
        mode = when (mode) {
            CredentialMode.None -> CredentialMode.UsernamePassword
            CredentialMode.Bearer -> CredentialMode.Both
            else -> mode
        }
        configure.execute(usernamePassword)
    }

    public val isBearerConfigured: Boolean get() = mode == CredentialMode.Bearer || mode == CredentialMode.Both
    public val isUsernamePasswordConfigured: Boolean get() = mode == CredentialMode.UsernamePassword || mode == CredentialMode.Both
}
```

Also in `UploaderSettingsExtension`, replace the `const val` for bundle size. Change:
```kotlin
public const val DEFAULT_MAX_BUNDLE_SIZE: Long = 256L * 1024L * 1024L // 256 MB
```
to:
```kotlin
public val DEFAULT_MAX_BUNDLE_SIZE: Long = 256.megabytes
```

Add import at top of file:
```kotlin
import io.github.zenhelix.gradle.plugin.extension.megabytes
```

Note: Since `megabytes` is defined in the same package (`io.github.zenhelix.gradle.plugin.extension`), the import is technically optional but should be explicit for clarity since it's an extension property from a different file.

- [ ] **Step 2: Remove dead else branch in RetryHandler**

In `RetryHandler.kt`, in the `when (result)` block inside `executeWithRetry`, remove:
```kotlin
                else -> return result
```

The `when` on `Outcome` sealed interface with `is Success` and `is Failure` branches is already exhaustive.

- [ ] **Step 3: Functional validation in BundleChunker**

In `BundleChunker.kt`, replace:
```kotlin
        modules.forEach { module ->
            if (module.sizeBytes > maxChunkSize) {
                return Failure(ChunkError.ModuleTooLarge(module.name, module.sizeBytes, maxChunkSize))
            }
        }
```
with:
```kotlin
        modules.firstOrNull { it.sizeBytes > maxChunkSize }
            ?.let { return Failure(ChunkError.ModuleTooLarge(it.name, it.sizeBytes, maxChunkSize)) }
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderExtension.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/client/BundleChunker.kt
git commit -m "refactor: credential sealed interface, remove dead else, functional BundleChunker validation"
```

---

### Task 10: Create test utilities — assertion helpers and mock builder

**Files:**
- Create: `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/TestAssertions.kt`

- [ ] **Step 1: Create test assertion helpers and mock HTTP response builder**

```kotlin
// src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/TestAssertions.kt
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.mockk.every
import io.mockk.mockk
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat

inline fun <reified T> assertSuccess(outcome: Outcome<*, *>): T {
    assertThat(outcome).isInstanceOf(Success::class.java)
    val value = (outcome as Success).value
    assertThat(value).isInstanceOf(T::class.java)
    @Suppress("UNCHECKED_CAST")
    return value as T
}

inline fun <reified T> assertHttpSuccess(result: HttpResponseResult<*, *>): T {
    assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
    val data = (result as HttpResponseResult.Success).data
    assertThat(data).isInstanceOf(T::class.java)
    @Suppress("UNCHECKED_CAST")
    return data as T
}

inline fun <reified T> assertFailure(outcome: Outcome<*, *>): T {
    assertThat(outcome).isInstanceOf(Failure::class.java)
    val error = (outcome as Failure).error
    assertThat(error).isInstanceOf(T::class.java)
    @Suppress("UNCHECKED_CAST")
    return error as T
}

fun mockHttpResponse(
    status: Int,
    body: String,
    headers: Map<String, List<String>> = emptyMap()
): HttpResponse<String> = mockk {
    every { statusCode() } returns status
    every { body() } returns body
    every { headers() } returns mockk { every { map() } returns headers }
}
```

- [ ] **Step 2: Verify test compilation**

Run: `./gradlew compileTestKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/TestAssertions.kt
git commit -m "test: add reified assertion helpers and mock HTTP response builder"
```

---

### Task 11: Update tests to use new value classes, assertion helpers, and mock builder

**Files:**
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/DefaultMavenCentralApiClientTest.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderCredentialExtensionTest.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResultTest.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelperTest.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandlerTest.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentErrorTest.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleDropBehaviorTest.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleDropBehaviorTest.kt`

- [ ] **Step 1: Update DefaultMavenCentralApiClientTest**

Key changes:
1. Replace `UUID.fromString(...)` with `DeploymentId.fromString(...)` where it represents deployment IDs
2. Replace inline mock blocks with `mockHttpResponse(...)` helper
3. Replace try/catch (lines 104-112) with `assertThatThrownBy`
4. Replace verbose `isInstanceOf` + cast patterns with `assertHttpSuccess<T>()`
5. Use `HttpStatus` where comparing httpStatus

Example of the key replacements:

```kotlin
// Import additions
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus
import io.github.zenhelix.gradle.plugin.utils.assertHttpSuccess
import io.github.zenhelix.gradle.plugin.utils.mockHttpResponse
import kotlinx.coroutines.runBlocking

// Replace inline mock with mockHttpResponse:
// OLD:
// every { mockHttpClient.send(...) } returns mockk<HttpResponse<String>> {
//     every { statusCode() } returns 201
//     every { body() } returns expectedDeploymentId.toString()
//     every { headers() } returns mockk { every { map() } returns mapOf(...) }
// }
// NEW:
every {
    mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
} returns mockHttpResponse(201, expectedDeploymentId.toString(), mapOf("Content-Type" to listOf("text/plain")))

// Replace verbose assertion pattern:
// OLD:
// assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
// val success = result as HttpResponseResult.Success
// assertThat(success.data).isEqualTo(expectedDeploymentId)
// NEW:
val data = assertHttpSuccess<DeploymentId>(result)
assertThat(data).isEqualTo(expectedDeploymentId)

// Replace try/catch with assertThatThrownBy:
// OLD:
// try {
//     client.uploadDeploymentBundle(...)
//     fail("Expected IllegalArgumentException")
// } catch (e: IllegalArgumentException) {
//     assertThat(e.message).contains("Bundle file does not exist")
// }
// NEW:
assertThatThrownBy {
    runBlocking {
        client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = nonExistentFile
        )
    }
}.isInstanceOf(IllegalArgumentException::class.java)
 .hasMessageContaining("Bundle file does not exist")
```

Note: `var callCount` in retry tests stays as-is per the design spec.

- [ ] **Step 2: Update MavenCentralUploaderCredentialExtensionTest**

Replace verbose cast chains with `assertSuccess<T>()` and `assertFailure<T>()`:

```kotlin
// Import additions
import io.github.zenhelix.gradle.plugin.utils.assertSuccess
import io.github.zenhelix.gradle.plugin.utils.assertFailure

// OLD:
// val result = project.mapCredentials(extension).get()
// assertThat(result).isInstanceOf(Success::class.java)
// val credentials = (result as Success).value
// assertThat(credentials).isInstanceOf(Credentials.BearerTokenCredentials::class.java)
// assertThat((credentials as Credentials.BearerTokenCredentials).token).isEqualTo("my-token")

// NEW:
val result = project.mapCredentials(extension).get()
val credentials = assertSuccess<Credentials.BearerTokenCredentials>(result)
assertThat(credentials.token).isEqualTo("my-token")
```

Apply same pattern for all test methods: `assertSuccess<Credentials.UsernamePasswordCredentials>`, `assertFailure<ValidationError.AmbiguousCredentials>`, `assertFailure<ValidationError.NoCredentials>`, `assertFailure<ValidationError.MissingCredential>`.

- [ ] **Step 3: Update HttpResponseResultTest**

Replace raw `Int` with `HttpStatus` in test data construction:

```kotlin
// OLD:
val result: HttpResponseResult<Int, String> = HttpResponseResult.Success(data = 42, httpStatus = 200)
// NEW:
val result: HttpResponseResult<Int, String> = HttpResponseResult.Success(data = 42, httpStatus = HttpStatus.OK)

// OLD:
val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", httpStatus = 400)
// NEW:
val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", httpStatus = HttpStatus.BAD_REQUEST)

// OLD:
HttpResponseResult.Error(data = "bad", cause = cause, httpStatus = 500)
// NEW:
HttpResponseResult.Error(data = "bad", cause = cause, httpStatus = HttpStatus(500))
```

- [ ] **Step 4: Update DeploymentDropHelperTest**

Replace `UUID.fromString(...)` with `DeploymentId.fromString(...)`:
```kotlin
private val deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
```

Update the `non-state-conflict 400` test — with the string matching removed, ANY HTTP 400 is now treated as state conflict. This test needs to be updated to reflect the new behavior. Either:
- Change the test to verify that HTTP 400 with non-state-conflict body ALSO logs lifecycle (not warn)
- Or replace it with a test for a different HTTP error code (e.g. 403) that should log a warning

Recommended: change the test to use HTTP 403 instead of 400:
```kotlin
@Test
fun `non-400 error logs warning`() = runTest {
    coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Error(
        data = """{"message":"Forbidden"}""",
        httpStatus = HttpStatus(403)
    )

    client.tryDropDeployment(creds, deploymentId, logger)

    verify {
        logger.warn(
            match<String> { it.contains("Failed to drop") },
            eq(deploymentId), any<HttpStatus>(), any()
        )
    }
}
```

Also update the state conflict test to use `HttpStatus`:
```kotlin
coEvery { client.dropDeployment(any(), any()) } returns HttpResponseResult.Error(
    data = """{"message":"Can only drop deployments that are in a VALIDATED or FAILED state."}""",
    httpStatus = HttpStatus.BAD_REQUEST
)
```

- [ ] **Step 5: Update DeploymentRecoveryHandlerTest**

Replace `UUID.fromString(...)` with `DeploymentId.fromString(...)`:
```kotlin
private val deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
// ...
val id1 = DeploymentId.fromString("11111111-1111-1111-1111-111111111111")
val id2 = DeploymentId.fromString("22222222-2222-2222-2222-222222222222")
```

Also update `DeploymentError` constructors that changed:
```kotlin
// OLD:
DeploymentError.UploadFailed(400, "Bad Request")
// NEW:
DeploymentError.UploadFailed(HttpStatus.BAD_REQUEST, "Bad Request")

// OLD:
DeploymentError.StatusCheckFailed(503, "Service Unavailable")
// NEW:
DeploymentError.StatusCheckFailed(HttpStatus(503), "Service Unavailable")

// OLD:
DeploymentError.PublishFailed(id2, 500)
// NEW:
DeploymentError.PublishFailed(id2, HttpStatus(500))
```

- [ ] **Step 6: Update PublishBundleDropBehaviorTest and PublishSplitBundleDropBehaviorTest**

Replace `UUID.fromString(...)` with `DeploymentId.fromString(...)`:
```kotlin
private val deploymentId = DeploymentId.fromString("12345678-1234-1234-1234-123456789012")
```

In `PublishSplitBundleDropBehaviorTest`:
```kotlin
val id1 = DeploymentId.fromString("11111111-1111-1111-1111-111111111111")
val id2 = DeploymentId.fromString("22222222-2222-2222-2222-222222222222")
```

Update mock return types — `HttpResponseResult.Success(deploymentId)` already returns correct type since the client interface now returns `DeploymentId`.

Also update `DeploymentStatus(...)` constructors:
```kotlin
DeploymentStatus(id1, "test", DeploymentStateType.VALIDATED, null, null)
```
These already use the field name `deploymentId` which is now `DeploymentId` type — the `DeploymentId.fromString(...)` values are correct.

- [ ] **Step 7: Update DeploymentErrorTest**

Replace raw `Int` with `HttpStatus` in `DeploymentError` constructors:

```kotlin
// Add import
import io.github.zenhelix.gradle.plugin.client.model.HttpStatus

// OLD:
DeploymentError.StatusCheckFailed(503, "Service Unavailable")
// NEW:
DeploymentError.StatusCheckFailed(HttpStatus(503), "Service Unavailable")

// OLD:
DeploymentError.UploadFailed(400, "Bad Request")
// NEW:
DeploymentError.UploadFailed(HttpStatus.BAD_REQUEST, "Bad Request")
```

No `UUID`/`DeploymentId` changes needed — this test doesn't use deployment IDs.

- [ ] **Step 8: Run all tests**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 9: Commit**

```bash
git add src/test/kotlin/
git commit -m "test: update all tests for DeploymentId, HttpStatus, assertion helpers, mock builder"
```

---

### Task 12: Run functional tests and full verification

**Files:** None (verification only)

- [ ] **Step 1: Run functional tests**

Run: `./gradlew functionalTest`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Run full check**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL (includes unit tests, functional tests, and jacoco report)

- [ ] **Step 3: Commit any remaining fixes if needed**

If any test failures were found and fixed, commit with:
```bash
git add -A
git commit -m "fix: address test failures from kotlin idioms refactoring"
```
