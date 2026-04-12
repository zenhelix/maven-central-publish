# Kotlin Idioms Refactoring Design

## Overview

Comprehensive refactoring of the maven-central-publish Gradle plugin to adopt idiomatic Kotlin patterns: coroutines, Result-based error handling extensions, DSL builders, and improved project structure.

## Approach: Bottom-up (A)

Changes are organized in layers — each phase stabilizes before the next begins. Every phase should compile and pass tests independently.

## Decisions

- **Custom `Outcome<T,E>`** stays (no switch to `kotlin.Result`)
- **Coroutines** adopted everywhere (not just `Thread.sleep` replacement)
- **DSL-builder** for API calls (not just higher-order function)
- **Separate configurator classes** for Plugin (not extension functions)
- **`ResponseResult`** removed (only `HttpResponseResult` remains)
- **No backward compatibility constraints** — free to rename, move, break API
- **`Credentials.equals/hashCode`** removed — credentials are never compared in the codebase; removing avoids timing-attack surface on secrets

---

## Phase 1: Foundation — Models and Outcome

### 1.1. Remove `ResponseResult`

Delete `Response.kt` entirely. `HttpResponseResult` becomes a standalone sealed class — no longer extends `ResponseResult`. Remove companion `of(ResponseResult)` methods from `HttpResponseResult`.

### 1.2. Extend `Outcome`

Add extension functions to `Outcome.kt`:

- `getOrThrow(transform: (E) -> Throwable): T` — replaces verbose `.fold(onSuccess = { it }, onFailure = { throw ... })`
- `onSuccess(action: (T) -> Unit): Outcome<T, E>` — side-effect without fold, returns self
- `onFailure(action: (E) -> Unit): Outcome<T, E>` — same for error path
- `mapError(transform: (E) -> R): Outcome<T, R>` — error transformation (currently missing)

### 1.3. Cleanup `Credentials`

Remove manual `equals`/`hashCode` from sealed class `Credentials`. Data class subclasses retain their auto-generated implementations. Credentials are never compared or used as map keys in the codebase.

### 1.4. Add kotlinx-coroutines dependency

Add `org.jetbrains.kotlinx:kotlinx-coroutines-core` to `build.gradle.kts` dependencies. Foundation for phases 2-3.

---

## Phase 2: Client — Suspend API and DSL Builder

### 2.1. Suspend API

`MavenCentralApiClient` interface — all methods become `suspend fun`:
- `suspend fun uploadDeploymentBundle(...)`
- `suspend fun deploymentStatus(...)`
- `suspend fun publishDeployment(...)`
- `suspend fun dropDeployment(...)`
- `close()` remains non-suspend

`NoOpMavenCentralApiClient` updated accordingly.

### 2.2. Suspend RetryHandler

`RetryHandler.executeWithRetry` becomes `suspend fun`. `Thread.sleep` replaced with `delay()`. Three identical try-catch blocks for `InterruptedException` disappear — `delay()` supports cancellation natively via `CancellationException`.

### 2.3. DSL Builder for API Calls

Current state: 4 methods in `MavenCentralApiClientImpl` with nearly identical patterns (build request -> executeRequestWithRetry -> if status == X success else error).

Introduce internal DSL:

```kotlin
private suspend fun <T : Any> apiCall(
    operationName: String,
    build: ApiCallBuilder.() -> Unit
): HttpResponseResult<T, String>
```

`ApiCallBuilder` is an internal class:

```kotlin
internal class ApiCallBuilder {
    lateinit var uri: URI
    var method: HttpMethod = HttpMethod.POST
    var body: HttpRequest.BodyPublisher = noBody()
    var headers: MutableMap<String, String> = mutableMapOf()
    var timeout: Duration = defaultTimeout

    var successStatus: Int = 200
    lateinit var parseSuccess: (String) -> Any
    var successLog: String? = null
    var errorLog: String? = null
}
```

Example usage after refactoring:

```kotlin
override suspend fun publishDeployment(
    credentials: Credentials, deploymentId: UUID
): HttpResponseResult<Unit, String> = apiCall("publishDeployment") {
    uri = URI("$baseUrl/api/v1/publisher/deployment/${urlEncode(deploymentId.toString())}")
    authorize(credentials)
    post()

    expectStatus(HTTP_NO_CONTENT)
    onSuccess { Unit }
    onSuccessLog { "Deployment published successfully: $deploymentId" }
    onErrorLog { status, body -> "Failed to publish deployment. Status: $status, Response: $body" }
}
```

### 2.4. `executeRequestWithRetry` -> suspend

Inside DSL builder, `httpClient.send()` is wrapped in `withContext(Dispatchers.IO)` for the blocking Java HttpClient.

### 2.5. `DeploymentDropHelper.tryDropDeployment` -> suspend

Recovery logic becomes suspend. try-catch for `InterruptedException` replaced with standard coroutine cancellation.

---

## Phase 3: Tasks — Coroutines and Kotlin Idioms

### 3.1. Coroutines in Gradle Tasks

Gradle `@TaskAction` methods remain regular `fun`. Suspend API is called via `runBlocking`:

```kotlin
@TaskAction
public fun publishBundle() {
    runBlocking { doPublishBundle() }
}

private suspend fun doPublishBundle() { ... }
```

`runBlocking` is a conscious choice: Gradle tasks execute in a thread pool and need a blocking entry point. All internal logic is suspend.

### 3.2. `delay()` instead of `Thread.sleep`

In `waitForDeploymentCompletion` and `waitForAllDeploymentsValidated`: `Thread.sleep(checkDelay.toMillis())` with try-catch replaced by `delay(checkDelay.toMillis())`. Three identical try-catch blocks eliminated.

### 3.3. Mutable collections -> buildList/buildSet

`PublishSplitBundleMavenCentralTask`:
- `mutableListOf<UUID>()` in `uploadAllBundles` -> `buildList { }`
- `mutableMapOf<UUID, DeploymentStateType>()` in `waitForAllDeploymentsValidated` — stays mutable (justified by polling loop updates)
- `mutableSetOf<UUID>()` in `publishAllDeployments` -> `buildSet { }`

`SplitZipDeploymentTask`:
- `mutableSetOf<String>()` for `addedEntries` — stays mutable (deduplication in loop)

Principle: replace with immutable only where it doesn't complicate logic. Mutable state in polling loops is acceptable.

### 3.4. Verbose fold -> getOrThrow

Both tasks:

```kotlin
// Before:
validateInputs().fold(
    onSuccess = {},
    onFailure = { throw it.toGradleException() }
)
val creds = credentials.get().fold(
    onSuccess = { it },
    onFailure = { throw it.toGradleException() }
)

// After:
validateInputs().getOrThrow { it.toGradleException() }
val creds = credentials.get().getOrThrow { it.toGradleException() }
```

---

## Phase 4: Plugin — Split into Configurators

### 4.1. Structure

`MavenCentralUploaderPlugin.kt` (308 lines) splits into:

- **`MavenCentralUploaderPlugin.kt`** (~50 lines) — entry point, delegates to configurators
- **`ZipDeploymentConfigurator.kt`** (~70 lines) — current `configureZipDeploymentTasks()`. Per-project checksum, zip, publish task registration.
- **`RootProjectConfigurator.kt`** (~90 lines) — current `configureRootProjectLifecycle()` + `createAggregationTasks()` + `collectAggregationData()`. Atomic aggregation mode.
- **`SubprojectConfigurator.kt`** (~20 lines) — current `emitIndependentPublishingWarningIfNeeded()`. One-time warning registration.

### 4.2. Package

Configurators live in `plugin/configurator/`:

```
plugin/
  MavenCentralUploaderPlugin.kt
  configurator/
    ZipDeploymentConfigurator.kt
    RootProjectConfigurator.kt
    SubprojectConfigurator.kt
```

### 4.3. Visibility

All configurators are `internal object` with a single public method `configure(...)`. Constants move to the configurator that owns them:
- `PUBLISH_ALL_PUBLICATIONS_TASK_NAME` -> `RootProjectConfigurator`
- `WARN_REGISTERED_FLAG` -> `SubprojectConfigurator`
- `MAVEN_CENTRAL_PORTAL_NAME` and `MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID` stay in `MavenCentralUploaderPlugin` companion.

---

## Phase 5: Naming, Packages and Final Cleanup

### 5.1. Renames

| Current | New | Reason |
|---------|-----|--------|
| `Response.kt` | deleted | ResponseResult removed (phase 1) |
| `MavenCentralApiClientImpl` | `DefaultMavenCentralApiClient` | Standard Kotlin convention (`Default*` over `*Impl`) |
| `MavenCentralPublishExceptions.kt` | `MavenCentralExceptions.kt` | Shorter; `Publish` is redundant in package context |
| `TaskExtension.kt` | `TaskRegistration.kt` | Accurately reflects content — task registration, not Task extensions |
| `ProjectExtensions.kt` | `ProjectUtils.kt` | Contains utility functions for Project, not only extensions |

### 5.2. Package Moves

| File | From | To | Reason |
|------|------|----|--------|
| ~~`DeploymentModels.kt`~~ | ~~`task/`~~ | ~~`client/model/`~~ | **Stays in `task/`** — imports Gradle `Provider`, `ListProperty`, `MavenPublication`, `MavenArtifact`. Moving to `client/model/` would create unwanted dependency of client layer on Gradle API |
| `BundleChunker.kt` | `utils/` | `client/` | Chunking is client-side bundle preparation logic, not a generic utility |
| `SizeExtensions.kt` | `utils/` | `extension/` | DSL extensions (`megabytes`, `gigabytes`) are part of the user-facing extension API |

### 5.3. Dead Code Removal

- `HttpResponseResult.equals/hashCode/toString` — custom overrides in sealed class with data class subclasses
- `ResponseResult` + companion `of()` methods — after ResponseResult deletion
- `retryHandler()` top-level function in `RetryHandler.kt` — factory function with no added value over constructor

### 5.4. Minor Kotlin Idioms

- `DeploymentStateType.ofOrNull()`: `values().firstOrNull` -> `entries.firstOrNull` (Kotlin `entries` over Java `values()`)
- `when` for task dependency type in `RootProjectConfigurator` — extract to extension `Any.taskNameOrNull(): String?`
- `HttpResponseResult.map()`: remove `@Suppress("UNCHECKED_CAST")` — redesign with proper generics

---

## Final Package Structure

```
io.github.zenhelix.gradle.plugin/
  MavenCentralUploaderPlugin.kt
  configurator/
    ZipDeploymentConfigurator.kt
    RootProjectConfigurator.kt
    SubprojectConfigurator.kt
  client/
    MavenCentralApiClient.kt
    DefaultMavenCentralApiClient.kt        (renamed from Impl)
    NoOpMavenCentralApiClient.kt
    BundleChunker.kt                        (moved from utils/)
    model/
      Outcome.kt                            (with new extensions)
      HttpResponseResult.kt                 (standalone, no ResponseResult parent)
      Credentials.kt                        (no custom equals/hashCode)
      DeploymentError.kt
      DeploymentStatus.kt
      ValidationError.kt
      ChunkError.kt
      PublishingType.kt
      MavenCentralExceptions.kt             (renamed)
    recovery/
      DeploymentDropHelper.kt               (suspend)
      DeploymentRecoveryHandler.kt          (suspend)
  extension/
    MavenCentralUploaderExtension.kt
    PublishingMode.kt
    SizeExtensions.kt                       (moved from utils/)
  task/
    CreateChecksumTask.kt
    DeploymentModels.kt                     (stays — depends on Gradle API)
    PublishBundleMavenCentralTask.kt        (runBlocking + suspend internals)
    PublishSplitBundleMavenCentralTask.kt   (runBlocking + suspend internals)
    SplitZipDeploymentTask.kt
    ZipDeploymentTask.kt
  utils/
    CredentialMapping.kt
    PublicationMapping.kt
    TaskRegistration.kt                     (renamed from TaskExtension.kt)
    ProjectUtils.kt                         (renamed from ProjectExtensions.kt)
    RetryHandler.kt                         (suspend)
```
