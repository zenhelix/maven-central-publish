# Exception-to-Result Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace exception-based control flow with Kotlin Result patterns across the maven-central-publish Gradle plugin.

**Architecture:** Introduce a `ResultLike<T, E>` sealed interface as the base for all Result types. Create domain-specific sealed error types (`ValidationError`, `DeploymentError`, `ChunkError`). Extract rollback logic into `DeploymentRecoveryHandler`. Convert `RetryHandler` to return Results. Push `GradleException` to `@TaskAction` boundary only.

**Tech Stack:** Kotlin, Gradle Plugin API, JUnit Jupiter, AssertJ, MockK

**Base path:** `src/main/kotlin/io/github/zenhelix/gradle/plugin` (referred to as `{base}` below)
**Test base path:** `src/test/kotlin/io/github/zenhelix/gradle/plugin` (referred to as `{testBase}` below)

---

### Task 1: Create `ResultLike` interface and concrete `Success`/`Failure`

**Files:**
- Create: `{base}/client/model/ResultLike.kt`
- Test: `{testBase}/client/model/ResultLikeTest.kt`

- [ ] **Step 1: Write tests for ResultLike**

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResultLikeTest {

    @Test
    fun `fold delegates to onSuccess for Success`() {
        val result: ResultLike<Int, String> = Success(42)
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { -1 })
        assertThat(folded).isEqualTo(84)
    }

    @Test
    fun `fold delegates to onFailure for Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { it.length })
        assertThat(folded).isEqualTo(5)
    }

    @Test
    fun `getOrNull returns value for Success`() {
        val result: ResultLike<Int, String> = Success(42)
        assertThat(result.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `getOrNull returns null for Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `errorOrNull returns null for Success`() {
        val result: ResultLike<Int, String> = Success(42)
        assertThat(result.errorOrNull()).isNull()
    }

    @Test
    fun `errorOrNull returns error for Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        assertThat(result.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `map transforms Success value`() {
        val result: ResultLike<Int, String> = Success(42)
        val mapped = result.map { it.toString() }
        assertThat(mapped.getOrNull()).isEqualTo("42")
    }

    @Test
    fun `map preserves Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        val mapped = result.map { it.toString() }
        assertThat(mapped.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `flatMap chains Success`() {
        val result: ResultLike<Int, String> = Success(42)
        val chained = result.flatMap<String> { Success(it.toString()) }
        assertThat(chained.getOrNull()).isEqualTo("42")
    }

    @Test
    fun `flatMap short-circuits on Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        val chained = result.flatMap<String> { Success(it.toString()) }
        assertThat(chained.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `getOrElse returns value for Success`() {
        val result: ResultLike<Int, String> = Success(42)
        assertThat(result.getOrElse { -1 }).isEqualTo(42)
    }

    @Test
    fun `getOrElse returns default for Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        assertThat(result.getOrElse { -1 }).isEqualTo(-1)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.model.ResultLikeTest" --no-build-cache`
Expected: Compilation failure — `ResultLike`, `Success`, `Failure` don't exist yet.

- [ ] **Step 3: Implement ResultLike**

Create `{base}/client/model/ResultLike.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

public sealed interface ResultLike<out T, out E> {
    public fun <R> fold(onSuccess: (T) -> R, onFailure: (E) -> R): R
    public fun getOrNull(): T?
    public fun errorOrNull(): E?
    public fun <R> map(transform: (T) -> R): ResultLike<R, E>
    public fun <R> flatMap(transform: (T) -> ResultLike<R, @UnsafeVariance E>): ResultLike<R, E>
    public fun getOrElse(default: (E) -> @UnsafeVariance T): T
}

public data class Success<out T>(val value: T) : ResultLike<T, Nothing> {
    override fun <R> fold(onSuccess: (T) -> R, onFailure: (Nothing) -> R): R = onSuccess(value)
    override fun getOrNull(): T = value
    override fun errorOrNull(): Nothing? = null
    override fun <R> map(transform: (T) -> R): ResultLike<R, Nothing> = Success(transform(value))
    override fun <R> flatMap(transform: (T) -> ResultLike<R, Nothing>): ResultLike<R, Nothing> = transform(value)
    override fun getOrElse(default: (Nothing) -> @UnsafeVariance T): T = value
}

public data class Failure<out E>(val error: E) : ResultLike<Nothing, E> {
    override fun <R> fold(onSuccess: (Nothing) -> R, onFailure: (E) -> R): R = onFailure(error)
    override fun getOrNull(): Nothing? = null
    override fun errorOrNull(): E = error
    override fun <R> map(transform: (Nothing) -> R): ResultLike<R, E> = this
    override fun <R> flatMap(transform: (Nothing) -> ResultLike<R, @UnsafeVariance E>): ResultLike<R, E> = this
    override fun getOrElse(default: (E) -> Nothing): Nothing = default(error)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.model.ResultLikeTest" --no-build-cache`
Expected: All 12 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/ResultLike.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/ResultLikeTest.kt
git commit -m "feat: add ResultLike sealed interface with Success and Failure"
```

---

### Task 2: Create domain sealed error types

**Files:**
- Create: `{base}/client/model/ValidationError.kt`
- Create: `{base}/client/model/DeploymentError.kt`
- Create: `{base}/client/model/ChunkError.kt`
- Test: `{testBase}/client/model/DeploymentErrorTest.kt`

- [ ] **Step 1: Write test for DeploymentError.isDroppable**

This test replaces the existing `DeploymentStateDroppableTest` logic but tests it on the new `DeploymentError` types.

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeploymentErrorTest {

    @Test
    fun `DeploymentFailed with droppable state is droppable`() {
        val error = DeploymentError.DeploymentFailed(DeploymentStateType.FAILED, null)
        assertThat(error.isDroppable).isTrue()
    }

    @Test
    fun `DeploymentFailed with PUBLISHING state is not droppable`() {
        val error = DeploymentError.DeploymentFailed(DeploymentStateType.PUBLISHING, null)
        assertThat(error.isDroppable).isFalse()
    }

    @Test
    fun `Timeout with droppable state is droppable`() {
        val error = DeploymentError.Timeout(DeploymentStateType.PENDING, 20)
        assertThat(error.isDroppable).isTrue()
    }

    @Test
    fun `Timeout with PUBLISHING state is not droppable`() {
        val error = DeploymentError.Timeout(DeploymentStateType.PUBLISHING, 20)
        assertThat(error.isDroppable).isFalse()
    }

    @Test
    fun `StatusCheckFailed is always droppable`() {
        val error = DeploymentError.StatusCheckFailed(503, "Service Unavailable")
        assertThat(error.isDroppable).isTrue()
    }

    @Test
    fun `StatusCheckUnexpected is always droppable`() {
        val error = DeploymentError.StatusCheckUnexpected(RuntimeException("network error"))
        assertThat(error.isDroppable).isTrue()
    }

    @Test
    fun `UploadFailed is not droppable`() {
        val error = DeploymentError.UploadFailed(400, "Bad Request")
        assertThat(error.isDroppable).isFalse()
    }

    @Test
    fun `UploadUnexpected is not droppable`() {
        val error = DeploymentError.UploadUnexpected(RuntimeException("timeout"))
        assertThat(error.isDroppable).isFalse()
    }

    @Test
    fun `toGradleException preserves cause for UploadUnexpected`() {
        val cause = RuntimeException("timeout")
        val error = DeploymentError.UploadUnexpected(cause)
        val gradleEx = error.toGradleException()
        assertThat(gradleEx.cause).isSameAs(cause)
        assertThat(gradleEx.message).isEqualTo("Unexpected error during bundle upload")
    }

    @Test
    fun `toGradleException has no cause for UploadFailed`() {
        val error = DeploymentError.UploadFailed(400, "Bad Request")
        val gradleEx = error.toGradleException()
        assertThat(gradleEx.cause).isNull()
        assertThat(gradleEx.message).isEqualTo("Failed to upload bundle: HTTP 400")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.model.DeploymentErrorTest" --no-build-cache`
Expected: Compilation failure — classes don't exist yet.

- [ ] **Step 3: Create ValidationError.kt**

Create `{base}/client/model/ValidationError.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

import org.gradle.api.GradleException

public sealed class ValidationError(public val message: String) {
    public data class MissingProperty(val property: String)
        : ValidationError("Property '$property' is required but not set")

    public data class InvalidFile(val path: String, val reason: String)
        : ValidationError("$reason: $path")

    public data class InvalidValue(val property: String, val detail: String)
        : ValidationError("$property: $detail")

    public data class AmbiguousCredentials(val detail: String) : ValidationError(detail)

    public data class MissingCredential(val detail: String) : ValidationError(detail)

    public data object NoCredentials : ValidationError(
        "No credentials configured. Use: credentials { bearer { token.set(\"...\") } } " +
            "or credentials { usernamePassword { username.set(\"...\"); password.set(\"...\") } }"
    )
}

public fun ValidationError.toGradleException(): GradleException = GradleException(message)
```

- [ ] **Step 4: Create DeploymentError.kt**

Create `{base}/client/model/DeploymentError.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

import java.util.UUID
import org.gradle.api.GradleException

public sealed class DeploymentError(public val message: String) {
    // Upload
    public data class UploadFailed(val httpStatus: Int, val response: String?)
        : DeploymentError("Failed to upload bundle: HTTP $httpStatus")

    public data class UploadUnexpected(val cause: Exception)
        : DeploymentError("Unexpected error during bundle upload")

    // Status polling
    public data class DeploymentFailed(val state: DeploymentStateType, val errors: Map<String, Any?>?)
        : DeploymentError(buildString {
            append("Deployment failed with status: $state")
            if (!errors.isNullOrEmpty()) append("\nErrors: $errors")
        })

    public data class StatusCheckFailed(val httpStatus: Int, val response: String?)
        : DeploymentError("Failed to check deployment status: HTTP $httpStatus")

    public data class StatusCheckUnexpected(val cause: Exception)
        : DeploymentError("Unexpected error while checking deployment status")

    public data class Timeout(val state: DeploymentStateType, val maxChecks: Int)
        : DeploymentError("Deployment did not complete after $maxChecks status checks. Current status: $state. Check Maven Central Portal for current status.")

    // Publish (split bundles)
    public data class PublishFailed(val deploymentId: UUID, val httpStatus: Int)
        : DeploymentError("Failed to publish deployment $deploymentId: HTTP $httpStatus")

    public data class PublishUnexpected(val deploymentId: UUID, val cause: Exception)
        : DeploymentError("Unexpected error publishing deployment $deploymentId")

    public val isDroppable: Boolean get() = when (this) {
        is DeploymentFailed -> state.isDroppable
        is Timeout -> state.isDroppable
        is StatusCheckFailed, is StatusCheckUnexpected -> true
        is UploadFailed, is UploadUnexpected, is PublishFailed, is PublishUnexpected -> false
    }
}

public fun DeploymentError.toGradleException(): GradleException = when (this) {
    is DeploymentError.UploadUnexpected -> GradleException(message, cause)
    is DeploymentError.StatusCheckUnexpected -> GradleException(message, cause)
    is DeploymentError.PublishUnexpected -> GradleException(message, cause)
    else -> GradleException(message)
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

Note: The `DeploymentStateType.isDroppable` extension is moved here from `PublishBundleMavenCentralTask.kt`.

- [ ] **Step 5: Create ChunkError.kt**

Create `{base}/client/model/ChunkError.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

import io.github.zenhelix.gradle.plugin.utils.toDisplayMB
import org.gradle.api.GradleException

public sealed class ChunkError(public val message: String) {
    public data class ModuleTooLarge(val moduleName: String, val moduleSize: Long, val maxSize: Long)
        : ChunkError(
            "Module '$moduleName' artifacts size ($moduleSize bytes / ${moduleSize.toDisplayMB()} MB) " +
                "exceeds maxBundleSize ($maxSize bytes / ${maxSize.toDisplayMB()} MB). " +
                "Reduce artifact size or increase maxBundleSize."
        )
}

public fun ChunkError.toGradleException(): GradleException = GradleException(message)
```

Note: `toDisplayMB` must be made `internal` (not `private`) in `SizeExtensions.kt` — it already is `internal`.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.model.DeploymentErrorTest" --no-build-cache`
Expected: All 10 tests PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/ValidationError.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentError.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/ChunkError.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/DeploymentErrorTest.kt
git commit -m "feat: add domain sealed error types (ValidationError, DeploymentError, ChunkError)"
```

---

### Task 3: Update `ResponseResult` / `HttpResponseResult` — remove `result()`, add `foldHttp()`

**Files:**
- Modify: `{base}/client/model/Response.kt`
- Test: `{testBase}/client/model/HttpResponseResultTest.kt`

- [ ] **Step 1: Write tests for foldHttp and ResultLike integration**

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HttpResponseResultTest {

    @Test
    fun `foldHttp delegates to onSuccess for Success`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Success(data = 42, httpStatus = 200)
        val folded = result.foldHttp(
            onSuccess = { data, _, _ -> "ok:$data" },
            onError = { data, _, _, _ -> "err:$data" },
            onUnexpected = { cause, _, _ -> "unexpected:${cause.message}" }
        )
        assertThat(folded).isEqualTo("ok:42")
    }

    @Test
    fun `foldHttp delegates to onError for Error`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", httpStatus = 400)
        val folded = result.foldHttp(
            onSuccess = { data, _, _ -> "ok:$data" },
            onError = { data, _, httpStatus, _ -> "err:$data:$httpStatus" },
            onUnexpected = { cause, _, _ -> "unexpected:${cause.message}" }
        )
        assertThat(folded).isEqualTo("err:bad:400")
    }

    @Test
    fun `foldHttp delegates to onUnexpected for UnexpectedError`() {
        val cause = RuntimeException("boom")
        val result: HttpResponseResult<Int, String> = HttpResponseResult.UnexpectedError(cause = cause)
        val folded = result.foldHttp(
            onSuccess = { data, _, _ -> "ok:$data" },
            onError = { data, _, _, _ -> "err:$data" },
            onUnexpected = { c, _, _ -> "unexpected:${c.message}" }
        )
        assertThat(folded).isEqualTo("unexpected:boom")
    }

    @Test
    fun `fold treats Error as failure`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", httpStatus = 400)
        val folded = result.fold(onSuccess = { "ok" }, onFailure = { "fail:$it" })
        assertThat(folded).isEqualTo("fail:bad")
    }

    @Test
    fun `fold treats UnexpectedError as failure with null error`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.UnexpectedError(cause = RuntimeException("boom"))
        val folded = result.fold(onSuccess = { "ok" }, onFailure = { "fail:$it" })
        assertThat(folded).isEqualTo("fail:null")
    }

    @Test
    fun `getOrNull returns data for Success`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Success(data = 42, httpStatus = 200)
        assertThat(result.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `getOrNull returns null for Error`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", httpStatus = 400)
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `causeOrNull returns null for Success`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Success(data = 42, httpStatus = 200)
        assertThat(result.causeOrNull()).isNull()
    }

    @Test
    fun `causeOrNull returns cause for UnexpectedError`() {
        val cause = RuntimeException("boom")
        val result: HttpResponseResult<Int, String> = HttpResponseResult.UnexpectedError(cause = cause)
        assertThat(result.causeOrNull()).isSameAs(cause)
    }

    @Test
    fun `causeOrNull returns cause for Error with cause`() {
        val cause = RuntimeException("inner")
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", cause = cause, httpStatus = 500)
        assertThat(result.causeOrNull()).isSameAs(cause)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.model.HttpResponseResultTest" --no-build-cache`
Expected: Compilation failure — `foldHttp`, `fold`, `causeOrNull` don't exist on `HttpResponseResult`.

- [ ] **Step 3: Update Response.kt**

Replace the entire content of `{base}/client/model/Response.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.client.model

public sealed class ResponseResult<out S : Any, out E : Any> {

    public data class Success<out D : Any>(val data: D) : ResponseResult<D, Nothing>()

    public data class Error<out E : Any>(val data: E? = null, val cause: Exception? = null) : ResponseResult<Nothing, E>()

    public data class UnexpectedError(val cause: Exception) : ResponseResult<Nothing, Nothing>()

}

public sealed class HttpResponseResult<out S : Any, out E : Any>(
    public open val httpStatus: Int?,
    public open val httpHeaders: Map<String, List<String>>?
) : ResponseResult<S, E>(), ResultLike<S, E?> {

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

    override fun <R> map(transform: (S) -> R): ResultLike<R, E?> = when (this) {
        is Success         -> Success(transform(data), httpStatus, httpHeaders)
        is Error           -> this
        is UnexpectedError -> this
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R> flatMap(transform: (S) -> ResultLike<R, E?>): ResultLike<R, E?> = when (this) {
        is Success         -> transform(data)
        is Error           -> this as ResultLike<R, E?>
        is UnexpectedError -> this as ResultLike<R, E?>
    }

    override fun getOrElse(default: (E?) -> @UnsafeVariance S): S = when (this) {
        is Success         -> data
        is Error           -> default(data)
        is UnexpectedError -> default(null)
    }

    public companion object {
        public fun <S : Any, E : Any> of(result: ResponseResult<S, E>): HttpResponseResult<S, E> = when (result) {
            is Error                          -> result
            is Success                        -> result
            is UnexpectedError                -> result

            is ResponseResult.Error           -> Error(data = result.data, cause = result.cause, httpStatus = 0)
            is ResponseResult.Success         -> Success(data = result.data, httpStatus = 0)
            is ResponseResult.UnexpectedError -> UnexpectedError(cause = result.cause)
        }

        public fun <S : Any, E : Any> of(
            result: ResponseResult<S, E>,
            httpStatus: Int, httpHeaders: Map<String, List<String>> = emptyMap()
        ): HttpResponseResult<S, E> = when (result) {
            is Error                          -> result
            is Success                        -> result
            is UnexpectedError                -> result

            is ResponseResult.Error           -> Error(data = result.data, cause = result.cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
            is ResponseResult.Success         -> Success(data = result.data, httpStatus = httpStatus, httpHeaders = httpHeaders)
            is ResponseResult.UnexpectedError -> UnexpectedError(cause = result.cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
        }

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

    @Suppress("UNCHECKED_CAST")
    public fun <OS : Any, OE : Any> copy(
        data: (S) -> OS = { it as OS }, error: (E?) -> OE? = { it as OE }
    ): HttpResponseResult<OS, OE> = when (val current = this) {
        is Success         -> Success(data = data(current.data), httpStatus = current.httpStatus, httpHeaders = current.httpHeaders)
        is Error           -> Error(data = error(current.data), cause = current.cause, httpStatus = current.httpStatus, httpHeaders = current.httpHeaders)
        is UnexpectedError -> current
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponseResult<*, *>) return false

        if (httpStatus != other.httpStatus) return false
        if (httpHeaders != other.httpHeaders) return false

        return true
    }

    override fun hashCode(): Int {
        var result = httpStatus ?: 0
        result = 31 * result + (httpHeaders?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "HttpResponseResult(httpStatus=$httpStatus, httpHeaders=$httpHeaders)"

}
```

Key changes:
- Removed `result()` method (unsafe unwrap)
- Removed `defaultException` field
- `HttpResponseResult` now implements `ResultLike<S, E?>` (nullable E because UnexpectedError has no typed error)
- Added `foldHttp()` for three-way matching
- Added `causeOrNull()` for accessing underlying exception
- Implemented all `ResultLike` methods

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.model.HttpResponseResultTest" --no-build-cache`
Expected: All 10 tests PASS.

- [ ] **Step 5: Run full test suite to check nothing is broken**

Run: `./gradlew test --no-build-cache`
Expected: All tests PASS. No existing code references `result()` method except the removed definition.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/Response.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/HttpResponseResultTest.kt
git commit -m "refactor: implement ResultLike on HttpResponseResult, remove unsafe result(), add foldHttp()"
```

---

### Task 4: Create `DeploymentRecoveryHandler`

**Files:**
- Create: `{base}/client/DeploymentRecoveryHandler.kt`
- Test: `{testBase}/client/DeploymentRecoveryHandlerTest.kt`

- [ ] **Step 1: Write tests for DeploymentRecoveryHandler**

```kotlin
package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.logging.Logger
import org.junit.jupiter.api.Test

class DeploymentRecoveryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)
    private val client: MavenCentralApiClient = mockk(relaxed = true)
    private val creds = io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials("test-token")
    private val deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")

    private fun createHandler() = DeploymentRecoveryHandler(client, creds, logger)

    @Test
    fun `recover drops deployment when error is droppable`() {
        every { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val error = DeploymentError.DeploymentFailed(DeploymentStateType.FAILED, null)

        val result = createHandler().recover(deploymentId, error)

        assertThat(result).isSameAs(error)
        verify(exactly = 1) { client.dropDeployment(creds, deploymentId) }
    }

    @Test
    fun `recover does not drop when error is not droppable`() {
        val error = DeploymentError.UploadFailed(400, "Bad Request")

        val result = createHandler().recover(deploymentId, error)

        assertThat(result).isSameAs(error)
        verify(exactly = 0) { client.dropDeployment(any(), any()) }
    }

    @Test
    fun `recoverAll drops only droppable deployments`() {
        every { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val id1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val id2 = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val lastKnownStates = mapOf(
            id1 to DeploymentStateType.VALIDATED,
            id2 to DeploymentStateType.PUBLISHING
        )
        val error = DeploymentError.Timeout(DeploymentStateType.VALIDATED, 20)

        createHandler().recoverAll(listOf(id1, id2), lastKnownStates, error)

        verify(exactly = 1) { client.dropDeployment(creds, id1) }
        verify(exactly = 0) { client.dropDeployment(creds, id2) }
    }

    @Test
    fun `recoverAll treats unknown state as droppable`() {
        every { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val id1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val error = DeploymentError.StatusCheckFailed(503, "Service Unavailable")

        createHandler().recoverAll(listOf(id1), emptyMap(), error)

        verify(exactly = 1) { client.dropDeployment(creds, id1) }
    }

    @Test
    fun `recoverPublishFailure drops unpublished deployments only`() {
        every { client.dropDeployment(any(), any()) } returns HttpResponseResult.Success(Unit)
        val id1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val id2 = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val id3 = UUID.fromString("33333333-3333-3333-3333-333333333333")
        val publishedIds = setOf(id1)
        val failedId = id2
        val error = DeploymentError.PublishFailed(id2, 500)

        createHandler().recoverPublishFailure(listOf(id1, id2, id3), publishedIds, failedId, error)

        // Should drop id3 only (id1 is published, id2 is the failed one)
        verify(exactly = 0) { client.dropDeployment(creds, id1) }
        verify(exactly = 0) { client.dropDeployment(creds, id2) }
        verify(exactly = 1) { client.dropDeployment(creds, id3) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.DeploymentRecoveryHandlerTest" --no-build-cache`
Expected: Compilation failure — `DeploymentRecoveryHandler` doesn't exist.

- [ ] **Step 3: Implement DeploymentRecoveryHandler**

Create `{base}/client/DeploymentRecoveryHandler.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.isDroppable
import java.util.UUID
import org.gradle.api.logging.Logger

internal class DeploymentRecoveryHandler(
    private val client: MavenCentralApiClient,
    private val credentials: Credentials,
    private val logger: Logger
) {
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

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.DeploymentRecoveryHandlerTest" --no-build-cache`
Expected: All 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/DeploymentRecoveryHandler.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/client/DeploymentRecoveryHandlerTest.kt
git commit -m "feat: add DeploymentRecoveryHandler for rollback/cleanup logic"
```

---

### Task 5: Refactor `RetryHandler` to return `ResultLike`

**Files:**
- Modify: `{base}/utils/RetryHandler.kt`
- Modify: `{testBase}/utils/RetryHandlerTest.kt`
- Modify: `{testBase}/utils/RetryHandlerBackoffTest.kt`

- [ ] **Step 1: Update RetryHandlerTest**

Replace `{testBase}/utils/RetryHandlerTest.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Success
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RetryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `should return Success on first attempt when operation succeeds`() {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(10), logger = logger)

        val result = handler.executeWithRetry(
            operation = { Success("ok") }
        )

        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `should return Failure after all retries exhausted`() {
        val handler = RetryHandler(maxRetries = 2, baseDelay = Duration.ofMillis(1), logger = logger)

        val result = handler.executeWithRetry(
            operation = { Failure(RuntimeException("always fails")) },
            shouldRetry = { true }
        )

        assertThat(result.errorOrNull()).isInstanceOf(RuntimeException::class.java)
        assertThat(result.errorOrNull()!!.message).isEqualTo("always fails")
    }

    @Test
    fun `should return Failure immediately when shouldRetry returns false`() {
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
    fun `should retry and eventually succeed`() {
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
    fun `should restore interrupt status when sleep is interrupted`() {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofSeconds(10), logger = logger)
        var attempt = 0

        val thread = Thread {
            assertThatThrownBy {
                handler.executeWithRetry(
                    operation = { attemptNum ->
                        attempt = attemptNum
                        Failure(RuntimeException("always fails"))
                    },
                    shouldRetry = { true }
                )
            }.isInstanceOf(InterruptedException::class.java)

            assertThat(Thread.currentThread().isInterrupted).isTrue()
        }

        thread.start()
        Thread.sleep(200)
        thread.interrupt()
        thread.join(5000)

        assertThat(attempt).isEqualTo(1)
        assertThat(thread.isAlive).isFalse()
    }
}
```

- [ ] **Step 2: Update RetryHandlerBackoffTest**

Replace the `normal backoff works correctly for small attempt values` test in `{testBase}/utils/RetryHandlerBackoffTest.kt`:

```kotlin
    @Test
    fun `normal backoff works correctly for small attempt values`() {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(100), logger = logger)
        val attemptTimestamps = mutableListOf<Long>()

        val result = handler.executeWithRetry(
            operation = {
                attemptTimestamps.add(System.currentTimeMillis())
                Failure(RuntimeException("always fails"))
            },
            shouldRetry = { true }
        )

        assertThat(result.errorOrNull()).isNotNull()
        assertThat(attemptTimestamps).hasSize(3)

        // Between attempt 1 and 2: ~100ms (baseDelay * 2^0)
        val delay1 = attemptTimestamps[1] - attemptTimestamps[0]
        assertThat(delay1).isBetween(50L, 500L)

        // Between attempt 2 and 3: ~200ms (baseDelay * 2^1)
        val delay2 = attemptTimestamps[2] - attemptTimestamps[1]
        assertThat(delay2).isBetween(100L, 800L)
    }
```

Also add the import at the top of `RetryHandlerBackoffTest.kt`:
```kotlin
import io.github.zenhelix.gradle.plugin.client.model.Failure
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.RetryHandlerTest" --tests "io.github.zenhelix.gradle.plugin.utils.RetryHandlerBackoffTest" --no-build-cache`
Expected: Failure — `executeWithRetry` still has old signature.

- [ ] **Step 4: Rewrite RetryHandler**

Replace `{base}/utils/RetryHandler.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.ResultLike
import io.github.zenhelix.gradle.plugin.client.model.Success
import java.time.Duration
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

    public fun <T> executeWithRetry(
        operation: (attempt: Int) -> ResultLike<T, Exception>,
        shouldRetry: (Exception) -> Boolean = { true },
        onRetry: ((attempt: Int, exception: Exception) -> Unit)? = null
    ): ResultLike<T, Exception> {
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

                    try {
                        Thread.sleep(delayMillis)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
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
        internal const val MAX_BACKOFF_DELAY_MILLIS: Long = 5 * 60 * 1000L
    }
}

public fun retryHandler(
    maxRetries: Int = 3,
    baseDelay: Duration = Duration.ofSeconds(2),
    logger: Logger
): RetryHandler = RetryHandler(maxRetries, baseDelay, logger)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.RetryHandlerTest" --tests "io.github.zenhelix.gradle.plugin.utils.RetryHandlerBackoffTest" --no-build-cache`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerTest.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerBackoffTest.kt
git commit -m "refactor: RetryHandler returns ResultLike instead of throwing"
```

---

### Task 6: Refactor `MavenCentralApiClientImpl` — remove `RetriableHttpException`

**Files:**
- Modify: `{base}/client/MavenCentralApiClientImpl.kt`
- Modify: `{testBase}/client/MavenCentralApiClientImplTest.kt`

- [ ] **Step 1: Refactor executeRequestWithRetry and remove RetriableHttpException**

In `{base}/client/MavenCentralApiClientImpl.kt`, replace `executeRequestWithRetry` method (lines 224-259), `isRetriableException` (lines 261-268), and `RetriableHttpException` (line 270):

```kotlin
    private fun <T : Any> executeRequestWithRetry(
        request: HttpRequest,
        operationName: String,
        responseHandler: (HttpResponse<String>, String) -> HttpResponseResult<T, String>
    ): HttpResponseResult<T, String> {
        val result = retryHandler.executeWithRetry(
            operation = { attempt ->
                val startTime = System.currentTimeMillis()
                val response = httpClient.send(request, BodyHandlers.ofString(UTF_8))
                val duration = System.currentTimeMillis() - startTime

                logger.debug(
                    "HTTP request completed: operation={}, status={}, duration={}ms, attempt={}",
                    operationName, response.statusCode(), duration, attempt
                )

                val httpResult = responseHandler(response, response.body())

                if (httpResult is HttpResponseResult.Error && isRetriableStatus(response.statusCode())) {
                    Failure(Exception("Retriable HTTP ${response.statusCode()}"))
                } else {
                    Success(httpResult)
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

    private fun isRetriableException(e: Exception): Boolean = when (e) {
        is HttpTimeoutException -> true
        is java.net.ConnectException -> true
        is java.net.SocketTimeoutException -> true
        is java.io.IOException -> true
        else -> false
    }
```

Also add imports at the top of the file:
```kotlin
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Success
```

Remove the `RetriableHttpException` class (line 270) entirely.

- [ ] **Step 2: Run existing tests to verify nothing breaks**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientImplTest" --no-build-cache`
Expected: All 12 existing tests PASS. The retry tests (`uploadDeploymentBundle should retry on HTTP 429`, `uploadDeploymentBundle should retry on HTTP 500`) should still work because the retry mechanism behavior is unchanged.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt
git commit -m "refactor: remove RetriableHttpException, use ResultLike in executeRequestWithRetry"
```

---

### Task 7: Refactor `BundleChunker` to return `ResultLike`

**Files:**
- Modify: `{base}/utils/BundleChunker.kt`
- Modify: `{base}/task/SplitZipDeploymentTask.kt` (calls `BundleChunker.chunk()`)
- Modify: `{testBase}/utils/BundleChunkerTest.kt`
- Modify: `{testBase}/task/SplitZipDeploymentTaskTest.kt` (asserts on `BundleSizeExceededException`)

- [ ] **Step 1: Update BundleChunkerTest**

In `{testBase}/utils/BundleChunkerTest.kt`, replace the `module exceeding limit throws exception` test and update imports:

Add imports:
```kotlin
import io.github.zenhelix.gradle.plugin.client.model.ChunkError
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Success
```

Replace the exception test:
```kotlin
    @Test
    fun `module exceeding limit returns Failure with ModuleTooLarge`() {
        val modules = listOf(ModuleSize(":big-module", 300))

        val result = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ChunkError.ModuleTooLarge::class.java)
        val moduleTooLarge = error as ChunkError.ModuleTooLarge
        assertThat(moduleTooLarge.moduleName).isEqualTo(":big-module")
        assertThat(moduleTooLarge.moduleSize).isEqualTo(300)
        assertThat(moduleTooLarge.maxSize).isEqualTo(256)
    }
```

Update all other tests that call `BundleChunker.chunk(...)` to unwrap the result. For example, replace:
```kotlin
    @Test
    fun `single module within limit produces one chunk`() {
        val modules = listOf(ModuleSize("core", 100))
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(1)
```
with:
```kotlin
    @Test
    fun `single module within limit produces one chunk`() {
        val modules = listOf(ModuleSize("core", 100))
        val chunks = (BundleChunker.chunk(modules, maxChunkSize = 256) as Success).value

        assertThat(chunks).hasSize(1)
```

Apply the same `(... as Success).value` unwrap pattern for all success-path tests: `all modules fit in one chunk`, `modules split into two chunks when exceeding limit`, `module exactly at limit produces one chunk`, `many small modules packed efficiently`.

For the `empty module list produces empty chunk list` test:
```kotlin
    @Test
    fun `empty module list produces empty chunk list`() {
        val result = BundleChunker.chunk(emptyList(), maxChunkSize = 256)
        assertThat((result as Success).value).isEmpty()
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.BundleChunkerTest" --no-build-cache`
Expected: Compilation or assertion failure.

- [ ] **Step 3: Refactor BundleChunker**

Replace `{base}/utils/BundleChunker.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.ChunkError
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.ResultLike
import io.github.zenhelix.gradle.plugin.client.model.Success

public data class ModuleSize(val name: String, val sizeBytes: Long)

public data class Chunk(val moduleNames: List<String>, val totalSize: Long)

public object BundleChunker {

    public fun chunk(modules: List<ModuleSize>, maxChunkSize: Long): ResultLike<List<Chunk>, ChunkError> {
        if (modules.isEmpty()) return Success(emptyList())

        // Validate no single module exceeds the limit
        modules.forEach { module ->
            if (module.sizeBytes > maxChunkSize) {
                return Failure(ChunkError.ModuleTooLarge(module.name, module.sizeBytes, maxChunkSize))
            }
        }

        // First-fit decreasing: sort by size descending
        val sorted = modules.sortedByDescending { it.sizeBytes }

        val chunks = mutableListOf<MutableChunk>()

        for (module in sorted) {
            val target = chunks.firstOrNull { it.remainingCapacity(maxChunkSize) >= module.sizeBytes }
            if (target != null) {
                target.add(module)
            } else {
                chunks.add(MutableChunk().apply { add(module) })
            }
        }

        return Success(chunks.map { Chunk(moduleNames = it.moduleNames, totalSize = it.totalSize) })
    }

    private class MutableChunk {
        val moduleNames = mutableListOf<String>()
        var totalSize: Long = 0L
            private set

        fun add(module: ModuleSize) {
            moduleNames.add(module.name)
            totalSize += module.sizeBytes
        }

        fun remainingCapacity(maxSize: Long): Long = maxSize - totalSize
    }
}
```

Note: `BundleSizeExceededException` is removed entirely.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.BundleChunkerTest" --no-build-cache`
Expected: All 7 tests PASS.

- [ ] **Step 5: Update SplitZipDeploymentTask to handle ResultLike from BundleChunker**

In `{base}/task/SplitZipDeploymentTask.kt`, line 61:

Replace:
```kotlin
        val chunks = BundleChunker.chunk(moduleSizes, maxSize)
```
with:
```kotlin
        val chunks = BundleChunker.chunk(moduleSizes, maxSize).fold(
            onSuccess = { it },
            onFailure = { throw it.toGradleException() }
        )
```

Add imports:
```kotlin
import io.github.zenhelix.gradle.plugin.client.model.toGradleException
```

This is a `@TaskAction` method so throwing `GradleException` is correct at this boundary.

- [ ] **Step 6: Update SplitZipDeploymentTaskTest**

In `{testBase}/task/SplitZipDeploymentTaskTest.kt`, replace the test `single module exceeding limit fails with clear message`:

Replace import:
```kotlin
import io.github.zenhelix.gradle.plugin.utils.BundleSizeExceededException
```
with:
```kotlin
import org.gradle.api.GradleException
```

Replace:
```kotlin
        assertThatThrownBy { task.createSplitZips() }
            .isInstanceOf(BundleSizeExceededException::class.java)
            .hasMessageContaining(":big")
```
with:
```kotlin
        assertThatThrownBy { task.createSplitZips() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining(":big")
```

- [ ] **Step 7: Run all related tests**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.BundleChunkerTest" --tests "io.github.zenhelix.gradle.plugin.task.SplitZipDeploymentTaskTest" --no-build-cache`
Expected: All tests PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunker.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTask.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunkerTest.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTaskTest.kt
git commit -m "refactor: BundleChunker.chunk() returns ResultLike, remove BundleSizeExceededException"
```

---

### Task 8: Refactor `mapCredentials` to return `ResultLike`

**Files:**
- Modify: `{base}/utils/Utils.kt`
- Modify: `{base}/utils/TaskExtension.kt`
- Modify: `{testBase}/extension/MavenCentralUploaderCredentialExtensionTest.kt`

- [ ] **Step 1: Update MavenCentralUploaderCredentialExtensionTest**

Replace `{testBase}/extension/MavenCentralUploaderCredentialExtensionTest.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.extension

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.utils.mapCredentials
import org.assertj.core.api.Assertions.assertThat
import org.gradle.kotlin.dsl.newInstance
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class MavenCentralUploaderCredentialExtensionTest {

    private val project = ProjectBuilder.builder().build()

    private fun createExtension(): MavenCentralUploaderExtension =
        project.objects.newInstance<MavenCentralUploaderExtension>()

    @Test
    fun `bearer block creates BearerTokenCredentials`() {
        val extension = createExtension()
        extension.credentials {
            bearer { token.set("my-token") }
        }

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Success::class.java)
        val credentials = (result as Success).value
        assertThat(credentials).isInstanceOf(Credentials.BearerTokenCredentials::class.java)
        assertThat((credentials as Credentials.BearerTokenCredentials).token).isEqualTo("my-token")
    }

    @Test
    fun `usernamePassword block creates UsernamePasswordCredentials`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                username.set("user")
                password.set("pass")
            }
        }

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Success::class.java)
        val credentials = (result as Success).value
        assertThat(credentials).isInstanceOf(Credentials.UsernamePasswordCredentials::class.java)
        val creds = credentials as Credentials.UsernamePasswordCredentials
        assertThat(creds.username).isEqualTo("user")
        assertThat(creds.password).isEqualTo("pass")
    }

    @Test
    fun `both blocks configured returns Failure with AmbiguousCredentials`() {
        val extension = createExtension()
        extension.credentials {
            bearer { token.set("my-token") }
            usernamePassword {
                username.set("user")
                password.set("pass")
            }
        }

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ValidationError.AmbiguousCredentials::class.java)
        assertThat(error.message).contains("Both 'bearer' and 'usernamePassword'")
    }

    @Test
    fun `no block configured returns Failure with NoCredentials`() {
        val extension = createExtension()

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ValidationError.NoCredentials::class.java)
        assertThat(error.message).contains("No credentials configured")
    }

    @Test
    fun `bearer block without token returns Failure with MissingCredential`() {
        val extension = createExtension()
        extension.credentials {
            bearer { /* token not set */ }
        }

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ValidationError.MissingCredential::class.java)
        assertThat(error.message).contains("Bearer token is not set")
    }

    @Test
    fun `usernamePassword block without username returns Failure with MissingCredential`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                password.set("pass")
            }
        }

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat((result as Failure).error.message).contains("Username is not set")
    }

    @Test
    fun `usernamePassword block without password returns Failure with MissingCredential`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                username.set("user")
            }
        }

        val result = project.mapCredentials(extension).get()
        assertThat(result).isInstanceOf(Failure::class.java)
        assertThat((result as Failure).error.message).contains("Password is not set")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderCredentialExtensionTest" --no-build-cache`
Expected: Compilation/assertion failure — `mapCredentials` still returns `Provider<Credentials>`.

- [ ] **Step 3: Refactor mapCredentials in Utils.kt**

Replace the `mapCredentials` function in `{base}/utils/Utils.kt`:

```kotlin
internal fun Project.mapCredentials(
    extension: MavenCentralUploaderExtension
): Provider<ResultLike<Credentials, ValidationError>> = provider {
    val creds = extension.credentials
    when {
        creds.isBearerConfigured && creds.isUsernamePasswordConfigured -> {
            Failure(ValidationError.AmbiguousCredentials(
                "Both 'bearer' and 'usernamePassword' credential blocks are configured. " +
                    "Use exactly one: credentials { bearer { ... } } or credentials { usernamePassword { ... } }"
            ))
        }
        creds.isBearerConfigured -> {
            val token = creds.bearer.token.orNull
            if (token != null) {
                Success(Credentials.BearerTokenCredentials(token))
            } else {
                Failure(ValidationError.MissingCredential("Bearer token is not set. Configure: credentials { bearer { token.set(\"...\") } }"))
            }
        }
        creds.isUsernamePasswordConfigured -> {
            val username = creds.usernamePassword.username.orNull
            val password = creds.usernamePassword.password.orNull
            when {
                username == null -> Failure(ValidationError.MissingCredential("Username is not set. Configure: credentials { usernamePassword { username.set(\"...\") } }"))
                password == null -> Failure(ValidationError.MissingCredential("Password is not set. Configure: credentials { usernamePassword { password.set(\"...\") } }"))
                else -> Success(Credentials.UsernamePasswordCredentials(username, password))
            }
        }
        else -> {
            Failure(ValidationError.NoCredentials)
        }
    }
}
```

Add imports to `{base}/utils/Utils.kt`:
```kotlin
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.ResultLike
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
```

Remove the import:
```kotlin
import org.gradle.api.GradleException
```

- [ ] **Step 4: Update TaskExtension.kt to match new return type**

In `{base}/utils/TaskExtension.kt`, the `credentials.set(...)` calls on lines 104 and 119 now need to accept `ResultLike<Credentials, ValidationError>` instead of `Credentials`.

Update the `credentials` property type in both publish tasks. In `PublishBundleMavenCentralTask.kt` (line 74-75), change:
```kotlin
    @get:Input
    public abstract val credentials: Property<Credentials>
```
to:
```kotlin
    @get:Input
    public abstract val credentials: Property<ResultLike<Credentials, ValidationError>>
```

And in `PublishSplitBundleMavenCentralTask.kt` (line 45), the same change. Add import:
```kotlin
import io.github.zenhelix.gradle.plugin.client.model.ResultLike
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
```

**Important:** This also affects the test helper classes (`TestPublishBundleTask`, `TestPublishSplitBundleTask`). The test `setUp()` calls like `credentials.set(BearerTokenCredentials("test-token"))` now need to be `credentials.set(Success(BearerTokenCredentials("test-token")))`.

Update all test files that set `credentials`:
- `{testBase}/task/PublishBundleDropBehaviorTest.kt`: `credentials.set(Success(BearerTokenCredentials("test-token")))`
- `{testBase}/task/PublishSplitBundleDropBehaviorTest.kt`: same
- `{testBase}/task/PublishSplitBundleMavenCentralTaskTest.kt`: same

Add `import io.github.zenhelix.gradle.plugin.client.model.Success` to each.

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderCredentialExtensionTest" --no-build-cache`
Expected: All 7 tests PASS.

- [ ] **Step 6: Run full test suite**

Run: `./gradlew test --no-build-cache`
Expected: All tests PASS (including the publish task tests with updated credential wrapping).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/Utils.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/TaskExtension.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderCredentialExtensionTest.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleDropBehaviorTest.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleDropBehaviorTest.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTaskTest.kt
git commit -m "refactor: mapCredentials returns ResultLike, update credential property types"
```

---

### Task 9: Refactor `PublishBundleMavenCentralTask` — linear flow

**Files:**
- Modify: `{base}/task/PublishBundleMavenCentralTask.kt`
- Modify: `{testBase}/task/PublishBundleDropBehaviorTest.kt`
- Modify: `{testBase}/task/DeploymentStateDroppableTest.kt`

- [ ] **Step 1: Update PublishBundleDropBehaviorTest assertions**

The tests currently assert on `DeploymentFailedException`. After refactoring, the task will throw `GradleException` at the `@TaskAction` boundary. Update the test assertions:

In `{testBase}/task/PublishBundleDropBehaviorTest.kt`:

Replace all `isInstanceOf(DeploymentFailedException::class.java)` with `isInstanceOf(GradleException::class.java)`.

Remove: `import io.github.zenhelix.gradle.plugin.task.DeploymentFailedException` (if present — it was used implicitly as `DeploymentFailedException`).

The `should attempt drop when status check returns HTTP error` test already asserts on `GradleException` — no change needed.

- [ ] **Step 2: Update DeploymentStateDroppableTest import**

In `{testBase}/task/DeploymentStateDroppableTest.kt`, the `isDroppable` extension is now in `DeploymentError.kt`. Update import:

```kotlin
import io.github.zenhelix.gradle.plugin.client.model.isDroppable
```
instead of the implicit import from `PublishBundleMavenCentralTask.kt`.

- [ ] **Step 3: Rewrite PublishBundleMavenCentralTask**

Replace `{base}/task/PublishBundleMavenCentralTask.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.DeploymentRecoveryHandler
import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.createApiClient as createDefaultApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.client.model.ResultLike
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.client.model.toGradleException
import java.time.Duration
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Not worth caching - publishes to external service")
public abstract class PublishBundleMavenCentralTask @Inject constructor(
    private val objects: ObjectFactory
) : DefaultTask() {

    @get:Input
    public abstract val baseUrl: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val zipFile: RegularFileProperty

    @get:Input
    @get:Optional
    public abstract val publishingType: Property<PublishingType>

    @get:Input
    @get:Optional
    public abstract val deploymentName: Property<String>

    @get:Input
    public abstract val credentials: Property<ResultLike<Credentials, ValidationError>>

    @get:Input
    public abstract val maxStatusChecks: Property<Int>

    @get:Input
    public abstract val statusCheckDelay: Property<Duration>

    protected open fun createApiClient(url: String): MavenCentralApiClient = createDefaultApiClient(url)

    init {
        group = PUBLISH_TASK_GROUP
        description = "Publishes a deployment bundle to Maven Central Portal"

        publishingType.convention(PublishingType.AUTOMATIC)
        maxStatusChecks.convention(20)
        statusCheckDelay.convention(Duration.ofSeconds(10))
    }

    @TaskAction
    public fun publishBundle() {
        validateInputs()?.let { throw it.toGradleException() }

        val creds = credentials.get().fold(
            onSuccess = { it },
            onFailure = { throw it.toGradleException() }
        )

        val error = executePublishing(creds)
        error?.let { throw it.toGradleException() }
    }

    private fun executePublishing(creds: Credentials): DeploymentError? {
        val bundleFile = zipFile.asFile.get()
        val type = publishingType.orNull
        val name = deploymentName.orNull
        val maxChecks = maxStatusChecks.get()
        val checkDelay = statusCheckDelay.get()

        logger.lifecycle("Publishing deployment bundle: ${bundleFile.name}. Publishing type: ${type ?: PublishingType.AUTOMATIC}. Deployment name: $name")

        return createApiClient(baseUrl.get()).use { apiClient ->
            val recoveryHandler = DeploymentRecoveryHandler(apiClient, creds, logger)

            apiClient.uploadDeploymentBundle(
                credentials = creds, bundle = bundleFile.toPath(), publishingType = type, deploymentName = name
            ).foldHttp(
                onSuccess = { deploymentId, _, _ ->
                    val waitResult = waitForDeploymentCompletion(apiClient, creds, deploymentId, type, maxChecks, checkDelay)
                    waitResult.fold(
                        onSuccess = { null },
                        onFailure = { error -> recoveryHandler.recover(deploymentId, error) }
                    )
                },
                onError = { data, _, httpStatus, _ ->
                    DeploymentError.UploadFailed(httpStatus, data)
                },
                onUnexpected = { cause, _, _ ->
                    DeploymentError.UploadUnexpected(cause)
                }
            )
        }
    }

    private fun validateInputs(): ValidationError? {
        if (!zipFile.isPresent) return ValidationError.MissingProperty("zipFile")
        if (!credentials.isPresent) return ValidationError.MissingProperty("credentials")

        val file = zipFile.asFile.get()
        if (!file.exists()) return ValidationError.InvalidFile(file.absolutePath, "Bundle file does not exist")
        if (!file.isFile) return ValidationError.InvalidFile(file.absolutePath, "Bundle path is not a file")
        if (file.length() == 0L) return ValidationError.InvalidFile(file.absolutePath, "Bundle file is empty")

        val maxChecks = maxStatusChecks.get()
        if (maxChecks < 1) return ValidationError.InvalidValue("maxStatusChecks", "must be at least 1, got: $maxChecks")

        return null
    }

    private fun waitForDeploymentCompletion(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentId: UUID,
        publishingType: PublishingType?,
        maxChecks: Int, checkDelay: Duration
    ): ResultLike<Unit, DeploymentError> {
        repeat(maxChecks) { checkIndex ->
            val checkNumber = checkIndex + 1

            val statusResult = client.deploymentStatus(creds, deploymentId)

            val stepResult: DeploymentPollStep = statusResult.foldHttp(
                onSuccess = { status, _, _ ->
                    logger.debug("Deployment status check ({}/{}): {}", checkNumber, maxChecks, status.deploymentState)

                    when (evaluateState(status.deploymentState, publishingType)) {
                        DeploymentState.SUCCESS -> {
                            if (publishingType == PublishingType.USER_MANAGED) {
                                logger.lifecycle("Note: USER_MANAGED publishing type - you may need to manually release the deployment in Central Portal")
                            }
                            DeploymentPollStep.Done
                        }
                        DeploymentState.FAILED -> DeploymentPollStep.Terminal(
                            DeploymentError.DeploymentFailed(status.deploymentState, status.errors)
                        )
                        DeploymentState.IN_PROGRESS -> {
                            if (checkNumber >= maxChecks) {
                                DeploymentPollStep.Terminal(DeploymentError.Timeout(status.deploymentState, maxChecks))
                            } else {
                                DeploymentPollStep.Continue
                            }
                        }
                    }
                },
                onError = { data, _, httpStatus, _ ->
                    DeploymentPollStep.Terminal(DeploymentError.StatusCheckFailed(httpStatus, data))
                },
                onUnexpected = { cause, _, _ ->
                    DeploymentPollStep.Terminal(DeploymentError.StatusCheckUnexpected(cause))
                }
            )

            when (stepResult) {
                is DeploymentPollStep.Done -> return Success(Unit)
                is DeploymentPollStep.Terminal -> return Failure(stepResult.error)
                is DeploymentPollStep.Continue -> {
                    try {
                        Thread.sleep(checkDelay.toMillis())
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }

        return Failure(DeploymentError.Timeout(DeploymentStateType.UNKNOWN, maxChecks))
    }

    private fun evaluateState(state: DeploymentStateType, publishingType: PublishingType?): DeploymentState =
        when (state) {
            DeploymentStateType.PENDING, DeploymentStateType.VALIDATING -> DeploymentState.IN_PROGRESS
            DeploymentStateType.VALIDATED -> {
                if (publishingType == PublishingType.USER_MANAGED) DeploymentState.SUCCESS
                else DeploymentState.IN_PROGRESS
            }
            DeploymentStateType.PUBLISHING -> DeploymentState.IN_PROGRESS
            DeploymentStateType.PUBLISHED -> DeploymentState.SUCCESS
            DeploymentStateType.FAILED, DeploymentStateType.UNKNOWN -> DeploymentState.FAILED
        }

    private enum class DeploymentState {
        SUCCESS, IN_PROGRESS, FAILED
    }

    private sealed class DeploymentPollStep {
        data object Done : DeploymentPollStep()
        data object Continue : DeploymentPollStep()
        data class Terminal(val error: DeploymentError) : DeploymentPollStep()
    }
}
```

Note: `DeploymentFailedException`, `DeploymentStateType.isDroppable` extension, and the private `tryDropDeployment` wrapper are removed from this file. The `isDroppable` extension is now in `DeploymentError.kt`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.task.*" --no-build-cache`
Expected: All publish task tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleDropBehaviorTest.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/DeploymentStateDroppableTest.kt
git commit -m "refactor: PublishBundleMavenCentralTask uses linear Result flow, remove DeploymentFailedException"
```

---

### Task 10: Refactor `PublishSplitBundleMavenCentralTask` — linear flow

**Files:**
- Modify: `{base}/task/PublishSplitBundleMavenCentralTask.kt`
- Modify: `{testBase}/task/PublishSplitBundleDropBehaviorTest.kt`

- [ ] **Step 1: Update PublishSplitBundleDropBehaviorTest**

In `{testBase}/task/PublishSplitBundleDropBehaviorTest.kt`:

Replace `isInstanceOf(DeploymentsAlreadyCleanedUpException::class.java)` with `isInstanceOf(GradleException::class.java)` in the `should not double-drop` test.

- [ ] **Step 2: Rewrite PublishSplitBundleMavenCentralTask**

Replace `{base}/task/PublishSplitBundleMavenCentralTask.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.DeploymentRecoveryHandler
import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.createApiClient as createDefaultApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.client.model.ResultLike
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.client.model.isDroppable
import io.github.zenhelix.gradle.plugin.client.model.toGradleException
import java.io.File
import java.time.Duration
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Not worth caching - publishes to external service")
public abstract class PublishSplitBundleMavenCentralTask : DefaultTask() {

    @get:Input
    public abstract val baseUrl: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    public abstract val bundlesDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    public abstract val publishingType: Property<PublishingType>

    @get:Input
    @get:Optional
    public abstract val deploymentName: Property<String>

    @get:Input
    public abstract val credentials: Property<ResultLike<Credentials, ValidationError>>

    @get:Input
    public abstract val maxStatusChecks: Property<Int>

    @get:Input
    public abstract val statusCheckDelay: Property<Duration>

    protected open fun createApiClient(url: String): MavenCentralApiClient = createDefaultApiClient(url)

    init {
        group = PUBLISH_TASK_GROUP
        description = "Publishes split deployment bundles to Maven Central Portal"

        publishingType.convention(PublishingType.AUTOMATIC)
        maxStatusChecks.convention(20)
        statusCheckDelay.convention(Duration.ofSeconds(10))
    }

    @TaskAction
    public fun publishBundles() {
        validateInputs()?.let { throw it.toGradleException() }

        val creds = credentials.get().fold(
            onSuccess = { it },
            onFailure = { throw it.toGradleException() }
        )

        val error = executePublishing(creds)
        error?.let { throw it.toGradleException() }
    }

    private fun executePublishing(creds: Credentials): DeploymentError? {
        val bundlesDir = bundlesDirectory.asFile.get()
        val bundleFiles = bundlesDir
            .listFiles { f -> f.extension == "zip" }
            ?.sortedBy { it.name }
            .orEmpty()

        if (bundleFiles.isEmpty()) {
            return DeploymentError.UploadFailed(0, "No ZIP bundles found in ${bundlesDir.absolutePath}")
        }

        val maxChecks = maxStatusChecks.get()
        val checkDelay = statusCheckDelay.get()
        val baseName = deploymentName.orNull

        val requestedType = publishingType.orNull
        val effectiveType = if (bundleFiles.size > 1 && requestedType == PublishingType.AUTOMATIC) {
            logger.lifecycle(
                "Bundle was split into ${bundleFiles.size} chunks. " +
                        "Switching to USER_MANAGED mode for atomic deployment. " +
                        "All chunks will be published after successful validation."
            )
            PublishingType.USER_MANAGED
        } else {
            requestedType
        }

        return createApiClient(baseUrl.get()).use { client ->
            val recoveryHandler = DeploymentRecoveryHandler(client, creds, logger)

            val uploadResult = uploadAllBundles(client, creds, bundleFiles, effectiveType, baseName)
            uploadResult.fold(
                onSuccess = { deploymentIds ->
                    val lastKnownStates = mutableMapOf<UUID, DeploymentStateType>()

                    val waitResult = waitForAllDeploymentsValidated(
                        client, creds, deploymentIds, effectiveType, maxChecks, checkDelay, lastKnownStates
                    )

                    waitResult.fold(
                        onSuccess = {
                            if (effectiveType != requestedType) {
                                val publishResult = publishAllDeployments(client, creds, deploymentIds, recoveryHandler)
                                publishResult.fold(
                                    onSuccess = { null },
                                    onFailure = { it }
                                )
                            } else {
                                null
                            }
                        },
                        onFailure = { error ->
                            recoveryHandler.recoverAll(deploymentIds, lastKnownStates, error)
                        }
                    )
                },
                onFailure = { it }
            )
        }
    }

    private fun uploadAllBundles(
        client: MavenCentralApiClient,
        creds: Credentials,
        bundleFiles: List<File>,
        effectiveType: PublishingType?,
        baseName: String?
    ): ResultLike<List<UUID>, DeploymentError> {
        val deploymentIds = mutableListOf<UUID>()
        val totalChunks = bundleFiles.size

        for ((index, bundleFile) in bundleFiles.withIndex()) {
            val chunkNumber = index + 1
            val chunkName = if (baseName != null) "$baseName-chunk-$chunkNumber" else null

            logger.lifecycle("Uploading chunk $chunkNumber/$totalChunks: ${bundleFile.name}...")

            val result = client.uploadDeploymentBundle(
                credentials = creds,
                bundle = bundleFile.toPath(),
                publishingType = effectiveType,
                deploymentName = chunkName
            )

            val error = result.foldHttp(
                onSuccess = { deploymentId, _, _ ->
                    deploymentIds.add(deploymentId)
                    logger.lifecycle("Uploading chunk $chunkNumber/$totalChunks... OK (deployment: $deploymentId)")
                    null
                },
                onError = { data, _, httpStatus, _ ->
                    DeploymentError.UploadFailed(httpStatus,
                        "Failed to upload chunk $chunkNumber/$totalChunks (${bundleFile.name}): " +
                            "HTTP $httpStatus, Response: $data. " +
                            "Rolled back ${deploymentIds.size} previously uploaded deployment(s)."
                    )
                },
                onUnexpected = { cause, _, _ ->
                    DeploymentError.UploadUnexpected(cause)
                }
            )

            if (error != null) {
                deploymentIds.forEach { id ->
                    client.tryDropDeployment(creds, id, logger)
                }
                return Failure(error)
            }
        }

        return Success(deploymentIds)
    }

    private fun waitForAllDeploymentsValidated(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>,
        effectiveType: PublishingType?,
        maxChecks: Int,
        checkDelay: Duration,
        lastKnownStates: MutableMap<UUID, DeploymentStateType>
    ): ResultLike<Unit, DeploymentError> {
        val terminalStates = mutableMapOf<UUID, DeploymentStateType>()

        repeat(maxChecks) { checkIndex ->
            val checkNumber = checkIndex + 1
            val pendingIds = deploymentIds.filter { it !in terminalStates }

            for (deploymentId in pendingIds) {
                val statusResult = client.deploymentStatus(creds, deploymentId)

                val error: DeploymentError? = statusResult.foldHttp(
                    onSuccess = { status, _, _ ->
                        val state = status.deploymentState
                        lastKnownStates[deploymentId] = state

                        when {
                            state == DeploymentStateType.FAILED || state == DeploymentStateType.UNKNOWN -> {
                                DeploymentError.DeploymentFailed(state, status.errors)
                            }
                            state == DeploymentStateType.PUBLISHED -> {
                                terminalStates[deploymentId] = state
                                null
                            }
                            state == DeploymentStateType.VALIDATED && effectiveType == PublishingType.USER_MANAGED -> {
                                terminalStates[deploymentId] = state
                                null
                            }
                            else -> null
                        }
                    },
                    onError = { data, _, httpStatus, _ ->
                        DeploymentError.StatusCheckFailed(httpStatus, "Failed to check deployment status for $deploymentId: HTTP $httpStatus, Response: $data")
                    },
                    onUnexpected = { cause, _, _ ->
                        DeploymentError.StatusCheckUnexpected(cause)
                    }
                )

                if (error != null) return Failure(error)
            }

            val statusSummary = deploymentIds.mapIndexed { i, id ->
                val state = terminalStates[id]?.name ?: "PENDING"
                "${i + 1}/${deploymentIds.size} $state"
            }
            logger.lifecycle("Validating deployments... [${statusSummary.joinToString(", ")}]")

            if (terminalStates.size == deploymentIds.size) {
                logger.lifecycle("All ${deploymentIds.size} deployment(s) validated successfully.")
                return Success(Unit)
            }

            if (checkNumber < maxChecks) {
                try {
                    Thread.sleep(checkDelay.toMillis())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }

        return Failure(DeploymentError.Timeout(DeploymentStateType.UNKNOWN, maxChecks))
    }

    private fun publishAllDeployments(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>,
        recoveryHandler: DeploymentRecoveryHandler
    ): ResultLike<Unit, DeploymentError> {
        logger.lifecycle("Publishing all ${deploymentIds.size} deployment(s)...")

        val publishedIds = mutableSetOf<UUID>()

        for (deploymentId in deploymentIds) {
            val result = client.publishDeployment(creds, deploymentId)

            val error: DeploymentError? = result.foldHttp(
                onSuccess = { _, _, _ ->
                    publishedIds.add(deploymentId)
                    logger.lifecycle("Published deployment $deploymentId")
                    null
                },
                onError = { _, _, httpStatus, _ ->
                    DeploymentError.PublishFailed(deploymentId, httpStatus)
                },
                onUnexpected = { cause, _, _ ->
                    DeploymentError.PublishUnexpected(deploymentId, cause)
                }
            )

            if (error != null) {
                recoveryHandler.recoverPublishFailure(deploymentIds, publishedIds, deploymentId, error)
                return Failure(error)
            }
        }

        logger.lifecycle("Published successfully.")
        return Success(Unit)
    }

    private fun validateInputs(): ValidationError? {
        if (!bundlesDirectory.isPresent) return ValidationError.MissingProperty("bundlesDirectory")
        if (!credentials.isPresent) return ValidationError.MissingProperty("credentials")

        val dir = bundlesDirectory.asFile.get()
        if (!dir.exists()) return ValidationError.InvalidFile(dir.absolutePath, "Bundles directory does not exist")
        if (!dir.isDirectory) return ValidationError.InvalidFile(dir.absolutePath, "Bundles path is not a directory")

        val maxChecks = maxStatusChecks.get()
        if (maxChecks < 1) return ValidationError.InvalidValue("maxStatusChecks", "must be at least 1, got: $maxChecks")

        return null
    }
}
```

Note: `DeploymentsAlreadyCleanedUpException` is removed entirely. The `tryDropDeployment` import refers to the extension function in `DeploymentDropHelper.kt`.

- [ ] **Step 3: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.task.*" --no-build-cache`
Expected: All task tests PASS.

- [ ] **Step 4: Run full test suite**

Run: `./gradlew test --no-build-cache`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleDropBehaviorTest.kt
git commit -m "refactor: PublishSplitBundleMavenCentralTask uses linear Result flow, remove DeploymentsAlreadyCleanedUpException"
```

---

### Task 11: Final verification and cleanup

**Files:**
- All modified files

- [ ] **Step 1: Run full test suite including functional tests**

Run: `./gradlew check --no-build-cache`
Expected: All unit tests, functional tests, and validation pass.

- [ ] **Step 2: Verify no remaining exception control flow patterns**

Search for leftover patterns:

```bash
grep -rn "throw GradleException" src/main/kotlin/ | grep -v "@TaskAction" | grep -v "test"
```

Expected: No results in the main source (except `@TaskAction` methods which convert Result → GradleException). The `require()` calls in `RetryHandler` and `MavenCentralApiClientImpl.uploadDeploymentBundle` are acceptable (preconditions).

```bash
grep -rn "DeploymentFailedException\|DeploymentsAlreadyCleanedUpException\|BundleSizeExceededException\|RetriableHttpException" src/
```

Expected: No results — all four exception classes are removed.

```bash
grep -rn "\.result(" src/main/kotlin/
```

Expected: No results — the `result()` method is removed from `HttpResponseResult`.

- [ ] **Step 3: Commit any cleanup**

If any leftover issues are found, fix and commit:

```bash
git add -A
git commit -m "chore: cleanup remaining exception references after Result refactoring"
```

- [ ] **Step 4: Run check one final time**

Run: `./gradlew check --no-build-cache`
Expected: BUILD SUCCESSFUL. All tests pass, code coverage report generates.

---

## Dependency Graph

```
Task 1 (ResultLike) ──┐
                       ├── Task 3 (Response.kt update)
Task 2 (Error types) ─┤
                       ├── Task 4 (DeploymentRecoveryHandler)
                       │
                       ├── Task 5 (RetryHandler) ── Task 6 (ApiClientImpl)
                       │
                       ├── Task 7 (BundleChunker)
                       │
                       ├── Task 8 (mapCredentials) ──┐
                       │                             ├── Task 9 (PublishBundleTask)
                       ├─────────────────────────────┤
                                                     ├── Task 10 (PublishSplitBundleTask)
                                                     │
                                                     └── Task 11 (Final verification)
```

Tasks 1-2 are prerequisites for everything. Tasks 3-8 can run in parallel after Tasks 1-2 are done. Tasks 9-10 depend on Tasks 3, 4, 5, 6, 8. Task 11 is the final gate.
