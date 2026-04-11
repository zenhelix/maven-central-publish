# Maven Central Publish Plugin Improvements

**Date:** 2026-04-11
**Type:** Major version bump (breaking change in credentials DSL)
**Scope:** Single PR with all improvements

## 1. Bearer Token Credentials DSL (Breaking Change)

### Problem

The current credentials DSL only supports `username`/`password`. The Maven Central Portal API recommends Bearer Token authentication, and the legacy `UserToken` header may be deprecated. The client layer (`Credentials` sealed class) already supports `BearerTokenCredentials`, but the Gradle DSL has no way to configure it.

### Solution

Replace flat `username`/`password` properties with two explicit nested blocks.

**New DSL:**

```kotlin
mavenCentralPortal {
    credentials {
        bearer { token.set("my-token") }
        // OR
        usernamePassword {
            username.set("user")
            password.set("pass")
        }
    }
}
```

### Changes

- `MavenCentralUploaderCredentialExtension`: Remove `username`/`password` properties. Add `bearer(Action<BearerCredentialExtension>)` and `usernamePassword(Action<UsernamePasswordCredentialExtension>)` methods.
- New `BearerCredentialExtension`: single `token: Property<String>` property.
- New `UsernamePasswordCredentialExtension`: `username` and `password` properties.
- Validation: exactly one block must be configured. Both or neither configured is an error at task execution time with a clear message.
- `Utils.kt` `mapCredentials()`: returns `Provider<Credentials>` (sealed class) instead of `Provider<Credentials.UsernamePasswordCredentials>`.
- API client unchanged: `Credentials.bearerToken` already handles both types.

### Files Modified

- `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt` (rewrite credential extension)
- `src/main/kotlin/.../utils/Utils.kt` (update `mapCredentials()`)
- All task files referencing credentials provider type

## 2. Remove Magic String `"http://test"`

### Problem

`PublishBundleMavenCentralTask` and `PublishSplitBundleMavenCentralTask` contain duplicated `createApiClient()` methods that check `url.equals("http://test")` to substitute `MavenCentralApiClientDumbImpl`. This is a magic string coupling production code to test infrastructure.

### Solution

- Production `createApiClient()`: remove the `"http://test"` branch, always create `MavenCentralApiClientImpl(url)`. Method stays `protected open`.
- Functional tests: register a custom task subclass that overrides `createApiClient()` to return `MavenCentralApiClientDumbImpl()`.
- Move `MavenCentralApiClientDumbImpl` from `main` to `test` source set (shared between unit tests and functionalTest via `testImplementation` dependency).

### Files Modified

- `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt`
- `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt`
- `src/main/kotlin/.../client/MavenCentralApiClientDumbImpl.kt` (move to functionalTest)
- `src/functionalTest/kotlin/...` (test build scripts and task subclasses)

## 3. RetryHandler Overflow Protection

### Problem

`calculateBackoffDelay()` uses `1L shl (attempt - 1)` without overflow guard. While safe with current defaults (`maxRetries = 3`), it is unsafe for arbitrary configurations.

### Solution

Cap the shift value:

```kotlin
private fun calculateBackoffDelay(attempt: Int): Long {
    val maxShift = 30
    val shift = (attempt - 1).coerceAtMost(maxShift)
    return baseDelay.toMillis() * (1L shl shift)
}
```

With `maxShift = 30`, the maximum multiplier is ~1 billion. Combined with any reasonable `baseDelay`, overflow is impossible.

### Files Modified

- `src/main/kotlin/.../utils/RetryHandler.kt`

## 4. KDoc for Complex Logic

### Problem

Several complex code sections lack documentation explaining the "why" behind non-obvious decisions.

### Locations

1. **`MavenCentralUploaderPlugin.kt`** — task dependency unwiring logic: why subproject tasks are disconnected in favor of aggregated tasks, and the effect on the build lifecycle.
2. **`PublishBundleMavenCentralTask.kt`** — deployment status polling state machine: document state transitions (PENDING -> VALIDATING -> VALIDATED -> PUBLISHING -> PUBLISHED) and error branches (FAILED).
3. **`BundleChunker.kt`** — KDoc for the first-fit decreasing bin packing algorithm: why this algorithm, what guarantees it provides.
4. **`MavenCentralApiClientImpl.kt`** — the `USELESS_IS_CHECK` suppression already has adequate KDoc on the `close()` method. No changes needed.

### Approach

Minimal documentation: only "why", not "what". No excessive commenting.

### Files Modified

- `src/main/kotlin/.../MavenCentralUploaderPlugin.kt`
- `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt`
- `src/main/kotlin/.../utils/BundleChunker.kt`

## 5. Test Coverage Expansion

### New Tests for Bearer Token

1. **Unit tests for `MavenCentralUploaderCredentialExtension`:**
   - `bearer` block creates `BearerTokenCredentials`
   - `usernamePassword` block creates `UsernamePasswordCredentials`
   - Both blocks configured -> validation error
   - Neither block configured -> validation error

2. **Functional test:** plugin works end-to-end with `bearer { token.set(...) }` configuration

### Edge Cases for Existing Code

3. **Unit tests for `RetryHandler`:**
   - Large `attempt` values don't cause overflow
   - Backoff delay correctly capped

4. **Unit tests for `MavenCentralApiClientImpl`:**
   - HTTP 500 -> retry
   - HTTP 429 -> retry
   - Timeout -> retry
   - All retries exhausted -> `UnexpectedError`
   - Invalid JSON in status response -> handled gracefully

5. **Functional tests:**
   - Invalid credentials configuration -> clear error message
   - Publish task with overridden `createApiClient()` (replacing magic string pattern)
