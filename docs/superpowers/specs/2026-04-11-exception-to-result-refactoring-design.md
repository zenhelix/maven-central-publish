# Exception-to-Result Refactoring Design

**Date:** 2026-04-11
**Scope:** Full project refactoring — replace exception-based control flow with Kotlin Result patterns

## Goals

- Replace all exception-based control flow with typed Result patterns
- Separate recovery/rollback logic into a dedicated component
- Keep `GradleException` only at the `@TaskAction` boundary (Gradle API contract)
- Preserve `InterruptedException` rethrow (JVM contract)
- Preserve `require()` for preconditions (idiomatic Kotlin)

## Non-Goals

- Changing the HTTP client API (`MavenCentralApiClient` interface methods)
- Adding external dependencies (Arrow, etc.)
- Changing plugin DSL or user-facing behavior

---

## Architecture

### Layer Model

```
@TaskAction (GradleException boundary)
    |
    v
executePublishing() -> DeploymentError? / ValidationError?
    |
    v
DeploymentRecoveryHandler (rollback decisions)
    |
    v
Internal logic (client, retry, validation) -> ResultLike<T, E>
```

All internal logic returns `ResultLike`. The only place that throws `GradleException` is the `@TaskAction` method.

---

## 1. Base Result Utilities

### `ResultLike<T, E>` — common interface

All Result types in the project implement this interface:

```kotlin
sealed interface ResultLike<out T, out E> {
    fun <R> fold(onSuccess: (T) -> R, onFailure: (E) -> R): R
    fun getOrNull(): T?
    fun errorOrNull(): E?
    fun <R> map(transform: (T) -> R): ResultLike<R, E>
    fun <R> flatMap(transform: (T) -> ResultLike<R, E>): ResultLike<R, E>
    fun getOrElse(default: (E) -> @UnsafeVariance T): T
}
```

### Concrete implementations

```kotlin
data class Success<out T>(val value: T) : ResultLike<T, Nothing>
data class Failure<out E>(val error: E) : ResultLike<Nothing, E>
```

These generic implementations are used by domain Result types and `RetryHandler`.

### `ResponseResult` / `HttpResponseResult` updates

- Remove `result()` method (unsafe unwrap that throws)
- `ResponseResult` implements `ResultLike`:
  - `Success` maps to `ResultLike` success
  - `Error` maps to `ResultLike` failure
  - `UnexpectedError` maps to `ResultLike` failure
- `HttpResponseResult` adds `foldHttp()` for three-way matching (Success / Error / UnexpectedError) — preserves the existing three-variant semantics for HTTP-specific code
- `copySuccess()`, `copyError()`, `copy()` remain unchanged

**UnexpectedError handling in ResultLike:** When `HttpResponseResult` is used as `ResultLike`, `UnexpectedError` is treated as failure. The `errorOrNull()` returns `null` for `UnexpectedError` (since it carries `Exception`, not `E`). A separate `causeOrNull(): Exception?` is available for accessing the underlying exception.

---

## 2. Domain Sealed Error Types

### `ValidationError` — input validation and credentials

```kotlin
sealed class ValidationError(val message: String) {
    // Input validation
    data class MissingProperty(val property: String)
        : ValidationError("Property '$property' is required but not set")
    data class InvalidFile(val path: String, val reason: String)
        : ValidationError("$reason: $path")
    data class InvalidValue(val property: String, val detail: String)
        : ValidationError("$property: $detail")

    // Credential validation
    data class AmbiguousCredentials(val detail: String) : ValidationError(detail)
    data class MissingCredential(val detail: String) : ValidationError(detail)
    data object NoCredentials : ValidationError(
        "No credentials configured. Use: credentials { bearer { token.set(\"...\") } } " +
            "or credentials { usernamePassword { username.set(\"...\"); password.set(\"...\") } }"
    )
}
```

**Used in:** `validateInputs()` in both publish tasks, `mapCredentials()` in Utils.kt.

### `DeploymentError` — publishing lifecycle

```kotlin
sealed class DeploymentError(val message: String) {
    // Upload
    data class UploadFailed(val httpStatus: Int, val response: String?)
        : DeploymentError("Failed to upload bundle: HTTP $httpStatus")
    data class UploadUnexpected(val cause: Exception)
        : DeploymentError("Unexpected error during bundle upload")

    // Status polling
    data class DeploymentFailed(val state: DeploymentStateType, val errors: Map<String, Any?>?)
        : DeploymentError("Deployment failed with status: $state")
    data class StatusCheckFailed(val httpStatus: Int, val response: String?)
        : DeploymentError("Failed to check deployment status: HTTP $httpStatus")
    data class StatusCheckUnexpected(val cause: Exception)
        : DeploymentError("Unexpected error while checking deployment status")
    data class Timeout(val state: DeploymentStateType, val maxChecks: Int)
        : DeploymentError("Deployment did not complete after $maxChecks status checks")

    // Publish (split bundles)
    data class PublishFailed(val deploymentId: UUID, val httpStatus: Int)
        : DeploymentError("Failed to publish deployment $deploymentId")
    data class PublishUnexpected(val deploymentId: UUID, val cause: Exception)
        : DeploymentError("Unexpected error publishing deployment $deploymentId")

    // Recovery context
    val isDroppable: Boolean get() = when (this) {
        is DeploymentFailed -> state.isDroppable
        is Timeout -> state.isDroppable
        is StatusCheckFailed, is StatusCheckUnexpected -> true  // state unknown, try drop
        else -> false
    }
}
```

**Used in:** `PublishBundleMavenCentralTask`, `PublishSplitBundleMavenCentralTask`, `DeploymentRecoveryHandler`.

### `ChunkError` — BundleChunker

```kotlin
sealed class ChunkError(val message: String) {
    data class ModuleTooLarge(val moduleName: String, val moduleSize: Long, val maxSize: Long)
        : ChunkError(
            "Module '$moduleName' artifacts size ($moduleSize bytes / ${moduleSize.toDisplayMB()} MB) " +
                "exceeds maxBundleSize ($maxSize bytes / ${maxSize.toDisplayMB()} MB). " +
                "Reduce artifact size or increase maxBundleSize."
        )
}
```

**Used in:** `BundleChunker.chunk()`.

### Removed types

| Old (exception) | New (sealed error) |
|---|---|
| `DeploymentFailedException` | `DeploymentError.DeploymentFailed` / `DeploymentError.Timeout` |
| `DeploymentsAlreadyCleanedUpException` | `DeploymentError.PublishFailed` / `DeploymentError.PublishUnexpected` |
| `RetriableHttpException` | Retry signaled via `Failure(exception)` return |
| `BundleSizeExceededException` | `ChunkError.ModuleTooLarge` |

### `toGradleException()` — single conversion point

Extension functions on each sealed error type for conversion at the `@TaskAction` boundary:

```kotlin
fun ValidationError.toGradleException(): GradleException = GradleException(message)
fun DeploymentError.toGradleException(): GradleException = when (this) {
    is DeploymentError.UploadUnexpected -> GradleException(message, cause)
    is DeploymentError.StatusCheckUnexpected -> GradleException(message, cause)
    is DeploymentError.PublishUnexpected -> GradleException(message, cause)
    else -> GradleException(message)
}
fun ChunkError.toGradleException(): GradleException = GradleException(message)
```

### `ValidationError.toDeploymentError()` — bridging validation into deployment flow

When `validateInputs()` fails inside `executePublishing()`, the error needs to be returned as `DeploymentError` to match the return type:

```kotlin
fun ValidationError.toDeploymentError(): DeploymentError =
    DeploymentError.UploadFailed(httpStatus = 0, response = message)
```

Alternatively, `executePublishing()` can return a union type or the `@TaskAction` can handle both error types separately:

```kotlin
@TaskAction
fun publishBundle() {
    val validationError = validateInputs()
    if (validationError != null) throw validationError.toGradleException()

    val deploymentError = executePublishing()
    if (deploymentError != null) throw deploymentError.toGradleException()
}
```

**Recommended:** the second approach — validate before entering the deployment flow. This avoids artificial bridging between unrelated error types. `executePublishing()` assumes inputs are already validated and returns `DeploymentError?` only.

---

## 3. DeploymentRecoveryHandler

Separate component responsible for rollback/cleanup decisions. Currently this logic is embedded in try-catch blocks of publish tasks.

```kotlin
class DeploymentRecoveryHandler(
    private val client: MavenCentralApiClient,
    private val credentials: Credentials,
    private val logger: Logger
) {
    /**
     * Single-bundle recovery.
     * Attempts to drop the deployment if the error state allows it.
     */
    fun recover(deploymentId: UUID, error: DeploymentError): DeploymentError {
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

    /**
     * Multi-bundle recovery.
     * Partitions deployments into droppable/non-droppable based on last known states.
     */
    fun recoverAll(
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

    /**
     * Publish failure recovery (split bundles).
     * Some deployments may already be published (API limitation — cannot roll back).
     * Drops remaining unpublished deployments.
     */
    fun recoverPublishFailure(
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

**What changes in tasks:** Recovery logic is fully extracted from publish tasks. Tasks call `recoveryHandler.recover(...)` in a `fold(onFailure = ...)` block instead of try-catch.

---

## 4. RetryHandler — Result-Based

### New contract

```kotlin
class RetryHandler(
    private val maxRetries: Int,
    private val baseDelay: Duration,
    private val logger: Logger
) {
    fun <T> executeWithRetry(
        operation: (attempt: Int) -> ResultLike<T, Exception>,
        shouldRetry: (Exception) -> Boolean = { true },
        onRetry: ((attempt: Int, exception: Exception) -> Unit)? = null
    ): ResultLike<T, Exception>
}
```

- `operation` returns `ResultLike<T, Exception>` instead of `T`
- On exhausted retries: returns `Failure(lastException)` instead of throwing
- `InterruptedException` is still rethrown (JVM contract — cannot wrap in Result)
- `require()` in constructor remains (precondition, idiomatic Kotlin)

### Impact on `MavenCentralApiClientImpl`

`RetriableHttpException` is removed. Retry signaling is done via return value:

```kotlin
private fun <T : Any> executeRequestWithRetry(
    request: HttpRequest,
    operationName: String,
    responseHandler: (...) -> HttpResponseResult<T, String>
): HttpResponseResult<T, String> {
    val result = retryHandler.executeWithRetry(
        operation = { attempt ->
            val response = httpClient.send(request, BodyHandlers.ofString(UTF_8))
            val httpResult = responseHandler(response, response.body())

            if (httpResult is HttpResponseResult.Error && isRetriableStatus(response.statusCode())) {
                Failure(Exception("Retriable HTTP ${response.statusCode()}"))
            } else {
                Success(httpResult)
            }
        },
        shouldRetry = { isRetriableException(it) }
    )

    return result.fold(
        onSuccess = { it },
        onFailure = { HttpResponseResult.UnexpectedError(cause = it) }
    )
}
```

`isRetriableException()` no longer needs `RetriableHttpException` check — network exceptions only.

---

## 5. Publish Tasks — Linear Flow

### `PublishBundleMavenCentralTask`

```kotlin
@TaskAction
fun publishBundle() {
    validateInputs()?.let { throw it.toGradleException() }

    val creds = credentials.get().fold(
        onSuccess = { it },
        onFailure = { throw it.toGradleException() }
    )

    val error = executePublishing(creds)
    error?.let { throw it.toGradleException() }
}

private fun executePublishing(creds: Credentials): DeploymentError? {
    // ... setup ...
    val recoveryHandler = DeploymentRecoveryHandler(client, creds, logger)

    return client.use { apiClient ->
        val uploadResult = apiClient.uploadDeploymentBundle(creds, bundleFile, type, name)

        uploadResult.fold(
            onSuccess = { deploymentId ->
                waitForDeploymentCompletion(apiClient, creds, deploymentId, type, maxChecks, checkDelay)
                    .fold(
                        onSuccess = { null },
                        onFailure = { error -> recoveryHandler.recover(deploymentId, error) }
                    )
            },
            onFailure = { error -> error }
        )
    }
}
```

`validateInputs()` returns `ValidationError?` (null = valid) — called before `executePublishing()`.
`waitForDeploymentCompletion()` returns `ResultLike<Unit, DeploymentError>`.

**HttpResponseResult → DeploymentError mapping:** Since `HttpResponseResult` is three-variant, the publish task uses `foldHttp()` to map all three cases:
```kotlin
uploadResult.foldHttp(
    onSuccess = { deploymentId -> ... },
    onError = { data, httpStatus, _ -> DeploymentError.UploadFailed(httpStatus, data) },
    onUnexpected = { cause -> DeploymentError.UploadUnexpected(cause) }
)
```
This explicit three-way fold is used wherever `HttpResponseResult` is converted to a domain error.

### `PublishSplitBundleMavenCentralTask`

Same pattern:
- `executePublishing()` returns `DeploymentError?`
- `uploadAllBundles()` returns `ResultLike<List<UUID>, DeploymentError>`
- `waitForAllDeploymentsValidated()` returns `ResultLike<Unit, DeploymentError>`
- `publishAllDeployments()` returns `ResultLike<Unit, DeploymentError>`
- Recovery via `DeploymentRecoveryHandler.recoverAll()` and `recoverPublishFailure()`

### `mapCredentials()` in Utils.kt

Two options for integrating with Gradle's `Provider` API:

**Option A — Result inside Provider:**
```kotlin
fun Project.mapCredentials(extension: MavenCentralUploaderExtension): Provider<ResultLike<Credentials, ValidationError>>
```
The task property becomes `Property<ResultLike<Credentials, ValidationError>>`, and the `@TaskAction` unwraps:
```kotlin
val creds = credentials.get().fold(
    onSuccess = { it },
    onFailure = { throw it.toGradleException() }
)
```

**Option B — Keep Provider<Credentials>, validate separately:**
Keep `mapCredentials()` returning `Provider<Credentials>` that throws on resolution (current behavior). Add a separate `validateCredentials()` function returning `ValidationError?` that the task calls before `credentials.get()`.

**Recommended:** Option A — keeps the Result-based approach consistent. The Provider just wraps the Result; unwrapping happens at the `@TaskAction` boundary alongside other error handling.

### `BundleChunker.chunk()`

```kotlin
fun chunk(modules: List<ModuleSize>, maxChunkSize: Long): ResultLike<List<Chunk>, ChunkError>
```

Returns `Failure(ChunkError.ModuleTooLarge(...))` instead of throwing `BundleSizeExceededException`.

---

## 6. What Stays Exception-Based

| Pattern | Reason |
|---|---|
| `require()` in `RetryHandler` constructor | Precondition — idiomatic Kotlin |
| `require()` in `uploadDeploymentBundle()` | Precondition — programming error if violated |
| `InterruptedException` rethrow | JVM thread contract — must propagate |
| `HttpClient.close()` try-catch | Graceful shutdown — acceptable |
| `parseDeploymentStatus()` try-catch returning null | JSON parsing — acceptable to catch and return null |

---

## 7. File Changes Summary

| File | Change |
|---|---|
| `client/model/Response.kt` | Implement `ResultLike`, remove `result()`, add `foldHttp()` |
| **NEW** `client/model/ResultLike.kt` | `ResultLike` interface, `Success`, `Failure` |
| **NEW** `client/model/DeploymentError.kt` | `DeploymentError` sealed class |
| **NEW** `client/model/ValidationError.kt` | `ValidationError` sealed class |
| **NEW** `client/model/ChunkError.kt` | `ChunkError` sealed class |
| **NEW** `client/DeploymentRecoveryHandler.kt` | Recovery/rollback component |
| `utils/RetryHandler.kt` | Return `ResultLike` instead of throwing |
| `utils/Utils.kt` | `mapCredentials` returns `ResultLike<Credentials, ValidationError>` |
| `utils/BundleChunker.kt` | `chunk()` returns `ResultLike<List<Chunk>, ChunkError>`, remove `BundleSizeExceededException` |
| `task/PublishBundleMavenCentralTask.kt` | Linear flow, remove try-catch nesting, remove `DeploymentFailedException` |
| `task/PublishSplitBundleMavenCentralTask.kt` | Linear flow, remove try-catch nesting, remove `DeploymentsAlreadyCleanedUpException` |
| `client/MavenCentralApiClientImpl.kt` | Result-based retry, remove `RetriableHttpException` |
| `client/DeploymentDropHelper.kt` | Minor — may simplify try-catch since client methods return Result |

### Test files requiring updates

All tests that assert on exceptions will need to assert on Result types instead. The test count and coverage scope remain the same.
