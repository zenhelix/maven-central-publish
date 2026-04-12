# Kotlin Idioms Refactoring

Comprehensive pass to make the codebase more idiomatic Kotlin: production code, type-safety, tests, and DSL improvements.

## 1. Production Code Idioms

### 1.1 Credential state tracking — sealed interface

**File:** `MavenCentralUploaderCredentialExtension`

Replace two `var Boolean` flags (`bearerConfigured`, `usernamePasswordConfigured`) with a sealed interface:

```kotlin
private sealed interface CredentialMode {
    data object None : CredentialMode
    data object Bearer : CredentialMode
    data object UsernamePassword : CredentialMode
    data object Both : CredentialMode
}
private var mode: CredentialMode = CredentialMode.None
```

`bearer {}` transitions `None -> Bearer`, `UsernamePassword -> Both`.
`usernamePassword {}` transitions `None -> UsernamePassword`, `Bearer -> Both`.
`isBearerConfigured` / `isUsernamePasswordConfigured` computed from `mode`.

### 1.2 Verbose null-handling in `deploymentStatus`

**File:** `DefaultMavenCentralApiClient.kt` (lines 91-101)

Replace `if (status != null) { ...; status } else { null }` with:

```kotlin
parseSuccess { body ->
    parseDeploymentStatus(body)?.also { status ->
        logger.debug(
            "Deployment status retrieved: deploymentId={}, state={}",
            status.deploymentId, status.deploymentState
        )
    }
}
```

### 1.3 Drop state conflict — HTTP 400 instead of string matching

**File:** `DeploymentDropHelper.kt`

Remove `isStateConflictError(responseBody: String?)` function. Treat any HTTP 400 on drop as state conflict:

```kotlin
is HttpResponseResult.Error -> {
    if (result.httpStatus == HTTP_BAD_REQUEST) {
        logger.lifecycle(
            "Deployment {} likely transitioned to non-droppable state. " +
                "Check Maven Central Portal for current status.", deploymentId
        )
    } else {
        logger.warn("Failed to drop deployment {}: HTTP {}, Response: {}", deploymentId, result.httpStatus, result.data)
    }
}
```

### 1.4 `toGradleException` — deduplicate cause extraction

**File:** `DeploymentError.kt` (lines 41-46)

Replace three identical branches with grouped `when`:

```kotlin
public fun DeploymentError.toGradleException(): MavenCentralDeploymentException {
    val cause = when (this) {
        is DeploymentError.UploadUnexpected -> cause
        is DeploymentError.StatusCheckUnexpected -> cause
        is DeploymentError.PublishUnexpected -> cause
        else -> null
    }
    return MavenCentralDeploymentException(error = this, message = message, cause = cause)
}
```

### 1.5 Remove dead `else` branch in RetryHandler

**File:** `RetryHandler.kt` (line 56)

`else -> return result` is unreachable — `Outcome` is sealed with only `Success` and `Failure`. Remove it.

## 2. Type-Safety — Value Classes

### 2.1 `DeploymentId` value class

```kotlin
@JvmInline
public value class DeploymentId(val value: UUID) {
    override fun toString(): String = value.toString()
}
```

Mechanical replacement across: `MavenCentralApiClient`, `DefaultMavenCentralApiClient`, `DeploymentStatus`, `DeploymentError`, `DeploymentRecoveryHandler`, `DeploymentDropHelper`, both publish tasks.

### 2.2 `HttpStatus` value class

```kotlin
@JvmInline
public value class HttpStatus(val code: Int) {
    companion object {
        val OK = HttpStatus(200)
        val CREATED = HttpStatus(201)
        val NO_CONTENT = HttpStatus(204)
        val BAD_REQUEST = HttpStatus(400)
        val TOO_MANY_REQUESTS = HttpStatus(429)
    }
}
```

Replaces `private const val HTTP_*` in `DefaultMavenCentralApiClient.companion` and raw `Int` for `httpStatus` in `HttpResponseResult`, `foldHttp` signatures, `DeploymentError.UploadFailed`, `DeploymentError.StatusCheckFailed`, `DeploymentError.PublishFailed`.

### 2.3 Not adding value classes for tokens/credentials

`Credentials` sealed class already provides sufficient type safety. Wrapping `token: String` would be overengineering.

## 3. Test Improvements

### 3.1 try/catch to `assertThatThrownBy`

**File:** `DefaultMavenCentralApiClientTest.kt` (lines 104-112)

Replace manual try/catch + `fail()` with AssertJ (already imported in the file):

```kotlin
assertThatThrownBy {
    runBlocking { client.uploadDeploymentBundle(...) }
}.isInstanceOf(IllegalArgumentException::class.java)
 .hasMessageContaining("Bundle file does not exist")
```

### 3.2 Reified assertion helpers for sealed types

New test utility file with inline helpers:

```kotlin
inline fun <reified T> assertSuccess(outcome: Outcome<*, *>): T {
    assertThat(outcome).isInstanceOf(Success::class.java)
    val value = (outcome as Success).value
    assertThat(value).isInstanceOf(T::class.java)
    return value as T
}

inline fun <reified T> assertHttpSuccess(result: HttpResponseResult<*, *>): T {
    assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
    return (result as HttpResponseResult.Success).data as T
}

inline fun <reified T> assertFailure(outcome: Outcome<*, *>): T {
    assertThat(outcome).isInstanceOf(Failure::class.java)
    val error = (outcome as Failure).error
    assertThat(error).isInstanceOf(T::class.java)
    return error as T
}
```

Apply across `DefaultMavenCentralApiClientTest` and `MavenCentralUploaderCredentialExtensionTest`.

### 3.3 Mock builder for HTTP responses

Extension function in test utilities:

```kotlin
internal fun mockHttpResponse(
    status: Int,
    body: String,
    headers: Map<String, List<String>> = emptyMap()
): HttpResponse<String> = mockk {
    every { statusCode() } returns status
    every { body() } returns body
    every { headers() } returns mockk { every { map() } returns headers }
}
```

Replaces ~10 duplicated mock blocks in `DefaultMavenCentralApiClientTest`.

### 3.4 Mutable counters — keep as-is

`var attempts` / `var callCount` are idiomatic for single-threaded `runTest` side-effect counting.

## 4. DSL and API Improvements

### 4.1 `ApiCallBuilder.handleResponse` — extract response handling

**File:** `DefaultMavenCentralApiClient.kt` (lines 230-256)

Move the 25-line `if-else` with duplicated `HttpResponseResult.Error` construction into `ApiCallBuilder.handleResponse()`:

```kotlin
fun handleResponse(response: HttpResponse<String>, body: String): HttpResponseResult<T, String> {
    val headers = response.headers().map()
    val status = response.statusCode()

    if (status != expectedSuccessStatus) {
        errorLogMessage?.let { logger.warn(it(status, body)) }
        return HttpResponseResult.Error(data = body, httpStatus = status, httpHeaders = headers)
    }

    val parsed = successParser(body)
        ?: run {
            errorLogMessage?.let { logger.warn(it(status, body)) }
            return HttpResponseResult.Error(data = body, httpStatus = status, httpHeaders = headers)
        }

    successLogMessage?.let { logger.debug(it(parsed)) }
    return HttpResponseResult.Success(data = parsed, httpStatus = status, httpHeaders = headers)
}
```

### 4.2 `DEFAULT_MAX_BUNDLE_SIZE` — use SizeExtensions

**File:** `MavenCentralUploaderExtension.kt` (line 83)

Replace `const val` with `val` using existing extension:

```kotlin
public val DEFAULT_MAX_BUNDLE_SIZE: Long = 256.megabytes
```

### 4.3 `BundleChunker` validation — functional style

**File:** `BundleChunker.kt` (lines 17-21)

Replace `forEach` + early return with:

```kotlin
modules.firstOrNull { it.sizeBytes > maxChunkSize }
    ?.let { return Failure(ChunkError.ModuleTooLarge(it.name, it.sizeBytes, maxChunkSize)) }
```

## 5. Explicit Exclusions

The following are intentionally left unchanged:

- **`MutableChunk` in `BundleChunker`** — internal algorithm, mutability justified for first-fit-decreasing
- **`mutableListOf`/`mutableMapOf` in publish tasks** — accumulation in imperative loops, `fold` would hurt readability
- **`var attempt` in `RetryHandler`** — while-loop with mutable counter is idiomatic for retry logic
- **`runBlocking` in `@TaskAction`** — required since Gradle tasks are synchronous
- **`@Suppress("USELESS_IS_CHECK")` in `close()`** — required for Java 17-20/21+ compatibility
- **`DeploymentPollStep` sealed class** — already excellent Kotlin pattern for state machine
- **`buildQueryString`** — already idiomatic with `mapNotNull`/`joinToString`/`takeIf`/`let`

## Files Affected

### New files
- `src/test/kotlin/.../utils/TestAssertions.kt` — reified assertion helpers + mock builder

### Modified files (production)
- `MavenCentralUploaderExtension.kt` — credential mode sealed interface, SizeExtensions for bundle size
- `DefaultMavenCentralApiClient.kt` — `?.also` for null handling, `handleResponse` extraction, `HttpStatus` value class
- `DeploymentDropHelper.kt` — remove string matching, HTTP 400 = state conflict
- `DeploymentError.kt` — deduplicate `toGradleException`, `HttpStatus` value class in error types
- `RetryHandler.kt` — remove dead `else` branch
- `BundleChunker.kt` — functional validation style
- `Outcome.kt` — no changes
- `HttpResponseResult.kt` — `HttpStatus` value class for `httpStatus` fields
- `DeploymentStatus.kt` — `DeploymentId` value class
- `MavenCentralApiClient.kt` — `DeploymentId` in signatures
- `DeploymentRecoveryHandler.kt` — `DeploymentId`
- `PublishBundleMavenCentralTask.kt` — `DeploymentId`
- `PublishSplitBundleMavenCentralTask.kt` — `DeploymentId`

### Modified files (tests)
- `DefaultMavenCentralApiClientTest.kt` — `assertThatThrownBy`, assertion helpers, mock builder
- `MavenCentralUploaderCredentialExtensionTest.kt` — assertion helpers

### New model files
- `DeploymentId.kt` — value class
- `HttpStatus.kt` — value class
