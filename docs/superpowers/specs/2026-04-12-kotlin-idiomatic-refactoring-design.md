# Kotlin Idiomatic Refactoring Design

Refactoring the maven-central-publish Gradle plugin to use more idiomatic Kotlin patterns:
Result-based error handling consistency, naming improvements, package reorganization,
and language-level idiom adoption.

## Scope

Three phases, each independently compilable and testable:
1. **Structural** -- renames, package moves, file splits
2. **Outcome consistency** -- `validateInputs()` return type, `@Suppress("UNCHECKED_CAST")` elimination
3. **Idiomatic Kotlin** -- `buildList`, safe calls, simplified expressions, inferred types

## Phase 1: Structural Changes

### 1.1 Type Renames

| Current | New | Rationale |
|---------|-----|-----------|
| `ResultLike<T, E>` | `Outcome<T, E>` | Clearer name, no conflict with `kotlin.Result` |
| `extension.PublishingType` | `PublishingMode` | Distinguishes DSL enum from API enum |
| `client.model.PublishingType` | stays `PublishingType` | Canonical API name with `id` field |
| `MavenCentralApiClientDumbImpl` | `NoOpMavenCentralApiClient` | Standard no-op naming convention |
| `ArtifactInfo.kt` (file) | `DeploymentModels.kt` | File has 4 data classes; name should reflect content |

All references, imports, and tests must be updated accordingly.
The `mapModel()` function in `PublicationMapping.kt` converts `PublishingMode` to `PublishingType`.

### 1.2 Package Moves

| Class | From | To | Rationale |
|-------|------|----|-----------|
| `DeploymentDropHelper` | `client` | `client.recovery` | Separate recovery logic from API client |
| `DeploymentRecoveryHandler` | `client` | `client.recovery` | Same |

The `createApiClient()` factory function and `tryDropDeployment()` extension stay in `client`.

### 1.3 Split `Utils.kt`

`Utils.kt` is deleted and replaced by two files in the same `utils` package:

| New File | Content |
|----------|---------|
| `CredentialMapping.kt` | `Project.mapCredentials(extension)` |
| `PublicationMapping.kt` | `MavenPublicationInternal.mapModel(project, checksumTask)`, `PublishingMode.mapModel()` |

## Phase 2: Outcome Pattern Consistency

### 2.1 `validateInputs()` Return Type

Both `PublishBundleMavenCentralTask` and `PublishSplitBundleMavenCentralTask` change from:
```kotlin
private fun validateInputs(): ValidationError?
```
to:
```kotlin
private fun validateInputs(): Outcome<Unit, ValidationError>
```

Call site changes from:
```kotlin
validateInputs()?.let { throw it.toGradleException() }
```
to:
```kotlin
validateInputs().fold(
    onSuccess = {},
    onFailure = { throw it.toGradleException() }
)
```

### 2.2 Eliminate `@Suppress("UNCHECKED_CAST")` in `Response.kt`

`HttpResponseResult.map()` and `flatMap()` currently use unsafe casts. Replace with explicit
construction of new instances:

```kotlin
override fun <R> map(transform: (S) -> R): HttpResponseResult<R, E> where R : Any = when (this) {
    is Success         -> Success(data = transform(data), httpStatus = httpStatus, httpHeaders = httpHeaders)
    is Error           -> Error(data = data, cause = cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
    is UnexpectedError -> UnexpectedError(cause = cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
}
```

Same approach for `flatMap()`. Return type changes from `Outcome<R, E?>` to
`HttpResponseResult<R, E>` to preserve HTTP context. Verify that callers of `map()`/`flatMap()`
on `HttpResponseResult` still compile correctly.

The `copy()` method has `@Suppress("UNCHECKED_CAST")` for default lambda parameters
(`{ it as OS }`, `{ it as OE }`). Remove the default parameters and require callers to pass
both lambdas explicitly. Existing callers already pass explicit lambdas via `copySuccess()`
and `copyError()`, so the generic `copy()` can be removed entirely if unused.

## Phase 3: Idiomatic Kotlin

### 3.1 `buildList` in `createAggregationTasks()`

Extract collection building from `MavenCentralUploaderPlugin.createAggregationTasks()` into a
helper function returning `Pair<List<PublicationInfo>, List<Any>>` using `buildList`.
Eliminates `mutableListOf()` + manual `.add()/.addAll()` calls.

### 3.2 Safe Calls in `mapCredentials()`

Replace manual null checks with `?.let`/`?:`:
```kotlin
creds.bearer.token.orNull
    ?.let { Success(Credentials.BearerTokenCredentials(it)) }
    ?: Failure(ValidationError.MissingCredential("..."))
```

Same pattern for `usernamePassword` branch -- use `let` with destructuring instead of
nested `when` with `== null` checks.

### 3.3 Simplify `isRetriableException`

Replace `when` returning `true`/`false` with `||` chain:
```kotlin
private fun isRetriableException(e: Exception): Boolean =
    e is HttpTimeoutException || e is ConnectException ||
    e is SocketTimeoutException || e is IOException
```

### 3.4 Remove Explicit Types on Constants

Remove redundant `: String`, `: Int`, `: Long` from `const val` declarations where the type
is obvious from the initializer. Applies to all `companion object` blocks across the project:
- `MavenCentralUploaderPlugin.companion`
- `MavenCentralUploaderExtension.companion`
- `UploaderSettingsExtension.companion`
- `MavenCentralApiClientImpl.companion`

### 3.5 Simplify `buildQueryString()`

Replace `filter` + `!!` + `if/else` with `mapNotNull` + `takeIf` + `orEmpty()`:
```kotlin
return params
    .mapNotNull { (key, value) -> value?.let { "$key=${urlEncode(it)}" } }
    .joinToString("&")
    .takeIf { it.isNotEmpty() }
    ?.let { "?$it" }
    .orEmpty()
```

### 3.6 `BundleChunker` -- Elvis Instead of If/Else

```kotlin
chunks.firstOrNull { it.remainingCapacity(maxChunkSize) >= module.sizeBytes }
    ?.add(module)
    ?: chunks.add(MutableChunk().apply { add(module) })
```

Internal `MutableChunk` stays mutable -- first-fit decreasing algorithm requires it.

## Out of Scope

- Changing the "Result inside, exception at Gradle boundary" architecture
- Refactoring `HttpResponseResult` inheritance from `ResponseResult` (works, low value)
- Adding new tests for already-tested functionality (tests update to match renames only)
- Documentation updates beyond what this refactoring requires
- Changes to build.gradle.kts or plugin metadata

## Risk Notes

- Phase 1 renames touch imports across all files and tests. Run full test suite after.
- Phase 2 generic changes in `Response.kt` may surface type inference issues. Verify callers.
- Phase 3 changes are leaf-level and low risk.
