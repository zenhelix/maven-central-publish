# Kotlin Idioms Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor maven-central-publish Gradle plugin to idiomatic Kotlin: coroutines, Outcome extensions, DSL builder for HTTP, plugin decomposition, naming cleanup.

**Architecture:** Bottom-up in 5 phases. Each phase compiles and passes tests before the next begins. Phase 1 stabilizes models, Phase 2 introduces suspend API + DSL, Phase 3 adapts tasks, Phase 4 splits the plugin, Phase 5 renames and cleans up.

**Tech Stack:** Kotlin, Gradle Plugin API, kotlinx-coroutines-core, Java HttpClient, JUnit 5, AssertJ, MockK

**Spec:** `docs/superpowers/specs/2026-04-12-kotlin-idioms-refactoring-design.md`

---

## Phase 1: Foundation

### Task 1: Remove `ResponseResult` and make `HttpResponseResult` standalone

**Files:**
- Delete: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/Response.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResult.kt`
- Test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResultTest.kt` (existing tests must still pass)

- [ ] **Step 1: Delete `Response.kt`**

Delete the file `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/Response.kt` entirely.

- [ ] **Step 2: Rewrite `HttpResponseResult` as standalone sealed class**

Replace the entire content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResult.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

public sealed class HttpResponseResult<out S : Any, out E : Any>(
    public open val httpStatus: Int?,
    public open val httpHeaders: Map<String, List<String>>?
) : Outcome<S, E?> {

    override fun <R> fold(onSuccess: (S) -> R, onFailure: (E?) -> R): R = when (this) {
        is Success         -> onSuccess(data)
        is Error           -> onFailure(data)
        is UnexpectedError -> onFailure(null)
    }

    public fun <R> foldHttp(
        onSuccess: (data: S, httpStatus: Int, httpHeaders: Map<String, List<String>>) -> R,
        onError: (data: E?, cause: Exception?, httpStatus: Int, httpHeaders: Map<String, List<String>>) -> R,
        onUnexpected: (cause: Exception, httpStatus: Int?, httpHeaders: Map<String, List<String>>?) -> R
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

    override fun <R> map(transform: (S) -> R): Outcome<R, E?> = when (this) {
        is Success         -> Success(data = transform(data), httpStatus = httpStatus, httpHeaders = httpHeaders)
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
        override val httpStatus: Int = 200,
        override val httpHeaders: Map<String, List<String>> = emptyMap()
    ) : HttpResponseResult<D, Nothing>(httpStatus = httpStatus, httpHeaders = httpHeaders)

    public data class Error<out E : Any>(
        val data: E? = null,
        val cause: Exception? = null,
        override val httpStatus: Int,
        override val httpHeaders: Map<String, List<String>> = emptyMap()
    ) : HttpResponseResult<Nothing, E>(httpStatus = httpStatus, httpHeaders = httpHeaders)

    public data class UnexpectedError(
        val cause: Exception,
        override val httpStatus: Int? = null,
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

Key changes from the original:
- No longer extends `ResponseResult<S, E>()`
- No `companion object` with `of(ResponseResult)` conversion methods (dead code)
- No custom `equals`/`hashCode`/`toString` overrides (data class subclasses handle this)
- `map()` no longer needs `@Suppress("UNCHECKED_CAST")` — returns `this` directly for Error/UnexpectedError (covariant `Nothing` makes this safe)

- [ ] **Step 3: Run tests**

Run: `./gradlew test --tests "*.HttpResponseResultTest" --tests "*.OutcomeTest" --tests "*.MavenCentralApiClientImplTest" -q`
Expected: All tests PASS

- [ ] **Step 4: Run full test suite**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: remove ResponseResult, make HttpResponseResult standalone"
```

---

### Task 2: Extend `Outcome` with utility extensions

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/Outcome.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/OutcomeTest.kt`

- [ ] **Step 1: Write tests for new Outcome extensions**

Add the following tests to `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/OutcomeTest.kt`:

```kotlin
@Test
fun `getOrThrow returns value for Success`() {
    val result: Outcome<Int, String> = Success(42)
    assertThat(result.getOrThrow { RuntimeException(it) }).isEqualTo(42)
}

@Test
fun `getOrThrow throws transformed error for Failure`() {
    val result: Outcome<Int, String> = Failure("boom")
    assertThatThrownBy {
        result.getOrThrow { IllegalStateException(it) }
    }.isInstanceOf(IllegalStateException::class.java)
        .hasMessage("boom")
}

@Test
fun `onSuccess executes action for Success and returns self`() {
    val result: Outcome<Int, String> = Success(42)
    var captured = 0
    val returned = result.onSuccess { captured = it }
    assertThat(captured).isEqualTo(42)
    assertThat(returned).isSameAs(result)
}

@Test
fun `onSuccess does not execute action for Failure`() {
    val result: Outcome<Int, String> = Failure("error")
    var called = false
    result.onSuccess { called = true }
    assertThat(called).isFalse()
}

@Test
fun `onFailure executes action for Failure and returns self`() {
    val result: Outcome<Int, String> = Failure("error")
    var captured = ""
    val returned = result.onFailure { captured = it }
    assertThat(captured).isEqualTo("error")
    assertThat(returned).isSameAs(result)
}

@Test
fun `onFailure does not execute action for Success`() {
    val result: Outcome<Int, String> = Success(42)
    var called = false
    result.onFailure { called = true }
    assertThat(called).isFalse()
}

@Test
fun `mapError transforms error for Failure`() {
    val result: Outcome<Int, String> = Failure("error")
    val mapped = result.mapError { it.length }
    assertThat(mapped.errorOrNull()).isEqualTo(5)
}

@Test
fun `mapError preserves Success`() {
    val result: Outcome<Int, String> = Success(42)
    val mapped = result.mapError { it.length }
    assertThat(mapped.getOrNull()).isEqualTo(42)
}
```

Add this import at the top of the test file:
```kotlin
import org.assertj.core.api.Assertions.assertThatThrownBy
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "*.OutcomeTest" -q`
Expected: FAIL — `getOrThrow`, `onSuccess`, `onFailure`, `mapError` not found

- [ ] **Step 3: Implement the extensions**

Add to `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/Outcome.kt` after the existing `getOrElse` function:

```kotlin
public fun <T, E> Outcome<T, E>.getOrThrow(transform: (E) -> Throwable): T =
    fold(onSuccess = { it }, onFailure = { throw transform(it) })

public fun <T, E> Outcome<T, E>.onSuccess(action: (T) -> Unit): Outcome<T, E> {
    if (this is Success) action(value)
    return this
}

public fun <T, E> Outcome<T, E>.onFailure(action: (E) -> Unit): Outcome<T, E> {
    if (this is Failure) action(error)
    return this
}

public fun <T, E, R> Outcome<T, E>.mapError(transform: (E) -> R): Outcome<T, R> = when (this) {
    is Success -> this
    is Failure -> Failure(transform(error))
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*.OutcomeTest" -q`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add getOrThrow, onSuccess, onFailure, mapError extensions to Outcome"
```

---

### Task 3: Remove `Credentials.equals/hashCode` and add coroutines dependency

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/Credentials.kt`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Remove equals/hashCode from Credentials**

Replace the entire content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/Credentials.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

import java.io.Serializable
import java.util.Base64

public sealed class Credentials : Serializable {
    public data class UsernamePasswordCredentials(val username: String, val password: String) : Credentials()
    public data class BearerTokenCredentials(val token: String) : Credentials()

    public val bearerToken: String by lazy {
        when (this) {
            is BearerTokenCredentials      -> this.token
            is UsernamePasswordCredentials -> Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        }
    }
}
```

- [ ] **Step 2: Add kotlinx-coroutines-core dependency**

In `build.gradle.kts`, add to the `dependencies` block:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
```

Add it after the existing `implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")` line.

Also add to the test dependencies in the `test` suite block:

```kotlin
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
```

Add it after the existing `implementation("io.mockk:mockk:1.14.9")` line.

- [ ] **Step 3: Run full test suite**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove Credentials equals/hashCode, add kotlinx-coroutines dependency"
```

---

## Phase 2: Client — Suspend API and DSL Builder

### Task 4: Make `RetryHandler` suspend-based

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerTest.kt`

- [ ] **Step 1: Update RetryHandler tests for suspend**

Replace the entire content of `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerTest.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Success
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `should return Success on first attempt when operation succeeds`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(10), logger = logger)

        val result = handler.executeWithRetry(
            operation = { Success("ok") }
        )

        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `should return Failure after all retries exhausted`() = runTest {
        val handler = RetryHandler(maxRetries = 2, baseDelay = Duration.ofMillis(1), logger = logger)

        val result = handler.executeWithRetry(
            operation = { Failure(RuntimeException("always fails")) },
            shouldRetry = { true }
        )

        assertThat(result.errorOrNull()).isInstanceOf(RuntimeException::class.java)
        assertThat(result.errorOrNull()!!.message).isEqualTo("always fails")
    }

    @Test
    fun `should return Failure immediately when shouldRetry returns false`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(1), logger = logger)
        var attempts = 0

        val result = handler.executeWithRetry(
            operation = {
                attempts++
                Failure(RuntimeException("not retriable"))
            },
            shouldRetry = { false }
        )

        assertThat(attempts).isEqualTo(1)
        assertThat(result.errorOrNull()!!.message).isEqualTo("not retriable")
    }

    @Test
    fun `should retry and eventually succeed`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(1), logger = logger)
        var attempts = 0

        val result = handler.executeWithRetry(
            operation = {
                attempts++
                if (attempts < 3) Failure(RuntimeException("retry me"))
                else Success("ok")
            },
            shouldRetry = { true }
        )

        assertThat(attempts).isEqualTo(3)
        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `should support cancellation during delay`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofSeconds(10), logger = logger)
        var attempt = 0

        val job = launch {
            handler.executeWithRetry(
                operation = { attemptNum ->
                    attempt = attemptNum
                    Failure(RuntimeException("always fails"))
                },
                shouldRetry = { true }
            )
        }

        advanceTimeBy(100)
        job.cancel()
        assertThat(attempt).isEqualTo(1)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "*.RetryHandlerTest" -q`
Expected: FAIL — `executeWithRetry` is not `suspend` yet

- [ ] **Step 3: Implement suspend RetryHandler**

Replace the entire content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success
import java.time.Duration
import kotlinx.coroutines.delay
import org.gradle.api.logging.Logger

public class RetryHandler(
    private val maxRetries: Int,
    private val baseDelay: Duration,
    private val logger: Logger
) {
    init {
        require(maxRetries >= 1) { "maxRetries must be at least 1, got: $maxRetries" }
        require(!baseDelay.isNegative && !baseDelay.isZero) {
            "baseDelay must be positive, got: $baseDelay"
        }
    }

    public suspend fun <T> executeWithRetry(
        operation: suspend (attempt: Int) -> Outcome<T, Exception>,
        shouldRetry: (Exception) -> Boolean = { true },
        onRetry: ((attempt: Int, exception: Exception) -> Unit)? = null
    ): Outcome<T, Exception> {
        var attempt = 1
        var lastError: Exception? = null

        while (attempt <= maxRetries) {
            val result = operation(attempt)

            when (result) {
                is Success -> return result
                is Failure -> {
                    lastError = result.error

                    if (!shouldRetry(result.error)) {
                        logger.debug("Exception is not retriable, failing immediately: {}", result.error.message)
                        return result
                    }

                    if (attempt >= maxRetries) {
                        logger.warn("Operation failed after {} attempts", maxRetries, result.error)
                        return result
                    }

                    onRetry?.invoke(attempt, result.error)

                    val delayMillis = calculateBackoffDelay(attempt)
                    logger.debug("Retrying after {}ms (attempt {}/{}): {}", delayMillis, attempt, maxRetries, result.error.message)

                    delay(delayMillis)
                }
                else -> return result
            }

            attempt++
        }

        return Failure(lastError ?: Exception("Operation failed after $maxRetries attempts"))
    }

    internal fun calculateBackoffDelay(attempt: Int): Long {
        val maxShift = 30
        val shift = (attempt - 1).coerceAtMost(maxShift)
        return (baseDelay.toMillis() * (1L shl shift)).coerceAtMost(MAX_BACKOFF_DELAY_MILLIS)
    }

    internal companion object {
        internal const val MAX_BACKOFF_DELAY_MILLIS = 5 * 60 * 1000L
    }
}
```

Key changes:
- `executeWithRetry` is now `suspend fun`
- `operation` parameter is now `suspend` lambda
- `Thread.sleep(delayMillis)` replaced with `delay(delayMillis)`
- `try-catch InterruptedException` block removed — coroutine cancellation handled natively
- Removed top-level `retryHandler()` factory function (dead code per spec)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*.RetryHandlerTest" --tests "*.RetryHandlerBackoffTest" -q`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: make RetryHandler suspend-based with coroutine delay"
```

---

### Task 5: Make `MavenCentralApiClient` interface suspend

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClient.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/NoOpMavenCentralApiClient.kt`

- [ ] **Step 1: Make interface methods suspend**

Replace the content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClient.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path
import java.util.UUID

public interface MavenCentralApiClient : AutoCloseable {

    public suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType? = null, deploymentName: String? = null
    ): HttpResponseResult<UUID, String>

    public suspend fun deploymentStatus(credentials: Credentials, deploymentId: UUID): HttpResponseResult<DeploymentStatus, String>

    public suspend fun publishDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String>

    public suspend fun dropDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String>

}
```

- [ ] **Step 2: Update NoOpMavenCentralApiClient**

Replace the content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/NoOpMavenCentralApiClient.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path
import java.util.UUID

/**
 * No-op API client for functional testing. Returns successful responses
 * without making any HTTP calls. Used when [TEST_BASE_URL] is set as the base URL.
 */
internal class NoOpMavenCentralApiClient : MavenCentralApiClient {

    override suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<UUID, String> = HttpResponseResult.Success(UUID.randomUUID())

    override suspend fun deploymentStatus(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<DeploymentStatus, String> = HttpResponseResult.Success(
        DeploymentStatus(
            deploymentId = UUID.randomUUID(),
            deploymentName = "",
            deploymentState = DeploymentStateType.PUBLISHED,
            purls = null, errors = null,
        )
    )

    override suspend fun publishDeployment(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<Unit, String> = HttpResponseResult.Success(Unit)

    override suspend fun dropDeployment(
        credentials: Credentials, deploymentId: UUID
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
 * otherwise creates a real [MavenCentralApiClientImpl].
 */
internal fun createApiClient(url: String): MavenCentralApiClient =
    if (url == TEST_BASE_URL) NoOpMavenCentralApiClient() else MavenCentralApiClientImpl(url)
```

- [ ] **Step 3: Commit (compilation will break until Task 6 completes — that's expected)**

```bash
git add -A
git commit -m "refactor: make MavenCentralApiClient interface suspend"
```

---

### Task 6: Rewrite `MavenCentralApiClientImpl` with DSL builder and suspend

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImplTest.kt`

- [ ] **Step 1: Rewrite `MavenCentralApiClientImpl` with DSL**

Replace the entire content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.client

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.utils.RetryHandler
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpRequest.BodyPublishers.noBody
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
    override suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<UUID, String> {
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

            expectStatus(HTTP_CREATED)
            parseSuccess { body -> UUID.fromString(body) }
            onSuccessLog { data -> "Bundle uploaded successfully. DeploymentId: $data" }
            onErrorLog { status, body -> "Failed to upload bundle. Status: $status, Response: $body" }
        }
    }

    override suspend fun deploymentStatus(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<DeploymentStatus, String> {
        return apiCall("deploymentStatus") {
            uri = URI("$baseUrl/api/v1/publisher/status?id=${urlEncode(deploymentId.toString())}")
            authorize(credentials)
            post()

            expectStatus(HTTP_OK)
            parseSuccess { body ->
                val status = parseDeploymentStatus(body)
                if (status != null) {
                    logger.debug(
                        "Deployment status retrieved: deploymentId={}, state={}",
                        status.deploymentId, status.deploymentState
                    )
                    status
                } else {
                    null
                }
            }
            onErrorLog { status, body -> "Failed to fetch deployment status. Status: $status, Response: $body" }
        }
    }

    /**
     * [Publish the Deployment](https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment)
     */
    override suspend fun publishDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String> {
        return apiCall("publishDeployment") {
            uri = URI("$baseUrl/api/v1/publisher/deployment/${urlEncode(deploymentId.toString())}")
            authorize(credentials)
            post()

            expectStatus(HTTP_NO_CONTENT)
            parseSuccess { Unit }
            onSuccessLog { "Deployment published successfully: $deploymentId" }
            onErrorLog { status, body -> "Failed to publish deployment. Status: $status, Response: $body" }
        }
    }

    /**
     * [Drop the Deployment](https://central.sonatype.org/publish/publish-portal-api/#publish-or-drop-the-deployment)
     */
    override suspend fun dropDeployment(credentials: Credentials, deploymentId: UUID): HttpResponseResult<Unit, String> {
        return apiCall("dropDeployment") {
            uri = URI("$baseUrl/api/v1/publisher/deployment/${urlEncode(deploymentId.toString())}")
            authorize(credentials)
            delete()

            expectStatus(HTTP_NO_CONTENT)
            parseSuccess { Unit }
            onSuccessLog { "Deployment dropped successfully: $deploymentId" }
            onErrorLog { status, body -> "Failed to drop deployment. Status: $status, Response: $body" }
        }
    }

    /**
     * Closes the underlying HTTP client.
     *
     * On Java 21+, [HttpClient] implements [AutoCloseable] and connection pools
     * are properly shut down. On Java 17-20, [HttpClient] does not implement
     * [AutoCloseable] — connections are managed by the JVM's internal pool
     * and cleaned up on GC. No explicit close is needed on these versions.
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

    // ── DSL Builder ─────────────────────────────────────────────────────

    private inner class ApiCallBuilder<T : Any> {
        lateinit var uri: URI
        private var method: String = "POST"
        private var body: HttpRequest.BodyPublisher = noBody()
        private val headers: MutableMap<String, String> = mutableMapOf()

        var expectedSuccessStatus: Int = HTTP_OK
        lateinit var successParser: (String) -> T?
        var successLogMessage: ((T) -> String)? = null
        var errorLogMessage: ((Int, String) -> String)? = null

        fun authorize(credentials: Credentials) {
            headers["Authorization"] = "Bearer ${credentials.bearerToken}"
        }

        fun post(bodyPublisher: HttpRequest.BodyPublisher = noBody()) {
            method = "POST"
            body = bodyPublisher
        }

        fun delete() {
            method = "DELETE"
        }

        fun header(name: String, value: String) {
            headers[name] = value
        }

        fun expectStatus(status: Int) {
            expectedSuccessStatus = status
        }

        fun parseSuccess(parser: (String) -> T?) {
            successParser = parser
        }

        fun onSuccessLog(message: (T) -> String) {
            successLogMessage = message
        }

        fun onErrorLog(message: (Int, String) -> String) {
            errorLogMessage = message
        }

        fun buildRequest(): HttpRequest {
            val builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)

            headers.forEach { (name, value) -> builder.header(name, value) }

            when (method) {
                "POST" -> builder.POST(body)
                "DELETE" -> builder.DELETE()
                else -> builder.POST(body)
            }

            return builder.build()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> apiCall(
        operationName: String,
        configure: ApiCallBuilder<T>.() -> Unit
    ): HttpResponseResult<T, String> {
        val builder = ApiCallBuilder<T>().apply(configure)
        val request = builder.buildRequest()

        logger.debug("Sending {} request to: {}", operationName, builder.uri)

        return executeRequestWithRetry(request, operationName) { response, body ->
            if (response.statusCode() == builder.expectedSuccessStatus) {
                val parsed = builder.successParser(body)
                if (parsed != null) {
                    builder.successLogMessage?.let { logger.debug(it(parsed)) }
                    HttpResponseResult.Success(
                        data = parsed,
                        httpStatus = response.statusCode(),
                        httpHeaders = response.headers().map()
                    )
                } else {
                    builder.errorLogMessage?.let { logger.warn(it(response.statusCode(), body)) }
                    HttpResponseResult.Error(
                        data = body,
                        httpStatus = response.statusCode(),
                        httpHeaders = response.headers().map()
                    )
                }
            } else {
                builder.errorLogMessage?.let { logger.warn(it(response.statusCode(), body)) }
                HttpResponseResult.Error(
                    data = body,
                    httpStatus = response.statusCode(),
                    httpHeaders = response.headers().map()
                )
            }
        }
    }

    private suspend fun <T : Any> executeRequestWithRetry(
        request: HttpRequest,
        operationName: String,
        responseHandler: (java.net.http.HttpResponse<String>, String) -> HttpResponseResult<T, String>
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
        statusCode >= 500 || statusCode == HTTP_TOO_MANY_REQUESTS

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
        private const val HTTP_TOO_MANY_REQUESTS = 429

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

- [ ] **Step 2: Update API client tests for suspend**

In `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImplTest.kt`, make the following changes:

1. Add import: `import kotlinx.coroutines.test.runTest`
2. Wrap every `@Test` function body in `runTest { ... }`. For example:

```kotlin
@Test
fun `uploadDeploymentBundle should successfully upload bundle and return deployment ID`() = runTest {
    // ... existing test body unchanged ...
}
```

Apply `= runTest { ... }` to ALL test methods in the file. The test bodies remain identical — only the wrapping changes.

Exception: the test `uploadDeploymentBundle should throw exception when bundle file does not exist` does NOT need `runTest` since `require` throws before any suspend call. But wrapping it in `runTest` is harmless and consistent.

- [ ] **Step 3: Run API client tests**

Run: `./gradlew test --tests "*.MavenCentralApiClientImplTest" -q`
Expected: All tests PASS

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: rewrite MavenCentralApiClientImpl with DSL builder and suspend API"
```

---

### Task 7: Make recovery layer suspend

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelper.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandler.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelperTest.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandlerTest.kt`

- [ ] **Step 1: Update DeploymentDropHelper to suspend**

Replace the content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelper.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import org.gradle.api.logging.Logger

/**
 * Best-effort attempt to drop a single deployment. Logs warnings on failure
 * but never throws (except for [CancellationException] which propagates for coroutine cancellation).
 *
 * HTTP 400 with a state-related message is treated as a normal race condition:
 * the deployment may have transitioned to PUBLISHING/PUBLISHED between our last
 * status check and the drop attempt. This is logged at lifecycle level, not as a warning.
 */
internal suspend fun MavenCentralApiClient.tryDropDeployment(
    creds: Credentials, deploymentId: UUID, logger: Logger
) {
    try {
        when (val result = dropDeployment(creds, deploymentId)) {
            is HttpResponseResult.Success -> {
                logger.lifecycle("Dropped deployment {}", deploymentId)
            }
            is HttpResponseResult.Error -> {
                if (result.httpStatus == HTTP_BAD_REQUEST && isStateConflictError(result.data)) {
                    logger.lifecycle(
                        "Deployment {} has progressed to a non-droppable state (race condition). " +
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

private const val HTTP_BAD_REQUEST = 400

/**
 * Detects the Maven Central Portal error returned when trying to drop a deployment
 * that has already moved to a non-droppable state (PUBLISHING, PUBLISHED).
 */
private fun isStateConflictError(responseBody: String?): Boolean =
    responseBody != null && responseBody.contains("VALIDATED or FAILED state", ignoreCase = true)
```

- [ ] **Step 2: Update DeploymentRecoveryHandler to suspend**

Replace the content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandler.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.isDroppable
import java.util.UUID
import org.gradle.api.logging.Logger

internal class DeploymentRecoveryHandler(
    private val client: MavenCentralApiClient,
    private val credentials: Credentials,
    private val logger: Logger
) {
    suspend fun recover(deploymentId: UUID, error: DeploymentError): DeploymentError {
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
        deploymentIds: List<UUID>,
        lastKnownStates: Map<UUID, DeploymentStateType>,
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
        allIds: List<UUID>,
        publishedIds: Set<UUID>,
        failedId: UUID,
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

- [ ] **Step 3: Update recovery tests for suspend**

Add `import kotlinx.coroutines.test.runTest` to both test files and wrap each `@Test` body in `= runTest { ... }`:

For `DeploymentDropHelperTest.kt` and `DeploymentRecoveryHandlerTest.kt` — the test bodies remain identical, only wrapped in `runTest`.

Also: in `DeploymentDropHelperTest.kt` and `DeploymentRecoveryHandlerTest.kt`, any mock setup using `every { client.dropDeployment(...) }` needs to change to `coEvery { client.dropDeployment(...) }` since the method is now `suspend`. Replace `every` with `coEvery` and `returns` remains the same. Import `io.mockk.coEvery`.

- [ ] **Step 4: Run recovery tests**

Run: `./gradlew test --tests "*.DeploymentDropHelperTest" --tests "*.DeploymentRecoveryHandlerTest" -q`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: make recovery layer suspend-based"
```

---

## Phase 3: Tasks — Coroutines and Kotlin Idioms

### Task 8: Adapt `PublishBundleMavenCentralTask` to coroutines and getOrThrow

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt`
- Test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleDropBehaviorTest.kt` (existing, must still pass)

- [ ] **Step 1: Rewrite PublishBundleMavenCentralTask with runBlocking and suspend internals**

Key changes to `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt`:

1. Add imports:
```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
```

2. Replace the `publishBundle()` method:
```kotlin
@TaskAction
public fun publishBundle(): Unit = runBlocking {
    doPublishBundle()
}

private suspend fun doPublishBundle() {
    validateInputs().getOrThrow { it.toGradleException() }
    val creds = credentials.get().getOrThrow { it.toGradleException() }

    val error = executePublishing(creds)
    error?.let { throw it.toGradleException() }
}
```

3. Add import for `getOrThrow`:
```kotlin
import io.github.zenhelix.gradle.plugin.client.model.getOrThrow
```

4. Make `executePublishing` and `waitForDeploymentCompletion` suspend:
```kotlin
private suspend fun executePublishing(creds: Credentials): DeploymentError? {
```
```kotlin
private suspend fun waitForDeploymentCompletion(
```

5. Replace `Thread.sleep` with `delay` in `waitForDeploymentCompletion`:
```kotlin
// Remove the try-catch block:
//     try {
//         Thread.sleep(checkDelay.toMillis())
//     } catch (e: InterruptedException) {
//         Thread.currentThread().interrupt()
//         throw e
//     }
// Replace with:
delay(checkDelay.toMillis())
```

6. Remove unused imports: `Failure`, `Success` (if no longer used directly — check), and add `getOrThrow` import.

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "*.PublishBundleDropBehaviorTest" -q`
Expected: All tests PASS (these tests use mocked clients and should work with `runBlocking`)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: adapt PublishBundleMavenCentralTask to coroutines and getOrThrow"
```

---

### Task 9: Adapt `PublishSplitBundleMavenCentralTask` to coroutines, getOrThrow, and buildList/buildSet

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt`
- Test: existing tests must pass

- [ ] **Step 1: Rewrite PublishSplitBundleMavenCentralTask**

Key changes to `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt`:

1. Add imports:
```kotlin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import io.github.zenhelix.gradle.plugin.client.model.getOrThrow
```

2. Replace `publishBundles()`:
```kotlin
@TaskAction
public fun publishBundles(): Unit = runBlocking {
    doPublishBundles()
}

private suspend fun doPublishBundles() {
    validateInputs().getOrThrow { it.toGradleException() }
    val creds = credentials.get().getOrThrow { it.toGradleException() }

    val error = executePublishing(creds)
    error?.let { throw it.toGradleException() }
}
```

3. Make all private methods `suspend`: `executePublishing`, `uploadAllBundles`, `waitForAllDeploymentsValidated`, `publishAllDeployments`.

4. In `uploadAllBundles`, replace `mutableListOf<UUID>()` with `buildList`:
```kotlin
private suspend fun uploadAllBundles(
    client: MavenCentralApiClient,
    creds: Credentials,
    bundleFiles: List<File>,
    effectiveType: PublishingType?,
    baseName: String?
): Outcome<List<UUID>, DeploymentError> {
    val deploymentIds = mutableListOf<UUID>()

    // Keep mutableListOf here because we need early return on error
    // with rollback of previously uploaded deployments.
    // buildList doesn't support early return from the builder lambda.
```

Actually, `buildList` with early return is complex here due to rollback logic. Keep `mutableListOf` for `uploadAllBundles` — the early return + rollback pattern doesn't fit `buildList`.

5. In `waitForAllDeploymentsValidated`, replace `Thread.sleep` with `delay`:
```kotlin
// Replace:
//     try {
//         Thread.sleep(checkDelay.toMillis())
//     } catch (e: InterruptedException) {
//         Thread.currentThread().interrupt()
//         throw e
//     }
// With:
delay(checkDelay.toMillis())
```

6. In `publishAllDeployments`, replace `mutableSetOf<UUID>()` with tracking through the loop. Actually, keep `mutableSetOf` — same reason as above (early return with recovery).

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "*.PublishSplitBundleMavenCentralTaskTest" --tests "*.PublishSplitBundleDropBehaviorTest" -q`
Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: adapt PublishSplitBundleMavenCentralTask to coroutines and getOrThrow"
```

---

### Task 10: Update remaining task tests and run full suite

**Files:**
- Modify: Various test files that call suspend API methods
- Test: Full test suite

- [ ] **Step 1: Update all remaining test files for suspend**

Any test files that directly call `MavenCentralApiClient` methods or `DeploymentRecoveryHandler` methods need `runTest` wrappers and `coEvery`/`coVerify` instead of `every`/`verify` for suspend mocks.

Check each test file:
- `PublishBundleDropBehaviorTest.kt` — uses mocked client → `coEvery`/`coVerify` + `runTest`
- `PublishSplitBundleDropBehaviorTest.kt` — same
- `PublishSplitBundleMavenCentralTaskTest.kt` — same

- [ ] **Step 2: Run full test suite**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: update all tests for suspend API"
```

---

## Phase 4: Plugin — Split into Configurators

### Task 11: Extract `ZipDeploymentConfigurator`

**Files:**
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/configurator/ZipDeploymentConfigurator.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt`

- [ ] **Step 1: Create ZipDeploymentConfigurator**

Create `src/main/kotlin/io/github/zenhelix/gradle/plugin/configurator/ZipDeploymentConfigurator.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.configurator

import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.utils.findMavenPublications
import io.github.zenhelix.gradle.plugin.utils.findPublishLifecycleTask
import io.github.zenhelix.gradle.plugin.utils.mapModel
import io.github.zenhelix.gradle.plugin.utils.registerChecksumTask
import io.github.zenhelix.gradle.plugin.utils.registerChecksumsAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishPublicationTask
import io.github.zenhelix.gradle.plugin.utils.registerZipAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerZipPublicationTask
import org.gradle.api.Project
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.tasks.TaskDependency
import org.gradle.kotlin.dsl.listProperty

internal object ZipDeploymentConfigurator {

    fun configure(project: Project, extension: MavenCentralUploaderExtension) {
        project.afterEvaluate {
            configureZipDeploymentTasks(this, extension)
        }
    }

    private fun configureZipDeploymentTasks(project: Project, extension: MavenCentralUploaderExtension) {
        val publications = project.findMavenPublications() ?: return

        val checksumsAllPublicationsTask = project.registerChecksumsAllPublicationsTask()

        val zipAllPublicationsTask = project.registerZipAllPublicationsTask {
            archiveFileName.set(project.provider { "${project.name}-allPublications-${project.version}.zip" })
        }

        val publishAllPublicationsTask = project.registerPublishAllPublicationsTask(extension) {
            dependsOn(zipAllPublicationsTask)
            zipFile.set(zipAllPublicationsTask.flatMap { it.archiveFile })
        }

        project.findPublishLifecycleTask().configure {
            dependsOn(publishAllPublicationsTask)
        }

        publications.configureEach {
            val publication = this as MavenPublicationInternal
            val publicationName = publication.name

            val taskDependencies = project.objects.listProperty<TaskDependency>().apply {
                publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
            }

            val checksumTask = project.registerChecksumTask(publicationName) {
                dependsOn(taskDependencies)
            }

            val publicationInfo = publication.mapModel(project, checksumTask)

            val zipTask = project.registerZipPublicationTask(publicationName) {
                dependsOn(taskDependencies)
                dependsOn(checksumTask)

                this.publications.add(publicationInfo)

                archiveFileName.set(project.provider { "${project.name}-${publicationName}-${project.version}.zip" })

                configureContentFor(publicationInfo)
            }

            project.registerPublishPublicationTask(publicationName, extension) {
                dependsOn(zipTask)
                zipFile.set(zipTask.flatMap { it.archiveFile })
            }

            checksumsAllPublicationsTask.configure {
                dependsOn(checksumTask)
            }

            zipAllPublicationsTask.configure {
                dependsOn(taskDependencies)
                dependsOn(checksumTask)

                this.publications.add(publicationInfo)

                configureContentFor(publicationInfo)
            }
        }
    }
}
```

- [ ] **Step 2: Update MavenCentralUploaderPlugin to delegate**

In `MavenCentralUploaderPlugin.kt`, replace the `configureZipDeploymentTasks` call and remove the private method. The `apply` method should now call:

```kotlin
ZipDeploymentConfigurator.configure(target, mavenCentralUploaderExtension)
```

Remove `import org.gradle.api.tasks.TaskDependency` and `import org.gradle.kotlin.dsl.listProperty` from MavenCentralUploaderPlugin if no longer needed.

Remove the `private fun configureZipDeploymentTasks(...)` method entirely from MavenCentralUploaderPlugin.

Add import: `import io.github.zenhelix.gradle.plugin.configurator.ZipDeploymentConfigurator`

- [ ] **Step 3: Run functional tests**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: extract ZipDeploymentConfigurator from plugin"
```

---

### Task 12: Extract `RootProjectConfigurator` and `SubprojectConfigurator`

**Files:**
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/configurator/RootProjectConfigurator.kt`
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/configurator/SubprojectConfigurator.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt`

- [ ] **Step 1: Create RootProjectConfigurator**

Create `src/main/kotlin/io/github/zenhelix/gradle/plugin/configurator/RootProjectConfigurator.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.configurator

import io.github.zenhelix.gradle.plugin.MavenCentralUploaderPlugin.Companion.MAVEN_CENTRAL_PORTAL_NAME
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import io.github.zenhelix.gradle.plugin.utils.findMavenPublications
import io.github.zenhelix.gradle.plugin.utils.findPublishLifecycleTask
import io.github.zenhelix.gradle.plugin.utils.mapModel
import io.github.zenhelix.gradle.plugin.utils.registerPublishSplitAllModulesTask
import io.github.zenhelix.gradle.plugin.utils.registerSplitZipAllModulesTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named

internal object RootProjectConfigurator {

    private val PUBLISH_ALL_PUBLICATIONS_TASK_NAME =
        "publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository"

    fun configure(rootProject: Project, extension: MavenCentralUploaderExtension) {
        rootProject.gradle.projectsEvaluated {
            configureRootProjectLifecycle(rootProject, extension)
        }
    }

    private fun configureRootProjectLifecycle(rootProject: Project, extension: MavenCentralUploaderExtension) {
        val subprojectsWithPublications = rootProject.subprojects.filter {
            it.findMavenPublications()?.isNotEmpty() == true
        }

        if (subprojectsWithPublications.isNotEmpty()) {
            createAggregationTasks(rootProject, extension)

            rootProject.findPublishLifecycleTask().configure {
                rootProject.tasks.findByName("publishAllModulesToMavenCentralPortalRepository")?.also { dependsOn(it) }
            }

            val projectsToUnwire = subprojectsWithPublications + rootProject
            projectsToUnwire.forEach { project ->
                project.findPublishLifecycleTask().configure {
                    setDependsOn(dependsOn.filterNot { dep ->
                        dep.taskNameOrNull() == PUBLISH_ALL_PUBLICATIONS_TASK_NAME
                    })
                }
            }
        }
    }

    private fun createAggregationTasks(rootProject: Project, extension: MavenCentralUploaderExtension) {
        val (allPublicationsInfo, allChecksumsAndBuildTasks) = collectAggregationData(rootProject)

        if (allPublicationsInfo.isNotEmpty()) {
            val splitZipTask = rootProject.registerSplitZipAllModulesTask {
                dependsOn(allChecksumsAndBuildTasks)

                this.publications.addAll(allPublicationsInfo)

                maxBundleSize.set(extension.uploader.maxBundleSize)
                archiveBaseName.set(rootProject.provider {
                    "${rootProject.name}-allModules-${rootProject.version}"
                })
                outputDirectory.set(
                    rootProject.layout.buildDirectory.dir("maven-central-split-bundles")
                )
            }

            rootProject.registerPublishSplitAllModulesTask(extension) {
                dependsOn(splitZipTask)
                bundlesDirectory.set(splitZipTask.flatMap { it.outputDirectory })
            }
        }
    }

    private fun collectAggregationData(
        rootProject: Project
    ): Pair<List<PublicationInfo>, List<Any>> {
        val rootPublications = rootProject.findMavenPublications()
        val subprojectPublications = rootProject.subprojects.associateWith { it.findMavenPublications() }

        val allPublicationsInfo = buildList {
            if (!rootPublications.isNullOrEmpty()) {
                rootPublications.forEach { publication ->
                    val checksumTaskName = "checksum${publication.name.capitalized()}Publication"
                    add(publication.mapModel(
                        rootProject,
                        rootProject.tasks.named<CreateChecksumTask>(checksumTaskName)
                    ))
                }
            }

            subprojectPublications.forEach { (subproject, publications) ->
                publications?.forEach { publication ->
                    val checksumTaskName = "checksum${publication.name.capitalized()}Publication"
                    val checksumTask = subproject.tasks.findByName(checksumTaskName)
                    if (checksumTask is CreateChecksumTask) {
                        add(publication.mapModel(
                            subproject,
                            subproject.tasks.named(checksumTaskName, CreateChecksumTask::class.java)
                        ))
                    }
                }
            }
        }

        val allChecksumsAndBuildTasks = buildList<Any> {
            if (!rootPublications.isNullOrEmpty()) {
                rootPublications.forEach { publication ->
                    val deps = rootProject.objects.listProperty<TaskDependency>().apply {
                        publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
                    }
                    add(deps)

                    rootProject.tasks.findByName("checksum${publication.name.capitalized()}Publication")?.let {
                        add(it)
                    }
                }
            }

            subprojectPublications.forEach { (subproject, publications) ->
                subproject.tasks.findByName("checksumAllPublications")?.let { add(it) }

                publications?.forEach { publication ->
                    val deps = subproject.objects.listProperty<TaskDependency>().apply {
                        publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
                    }
                    add(deps)
                }
            }
        }

        return allPublicationsInfo to allChecksumsAndBuildTasks
    }
}

/**
 * Extracts a task name from various Gradle dependency types.
 */
private fun Any.taskNameOrNull(): String? = when (this) {
    is TaskProvider<*> -> name
    is Task -> name
    is String -> this
    else -> null
}
```

- [ ] **Step 2: Create SubprojectConfigurator**

Create `src/main/kotlin/io/github/zenhelix/gradle/plugin/configurator/SubprojectConfigurator.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.configurator

import io.github.zenhelix.gradle.plugin.utils.hasMavenCentralPortalExtension
import org.gradle.api.Project

internal object SubprojectConfigurator {

    private const val WARN_REGISTERED_FLAG = "io.github.zenhelix.maven-central-publish.warnRegistered"

    fun configure(target: Project) {
        val rootProject = target.rootProject
        if (!rootProject.extensions.extraProperties.has(WARN_REGISTERED_FLAG)) {
            rootProject.extensions.extraProperties[WARN_REGISTERED_FLAG] = true
            target.gradle.projectsEvaluated {
                emitIndependentPublishingWarningIfNeeded(rootProject)
            }
        }
    }

    private fun emitIndependentPublishingWarningIfNeeded(rootProject: Project) {
        if (rootProject.hasMavenCentralPortalExtension()) {
            return
        }

        val subprojectsWithPlugin = rootProject.subprojects.filter { it.hasMavenCentralPortalExtension() }

        if (subprojectsWithPlugin.size > 1) {
            rootProject.logger.warn(
                "Multiple projects publish to Maven Central independently. For atomic multi-module publishing, apply the plugin to the root project."
            )
        }
    }
}
```

- [ ] **Step 3: Simplify MavenCentralUploaderPlugin**

Replace the content of `src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.configurator.RootProjectConfigurator
import io.github.zenhelix.gradle.plugin.configurator.SubprojectConfigurator
import io.github.zenhelix.gradle.plugin.configurator.ZipDeploymentConfigurator
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension.Companion.MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.plugins.signing.SigningPlugin

/**
 * Gradle plugin for publishing artifacts to Maven Central via the Publisher API.
 *
 * Supports two publishing modes:
 * - **Independent (default):** Each project that applies the plugin publishes its own bundle.
 *   The `publish` lifecycle task depends on `publishAllPublicationsToMavenCentralPortalRepository`.
 * - **Atomic aggregation:** When applied to the root project and subprojects have publications,
 *   all modules are aggregated into a single deployment bundle. Subproject `publish` tasks
 *   do not trigger independent Maven Central uploads.
 *
 * Mode detection:
 * - Plugin on root + subprojects with publications -> atomic aggregation
 * - Plugin on root only (no subproject publications) -> independent (single-module)
 * - Plugin on subprojects only -> independent per subproject (with warning)
 */
public class MavenCentralUploaderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        val extension = target.extensions.create<MavenCentralUploaderExtension>(
            MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
        )

        ZipDeploymentConfigurator.configure(target, extension)

        if (target == target.rootProject) {
            RootProjectConfigurator.configure(target, extension)
        } else {
            SubprojectConfigurator.configure(target)
        }
    }

    public companion object {
        public const val MAVEN_CENTRAL_PORTAL_NAME: String = "mavenCentralPortal"
        public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID: String = "io.github.zenhelix.maven-central-publish"
    }
}
```

- [ ] **Step 4: Run full test suite**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: split MavenCentralUploaderPlugin into configurators"
```

---

## Phase 5: Naming, Packages and Final Cleanup

### Task 13: Renames

**Files:**
- Rename: `MavenCentralApiClientImpl.kt` -> `DefaultMavenCentralApiClient.kt`
- Rename: `MavenCentralPublishExceptions.kt` -> `MavenCentralExceptions.kt`
- Rename: `TaskExtension.kt` -> `TaskRegistration.kt`
- Rename: `ProjectExtensions.kt` -> `ProjectUtils.kt`

- [ ] **Step 1: Rename MavenCentralApiClientImpl**

Rename the class in `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt`:
- Change `public class MavenCentralApiClientImpl` to `public class DefaultMavenCentralApiClient`
- Change `Logging.getLogger(MavenCentralApiClientImpl::class.java)` to `Logging.getLogger(DefaultMavenCentralApiClient::class.java)`
- Rename the file to `DefaultMavenCentralApiClient.kt`

Update references in:
- `NoOpMavenCentralApiClient.kt`: `MavenCentralApiClientImpl(url)` -> `DefaultMavenCentralApiClient(url)`, KDoc references
- `MavenCentralApiClientImplTest.kt`: class name references, rename test file to `DefaultMavenCentralApiClientTest.kt`

- [ ] **Step 2: Rename MavenCentralPublishExceptions.kt**

Rename file `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/MavenCentralPublishExceptions.kt` to `MavenCentralExceptions.kt`. No class renames needed — only the file name changes.

- [ ] **Step 3: Rename TaskExtension.kt and ProjectExtensions.kt**

Rename `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/TaskExtension.kt` to `TaskRegistration.kt`.
Rename `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/ProjectExtensions.kt` to `ProjectUtils.kt`.

No internal code changes needed — only file names.

- [ ] **Step 4: Run full test suite**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: rename Impl to Default, clarify file names"
```

---

### Task 14: Package moves

**Files:**
- Move: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunker.kt` -> `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/BundleChunker.kt`
- Move: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/SizeExtensions.kt` -> `src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/SizeExtensions.kt`
- Move test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunkerTest.kt` -> `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/BundleChunkerTest.kt`
- Move test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/SizeExtensionsTest.kt` -> `src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/SizeExtensionsTest.kt`

- [ ] **Step 1: Move BundleChunker to client package**

Move `BundleChunker.kt` to `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/BundleChunker.kt`.
Change the package declaration from `io.github.zenhelix.gradle.plugin.utils` to `io.github.zenhelix.gradle.plugin.client`.

Move `BundleChunkerTest.kt` to `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/BundleChunkerTest.kt`.
Change the package declaration from `io.github.zenhelix.gradle.plugin.utils` to `io.github.zenhelix.gradle.plugin.client`.

Update imports in:
- `SplitZipDeploymentTask.kt`: change `io.github.zenhelix.gradle.plugin.utils.BundleChunker` to `io.github.zenhelix.gradle.plugin.client.BundleChunker` (also `Chunk`, `ModuleSize`)

- [ ] **Step 2: Move SizeExtensions to extension package**

Move `SizeExtensions.kt` to `src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/SizeExtensions.kt`.
Change the package declaration from `io.github.zenhelix.gradle.plugin.utils` to `io.github.zenhelix.gradle.plugin.extension`.

Move `SizeExtensionsTest.kt` to `src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/SizeExtensionsTest.kt`.
Change the package declaration.

Update imports in:
- `ChunkError.kt`: change `io.github.zenhelix.gradle.plugin.utils.toDisplayMB` to `io.github.zenhelix.gradle.plugin.extension.toDisplayMB`
- `SplitZipDeploymentTask.kt`: change `io.github.zenhelix.gradle.plugin.utils.toDisplayKB` and `toDisplayMB` to `io.github.zenhelix.gradle.plugin.extension.toDisplayKB` and `toDisplayMB`

- [ ] **Step 3: Run full test suite**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: move BundleChunker to client, SizeExtensions to extension package"
```

---

### Task 15: Minor Kotlin idioms and dead code removal

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentStatus.kt`

- [ ] **Step 1: Replace `values()` with `entries` in DeploymentStateType**

In `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentStatus.kt`, change:

```kotlin
public fun ofOrNull(value: String): DeploymentStateType? = values().firstOrNull { it.id.equals(value, true) }
```

to:

```kotlin
public fun ofOrNull(value: String): DeploymentStateType? = entries.firstOrNull { it.id.equals(value, ignoreCase = true) }
```

- [ ] **Step 2: Run full test suite**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: use entries instead of values(), final Kotlin idiom cleanup"
```

---

### Task 16: Final verification

- [ ] **Step 1: Run full check with functional tests**

Run: `./gradlew check -q`
Expected: BUILD SUCCESSFUL — all unit tests, functional tests, and code coverage pass.

- [ ] **Step 2: Verify no compilation warnings**

Run: `./gradlew compileKotlin 2>&1 | grep -i "warning"` 
Expected: No unexpected warnings (some Gradle API deprecation warnings may exist and are acceptable).

- [ ] **Step 3: Verify final package structure matches spec**

Run: `find src/main/kotlin -name "*.kt" | sort`
Expected output should match the final package structure from the spec.
