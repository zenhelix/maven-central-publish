# Kotlin Idiomatic Refactoring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor the maven-central-publish Gradle plugin to use more idiomatic Kotlin: rename `ResultLike` to `Outcome`, resolve duplicate `PublishingType`, reorganize packages, eliminate `@Suppress("UNCHECKED_CAST")`, and apply Kotlin idioms (safe calls, `buildList`, inferred types).

**Architecture:** Three-phase approach — structural changes first (renames, moves, splits), then Outcome pattern consistency (`validateInputs()`, `Response.kt` generics), then idiomatic Kotlin polish. Each phase produces a compilable, passing codebase and a separate commit.

**Tech Stack:** Kotlin 2.3.20, Gradle 8.x, JUnit 5, AssertJ, MockK

**Test command:** `./gradlew test` (unit tests), `./gradlew functionalTest` (functional tests), `./gradlew check` (all)

---

## File Map

### Phase 1: Structural Changes

| Action | Path |
|--------|------|
| Rename file + content | `src/main/kotlin/.../client/model/ResultLike.kt` → `Outcome.kt` |
| Rename file + content | `src/test/kotlin/.../client/model/ResultLikeTest.kt` → `OutcomeTest.kt` |
| Rename file + content | `src/main/kotlin/.../extension/PublishingType.kt` → `PublishingMode.kt` |
| Rename file + content | `src/main/kotlin/.../client/MavenCentralApiClientDumbImpl.kt` → `NoOpMavenCentralApiClient.kt` |
| Rename file | `src/main/kotlin/.../task/ArtifactInfo.kt` → `DeploymentModels.kt` |
| Move + update package | `src/main/kotlin/.../client/DeploymentDropHelper.kt` → `client/recovery/DeploymentDropHelper.kt` |
| Move + update package | `src/main/kotlin/.../client/DeploymentRecoveryHandler.kt` → `client/recovery/DeploymentRecoveryHandler.kt` |
| Move + update package | `src/test/kotlin/.../client/DeploymentDropHelperTest.kt` → `client/recovery/DeploymentDropHelperTest.kt` |
| Move + update package | `src/test/kotlin/.../client/DeploymentRecoveryHandlerTest.kt` → `client/recovery/DeploymentRecoveryHandlerTest.kt` |
| Create | `src/main/kotlin/.../utils/CredentialMapping.kt` |
| Create | `src/main/kotlin/.../utils/PublicationMapping.kt` |
| Delete | `src/main/kotlin/.../utils/Utils.kt` |
| Update imports | All files referencing renamed/moved types |

### Phase 2: Outcome Pattern Consistency

| Action | Path |
|--------|------|
| Modify | `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt` — `validateInputs()` return type |
| Modify | `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt` — `validateInputs()` return type |
| Modify | `src/main/kotlin/.../client/model/Response.kt` — eliminate `@Suppress`, fix generics |

### Phase 3: Idiomatic Kotlin

| Action | Path |
|--------|------|
| Modify | `src/main/kotlin/.../MavenCentralUploaderPlugin.kt` — `buildList` in `createAggregationTasks()` |
| Modify | `src/main/kotlin/.../utils/CredentialMapping.kt` — safe calls |
| Modify | `src/main/kotlin/.../client/MavenCentralApiClientImpl.kt` — simplify `isRetriableException`, `buildQueryString` |
| Modify | `src/main/kotlin/.../utils/BundleChunker.kt` — elvis pattern |
| Modify | Multiple companion objects — remove explicit types on constants |

---

## Task 1: Rename `ResultLike` → `Outcome` (interface, implementations, extension)

**Files:**
- Rename: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/ResultLike.kt` → `Outcome.kt`
- Rename: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/ResultLikeTest.kt` → `OutcomeTest.kt`
- Update imports in all consuming files

- [ ] **Step 1: Rename `ResultLike.kt` → `Outcome.kt` and replace content**

Rename the file and replace `ResultLike` with `Outcome` everywhere in it:

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/model/Outcome.kt
package io.github.zenhelix.gradle.plugin.client.model

public sealed interface Outcome<out T, out E> {
    public fun <R> fold(onSuccess: (T) -> R, onFailure: (E) -> R): R
    public fun getOrNull(): T?
    public fun errorOrNull(): E?
    public fun <R> map(transform: (T) -> R): Outcome<R, E>
    public fun <R> flatMap(transform: (T) -> Outcome<R, @UnsafeVariance E>): Outcome<R, E>
}

public fun <T, E> Outcome<T, E>.getOrElse(default: (E) -> T): T = fold(onSuccess = { it }, onFailure = default)

public data class Success<out T>(val value: T) : Outcome<T, Nothing> {
    override fun <R> fold(onSuccess: (T) -> R, onFailure: (Nothing) -> R): R = onSuccess(value)
    override fun getOrNull(): T = value
    override fun errorOrNull(): Nothing? = null
    override fun <R> map(transform: (T) -> R): Outcome<R, Nothing> = Success(transform(value))
    override fun <R> flatMap(transform: (T) -> Outcome<R, Nothing>): Outcome<R, Nothing> = transform(value)
}

public data class Failure<out E>(val error: E) : Outcome<Nothing, E> {
    override fun <R> fold(onSuccess: (Nothing) -> R, onFailure: (E) -> R): R = onFailure(error)
    override fun getOrNull(): Nothing? = null
    override fun errorOrNull(): E = error
    override fun <R> map(transform: (Nothing) -> R): Outcome<R, E> = this
    override fun <R> flatMap(transform: (Nothing) -> Outcome<R, @UnsafeVariance E>): Outcome<R, E> = this
}
```

Delete the old `ResultLike.kt` file.

- [ ] **Step 2: Rename test file `ResultLikeTest.kt` → `OutcomeTest.kt` and update content**

```kotlin
// src/test/kotlin/io/github/zenhelix/gradle/plugin/client/model/OutcomeTest.kt
package io.github.zenhelix.gradle.plugin.client.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OutcomeTest {

    @Test
    fun `fold delegates to onSuccess for Success`() {
        val result: Outcome<Int, String> = Success(42)
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { -1 })
        assertThat(folded).isEqualTo(84)
    }

    @Test
    fun `fold delegates to onFailure for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { it.length })
        assertThat(folded).isEqualTo(5)
    }

    @Test
    fun `getOrNull returns value for Success`() {
        val result: Outcome<Int, String> = Success(42)
        assertThat(result.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `getOrNull returns null for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `errorOrNull returns null for Success`() {
        val result: Outcome<Int, String> = Success(42)
        assertThat(result.errorOrNull()).isNull()
    }

    @Test
    fun `errorOrNull returns error for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        assertThat(result.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `map transforms Success value`() {
        val result: Outcome<Int, String> = Success(42)
        val mapped = result.map { it.toString() }
        assertThat(mapped.getOrNull()).isEqualTo("42")
    }

    @Test
    fun `map preserves Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        val mapped = result.map { it.toString() }
        assertThat(mapped.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `flatMap chains Success`() {
        val result: Outcome<Int, String> = Success(42)
        val chained = result.flatMap<String> { Success(it.toString()) }
        assertThat(chained.getOrNull()).isEqualTo("42")
    }

    @Test
    fun `flatMap short-circuits on Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        val chained = result.flatMap<String> { Success(it.toString()) }
        assertThat(chained.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `getOrElse returns value for Success`() {
        val result: Outcome<Int, String> = Success(42)
        assertThat(result.getOrElse { -1 }).isEqualTo(42)
    }

    @Test
    fun `getOrElse returns default for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        assertThat(result.getOrElse { -1 }).isEqualTo(-1)
    }
}
```

- [ ] **Step 3: Update all imports of `ResultLike` → `Outcome`**

Replace `import io.github.zenhelix.gradle.plugin.client.model.ResultLike` with `import io.github.zenhelix.gradle.plugin.client.model.Outcome` and replace all type usages `ResultLike<` with `Outcome<` in these files:

- `src/main/kotlin/.../client/model/Response.kt` — line 16 (`: ResultLike<S, E?>`) and lines 51, 52-54, 58, 60-61
- `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt` — import line 11, usage lines 52, 137
- `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt` — import line 12, usage lines 51, 143, 204, 281
- `src/main/kotlin/.../utils/RetryHandler.kt` — import line 4, usage lines 22, 25, 59 (comment)
- `src/main/kotlin/.../utils/Utils.kt` — import line 5, usage line 23
- `src/main/kotlin/.../utils/BundleChunker.kt` — import line 5, usage line 14

- [ ] **Step 4: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass. No compilation errors.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: rename ResultLike to Outcome"
```

---

## Task 2: Rename `extension.PublishingType` → `PublishingMode`

**Files:**
- Rename: `src/main/kotlin/.../extension/PublishingType.kt` → `PublishingMode.kt`
- Modify: `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt`
- Modify: `src/main/kotlin/.../utils/Utils.kt` (or the split files if Task 5 runs first)
- Modify: `src/main/kotlin/.../utils/TaskExtension.kt`

- [ ] **Step 1: Rename `PublishingType.kt` → `PublishingMode.kt` and update enum name**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/PublishingMode.kt
package io.github.zenhelix.gradle.plugin.extension

public enum class PublishingMode {
    AUTOMATIC,
    USER_MANAGED
}
```

Delete the old `PublishingType.kt` file.

- [ ] **Step 2: Update `MavenCentralUploaderExtension.kt`**

Change import on line 3 from `PublishingType.AUTOMATIC` to `PublishingMode.AUTOMATIC` and update line 22:

```kotlin
import io.github.zenhelix.gradle.plugin.extension.PublishingMode.AUTOMATIC
// ...
public val publishingType: Property<PublishingMode> = objects.property<PublishingMode>().convention(AUTOMATIC)
```

- [ ] **Step 3: Update `Utils.kt` (mapModel function)**

Change the import on line 9 and the function on lines 72-75:

```kotlin
import io.github.zenhelix.gradle.plugin.extension.PublishingMode
// ...
internal fun PublishingMode.mapModel(): io.github.zenhelix.gradle.plugin.client.model.PublishingType = when (this) {
    PublishingMode.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
    PublishingMode.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
}
```

- [ ] **Step 4: Update `TaskExtension.kt`**

On line 105, the `.map { it.mapModel() }` call chains from `publishingType` which is now `Property<PublishingMode>`. The `mapModel()` extension is on `PublishingMode`, so this already works. No code change needed — just verify it compiles.

- [ ] **Step 5: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: rename extension.PublishingType to PublishingMode"
```

---

## Task 3: Rename `MavenCentralApiClientDumbImpl` → `NoOpMavenCentralApiClient`

**Files:**
- Rename: `src/main/kotlin/.../client/MavenCentralApiClientDumbImpl.kt` → `NoOpMavenCentralApiClient.kt`

- [ ] **Step 1: Rename file and class**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/NoOpMavenCentralApiClient.kt
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

    override fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType?, deploymentName: String?
    ): HttpResponseResult<UUID, String> = HttpResponseResult.Success(UUID.randomUUID())

    override fun deploymentStatus(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<DeploymentStatus, String> = HttpResponseResult.Success(
        DeploymentStatus(
            deploymentId = UUID.randomUUID(),
            deploymentName = "",
            deploymentState = DeploymentStateType.PUBLISHED,
            purls = null, errors = null,
        )
    )

    override fun publishDeployment(
        credentials: Credentials, deploymentId: UUID
    ): HttpResponseResult<Unit, String> = HttpResponseResult.Success(Unit)

    override fun dropDeployment(
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

Delete the old `MavenCentralApiClientDumbImpl.kt` file.

- [ ] **Step 2: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass. (No external references to the class name — it's `internal`.)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: rename MavenCentralApiClientDumbImpl to NoOpMavenCentralApiClient"
```

---

## Task 4: Rename `ArtifactInfo.kt` → `DeploymentModels.kt`

**Files:**
- Rename: `src/main/kotlin/.../task/ArtifactInfo.kt` → `DeploymentModels.kt`

- [ ] **Step 1: Rename the file**

Rename `ArtifactInfo.kt` to `DeploymentModels.kt`. No content changes — the package statement and class names stay the same.

- [ ] **Step 2: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: rename ArtifactInfo.kt to DeploymentModels.kt"
```

---

## Task 5: Move recovery classes to `client.recovery` package

**Files:**
- Move: `src/main/kotlin/.../client/DeploymentDropHelper.kt` → `src/main/kotlin/.../client/recovery/DeploymentDropHelper.kt`
- Move: `src/main/kotlin/.../client/DeploymentRecoveryHandler.kt` → `src/main/kotlin/.../client/recovery/DeploymentRecoveryHandler.kt`
- Move: `src/test/kotlin/.../client/DeploymentDropHelperTest.kt` → `src/test/kotlin/.../client/recovery/DeploymentDropHelperTest.kt`
- Move: `src/test/kotlin/.../client/DeploymentRecoveryHandlerTest.kt` → `src/test/kotlin/.../client/recovery/DeploymentRecoveryHandlerTest.kt`
- Update imports in consuming files

- [ ] **Step 1: Create recovery package directories**

```bash
mkdir -p src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery
mkdir -p src/test/kotlin/io/github/zenhelix/gradle/plugin/client/recovery
```

- [ ] **Step 2: Move `DeploymentDropHelper.kt` and update package**

Move the file and change package declaration from `io.github.zenhelix.gradle.plugin.client` to `io.github.zenhelix.gradle.plugin.client.recovery`. The file references `MavenCentralApiClient` which stays in `client`, so add the import:

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentDropHelper.kt
package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import java.util.UUID
import org.gradle.api.logging.Logger

/**
 * Best-effort attempt to drop a single deployment. Logs warnings on failure
 * but never throws (except for [InterruptedException] which restores the interrupt flag).
 *
 * HTTP 400 with a state-related message is treated as a normal race condition:
 * the deployment may have transitioned to PUBLISHING/PUBLISHED between our last
 * status check and the drop attempt. This is logged at lifecycle level, not as a warning.
 */
internal fun MavenCentralApiClient.tryDropDeployment(
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
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        logger.warn("Interrupted while dropping deployment {}", deploymentId)
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

Delete the old file from `client/`.

- [ ] **Step 3: Move `DeploymentRecoveryHandler.kt` and update package**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/DeploymentRecoveryHandler.kt
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

Delete the old file from `client/`.

- [ ] **Step 4: Move test files to `client/recovery/` package**

Move `DeploymentDropHelperTest.kt` and `DeploymentRecoveryHandlerTest.kt` to `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/recovery/`. Update their package declarations to `io.github.zenhelix.gradle.plugin.client.recovery` and add the import `import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient`.

For `DeploymentDropHelperTest.kt`:
```kotlin
package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials
// ... rest of imports unchanged
```

For `DeploymentRecoveryHandlerTest.kt`:
```kotlin
package io.github.zenhelix.gradle.plugin.client.recovery

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.DeploymentError
// ... rest of imports unchanged
```

Delete old test files from `client/`.

- [ ] **Step 5: Update imports in consuming files**

In `PublishBundleMavenCentralTask.kt` line 3:
```kotlin
import io.github.zenhelix.gradle.plugin.client.recovery.DeploymentRecoveryHandler
```

In `PublishSplitBundleMavenCentralTask.kt` line 3 and line 6:
```kotlin
import io.github.zenhelix.gradle.plugin.client.recovery.DeploymentRecoveryHandler
import io.github.zenhelix.gradle.plugin.client.recovery.tryDropDeployment
```

- [ ] **Step 6: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor: move DeploymentDropHelper and DeploymentRecoveryHandler to client.recovery package"
```

---

## Task 6: Split `Utils.kt` into `CredentialMapping.kt` and `PublicationMapping.kt`

**Files:**
- Create: `src/main/kotlin/.../utils/CredentialMapping.kt`
- Create: `src/main/kotlin/.../utils/PublicationMapping.kt`
- Delete: `src/main/kotlin/.../utils/Utils.kt`
- Update imports in consuming files

- [ ] **Step 1: Create `CredentialMapping.kt`**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/CredentialMapping.kt
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import org.gradle.api.Project
import org.gradle.api.provider.Provider

internal fun Project.mapCredentials(
    extension: MavenCentralUploaderExtension
): Provider<Outcome<Credentials, ValidationError>> = provider {
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

- [ ] **Step 2: Create `PublicationMapping.kt`**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/PublicationMapping.kt
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.github.zenhelix.gradle.plugin.extension.PublishingMode
import io.github.zenhelix.gradle.plugin.task.ArtifactFileInfo
import io.github.zenhelix.gradle.plugin.task.ArtifactInfo
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.GAV
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.kotlin.dsl.listProperty

internal fun MavenPublicationInternal.mapModel(
    project: Project,
    checksumTask: TaskProvider<CreateChecksumTask>
): PublicationInfo = PublicationInfo(
    projectPath = project.path,
    gav = GAV.of(this),
    publicationName = this.name,
    artifacts = project.objects.listProperty<ArtifactInfo>().apply {
        convention(project.provider {
            this@mapModel.publishableArtifacts.map {
                ArtifactInfo(artifact = ArtifactFileInfo.of(it), gav = GAV.of(this@mapModel))
            }
        })
    },
    checksumFiles = checksumTask.flatMap { it.checksumFiles }
)

internal fun PublishingMode.mapModel(): PublishingType = when (this) {
    PublishingMode.AUTOMATIC -> PublishingType.AUTOMATIC
    PublishingMode.USER_MANAGED -> PublishingType.USER_MANAGED
}
```

- [ ] **Step 3: Delete `Utils.kt`**

Remove `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/Utils.kt`.

- [ ] **Step 4: Update imports in consuming files**

In `MavenCentralUploaderPlugin.kt` line 11-12, change:
```kotlin
import io.github.zenhelix.gradle.plugin.utils.mapModel
import io.github.zenhelix.gradle.plugin.utils.mapCredentials
```
These imports don't change path-wise (same `utils` package), so they should work as-is. Verify compilation.

In `TaskExtension.kt` — same, `mapCredentials` and `mapModel` are still in `utils`. No import changes needed.

In `MavenCentralUploaderCredentialExtensionTest.kt` line 7 — `import io.github.zenhelix.gradle.plugin.utils.mapCredentials` stays the same.

- [ ] **Step 5: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: split Utils.kt into CredentialMapping.kt and PublicationMapping.kt"
```

---

## Task 7: `validateInputs()` return type → `Outcome<Unit, ValidationError>`

**Files:**
- Modify: `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt`
- Modify: `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt`

- [ ] **Step 1: Update `PublishBundleMavenCentralTask.validateInputs()`**

Change the return type and all return statements (lines 116-129):

```kotlin
private fun validateInputs(): Outcome<Unit, ValidationError> {
    if (!zipFile.isPresent) return Failure(ValidationError.MissingProperty("zipFile"))
    if (!credentials.isPresent) return Failure(ValidationError.MissingProperty("credentials"))

    val file = zipFile.asFile.get()
    if (!file.exists()) return Failure(ValidationError.InvalidFile(file.absolutePath, "Bundle file does not exist"))
    if (!file.isFile) return Failure(ValidationError.InvalidFile(file.absolutePath, "Bundle path is not a file"))
    if (file.length() == 0L) return Failure(ValidationError.InvalidFile(file.absolutePath, "Bundle file is empty"))

    val maxChecks = maxStatusChecks.get()
    if (maxChecks < 1) return Failure(ValidationError.InvalidValue("maxStatusChecks", "must be at least 1, got: $maxChecks"))

    return Success(Unit)
}
```

Update the call site in `publishBundle()` (line 73):

```kotlin
validateInputs().fold(
    onSuccess = {},
    onFailure = { throw it.toGradleException() }
)
```

- [ ] **Step 2: Update `PublishSplitBundleMavenCentralTask.validateInputs()`**

Same pattern (lines 313-325):

```kotlin
private fun validateInputs(): Outcome<Unit, ValidationError> {
    if (!bundlesDirectory.isPresent) return Failure(ValidationError.MissingProperty("bundlesDirectory"))
    if (!credentials.isPresent) return Failure(ValidationError.MissingProperty("credentials"))

    val dir = bundlesDirectory.asFile.get()
    if (!dir.exists()) return Failure(ValidationError.InvalidFile(dir.absolutePath, "Bundles directory does not exist"))
    if (!dir.isDirectory) return Failure(ValidationError.InvalidFile(dir.absolutePath, "Bundles path is not a directory"))

    val maxChecks = maxStatusChecks.get()
    if (maxChecks < 1) return Failure(ValidationError.InvalidValue("maxStatusChecks", "must be at least 1, got: $maxChecks"))

    return Success(Unit)
}
```

Update call site in `publishBundles()` (line 72):

```kotlin
validateInputs().fold(
    onSuccess = {},
    onFailure = { throw it.toGradleException() }
)
```

- [ ] **Step 3: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: validateInputs() returns Outcome instead of nullable ValidationError"
```

---

## Task 8: Eliminate `@Suppress("UNCHECKED_CAST")` in `Response.kt`

**Files:**
- Modify: `src/main/kotlin/.../client/model/Response.kt`
- Verify: `src/test/kotlin/.../client/model/HttpResponseResultTest.kt`

- [ ] **Step 1: Replace `map()` implementation (lines 50-55)**

Remove `@Suppress("UNCHECKED_CAST")` and return `HttpResponseResult<R, E>` instead of `Outcome<R, E?>`:

```kotlin
override fun <R> map(transform: (S) -> R): HttpResponseResult<R, E> where R : Any = when (this) {
    is Success         -> Success(data = transform(data), httpStatus = httpStatus, httpHeaders = httpHeaders)
    is Error           -> Error(data = data, cause = cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
    is UnexpectedError -> UnexpectedError(cause = cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
}
```

- [ ] **Step 2: Replace `flatMap()` implementation (lines 57-62)**

Remove `@Suppress("UNCHECKED_CAST")` and fix return type:

```kotlin
override fun <R> flatMap(transform: (S) -> Outcome<R, @UnsafeVariance E?>): Outcome<R, E?> where R : Any = when (this) {
    is Success         -> transform(data)
    is Error           -> Error(data = data, cause = cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
    is UnexpectedError -> UnexpectedError(cause = cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
}
```

Note: `flatMap` returns `Outcome<R, E?>` (not `HttpResponseResult`) because the transform function may return any `Outcome`. This matches the interface contract.

- [ ] **Step 3: Remove the generic `copy()` method (lines 125-132)**

The `copy()` method with `@Suppress("UNCHECKED_CAST")` default lambdas is not used by any code. `copySuccess()` and `copyError()` are the type-safe alternatives. Delete the entire `copy()` method:

```kotlin
// DELETE this entire block (lines 125-132):
// @Suppress("UNCHECKED_CAST")
// public fun <OS : Any, OE : Any> copy(
//     data: (S) -> OS = { it as OS }, error: (E?) -> OE? = { it as OE }
// ): HttpResponseResult<OS, OE> = ...
```

- [ ] **Step 4: Update the `Outcome` interface if needed**

The `Outcome` interface declares `map` returning `Outcome<R, E>`. The `HttpResponseResult.map` now returns `HttpResponseResult<R, E>` which IS an `Outcome<R, E>` (since `HttpResponseResult` implements `Outcome`). This is a covariant return type and is valid in Kotlin. Verify compilation.

- [ ] **Step 5: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass. The `HttpResponseResultTest` doesn't test `map()`/`flatMap()`/`copy()` directly, so existing tests should pass unchanged.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: eliminate @Suppress(UNCHECKED_CAST) in HttpResponseResult, remove unused copy()"
```

---

## Task 9: `buildList` in `createAggregationTasks()`

**Files:**
- Modify: `src/main/kotlin/.../MavenCentralUploaderPlugin.kt`

- [ ] **Step 1: Extract collection building into a helper function**

Replace lines 211-269 in `MavenCentralUploaderPlugin.kt`. Extract a `collectAggregationData()` function and refactor `createAggregationTasks()`:

```kotlin
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
    val allPublicationsInfo = buildList {
        // Root project publications
        val rootPublications = rootProject.findMavenPublications()
        if (!rootPublications.isNullOrEmpty()) {
            rootPublications.forEach { publication ->
                val checksumTaskName = "checksum${publication.name.capitalized()}Publication"
                val checksumTask = rootProject.tasks.named<CreateChecksumTask>(checksumTaskName)
                add(publication.mapModel(rootProject, checksumTask))
            }
        }

        // Subproject publications
        rootProject.subprojects.forEach { subproject ->
            val publications = subproject.findMavenPublications()
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
        // Root project tasks
        val rootPublications = rootProject.findMavenPublications()
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

        // Subproject tasks
        rootProject.subprojects.forEach { subproject ->
            subproject.tasks.findByName("checksumAllPublications")?.let { add(it) }

            subproject.findMavenPublications()?.forEach { publication ->
                val deps = subproject.objects.listProperty<TaskDependency>().apply {
                    publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
                }
                add(deps)
            }
        }
    }

    return allPublicationsInfo to allChecksumsAndBuildTasks
}
```

Ensure the necessary imports are present at the top of the file:
```kotlin
import org.gradle.api.tasks.TaskDependency
```

- [ ] **Step 2: Run tests to verify**

Run: `./gradlew test functionalTest`
Expected: All tests pass. The functional tests exercise the full plugin lifecycle including aggregation.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: use buildList in createAggregationTasks for immutable collection building"
```

---

## Task 10: Safe calls in `CredentialMapping.kt`

**Files:**
- Modify: `src/main/kotlin/.../utils/CredentialMapping.kt`

- [ ] **Step 1: Replace null checks with safe calls**

Update the `mapCredentials` function body (the bearer and usernamePassword branches):

```kotlin
internal fun Project.mapCredentials(
    extension: MavenCentralUploaderExtension
): Provider<Outcome<Credentials, ValidationError>> = provider {
    val creds = extension.credentials
    when {
        creds.isBearerConfigured && creds.isUsernamePasswordConfigured -> {
            Failure(ValidationError.AmbiguousCredentials(
                "Both 'bearer' and 'usernamePassword' credential blocks are configured. " +
                    "Use exactly one: credentials { bearer { ... } } or credentials { usernamePassword { ... } }"
            ))
        }
        creds.isBearerConfigured -> {
            creds.bearer.token.orNull
                ?.let { Success(Credentials.BearerTokenCredentials(it)) }
                ?: Failure(ValidationError.MissingCredential("Bearer token is not set. Configure: credentials { bearer { token.set(\"...\") } }"))
        }
        creds.isUsernamePasswordConfigured -> {
            val username = creds.usernamePassword.username.orNull
                ?: return@provider Failure(ValidationError.MissingCredential("Username is not set. Configure: credentials { usernamePassword { username.set(\"...\") } }"))
            val password = creds.usernamePassword.password.orNull
                ?: return@provider Failure(ValidationError.MissingCredential("Password is not set. Configure: credentials { usernamePassword { password.set(\"...\") } }"))
            Success(Credentials.UsernamePasswordCredentials(username, password))
        }
        else -> {
            Failure(ValidationError.NoCredentials)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass. The `MavenCentralUploaderCredentialExtensionTest` covers all branches.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: use safe calls and elvis in mapCredentials"
```

---

## Task 11: Simplify `isRetriableException` and `buildQueryString`

**Files:**
- Modify: `src/main/kotlin/.../client/MavenCentralApiClientImpl.kt`

- [ ] **Step 1: Simplify `isRetriableException` (lines 272-278)**

Replace:
```kotlin
private fun isRetriableException(e: Exception): Boolean =
    e is HttpTimeoutException || e is java.net.ConnectException ||
    e is java.net.SocketTimeoutException || e is java.io.IOException
```

- [ ] **Step 2: Simplify `buildQueryString` (lines 287-297)**

Replace:
```kotlin
private fun buildQueryString(vararg params: Pair<String, String?>): String =
    params
        .mapNotNull { (key, value) -> value?.let { "$key=${urlEncode(it)}" } }
        .joinToString("&")
        .takeIf { it.isNotEmpty() }
        ?.let { "?$it" }
        .orEmpty()
```

- [ ] **Step 3: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass. `MavenCentralApiClientImplTest` covers the client.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: simplify isRetriableException and buildQueryString with Kotlin idioms"
```

---

## Task 12: Elvis pattern in `BundleChunker`

**Files:**
- Modify: `src/main/kotlin/.../utils/BundleChunker.kt`

- [ ] **Step 1: Replace if/else with elvis (lines 28-33)**

Replace the loop body:

```kotlin
for (module in sorted) {
    chunks.firstOrNull { it.remainingCapacity(maxChunkSize) >= module.sizeBytes }
        ?.add(module)
        ?: chunks.add(MutableChunk().apply { add(module) })
}
```

- [ ] **Step 2: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass. `BundleChunkerTest` covers the chunking logic.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor: use elvis pattern in BundleChunker"
```

---

## Task 13: Remove explicit types on constants

**Files:**
- Modify: `src/main/kotlin/.../MavenCentralUploaderPlugin.kt`
- Modify: `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt`
- Modify: `src/main/kotlin/.../client/MavenCentralApiClientImpl.kt`
- Modify: `src/main/kotlin/.../client/model/MavenCentralPublishExceptions.kt` (if any)
- Modify: `src/main/kotlin/.../client/recovery/DeploymentDropHelper.kt`
- Modify: `src/main/kotlin/.../client/NoOpMavenCentralApiClient.kt`

- [ ] **Step 1: Update `MavenCentralUploaderPlugin.kt` companion (lines 298-306)**

```kotlin
public companion object {
    public const val MAVEN_CENTRAL_PORTAL_NAME = "mavenCentralPortal"
    public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID = "io.github.zenhelix.maven-central-publish"

    private val PUBLISH_ALL_PUBLICATIONS_TASK_NAME =
        "publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository"

    private const val WARN_REGISTERED_FLAG = "io.github.zenhelix.maven-central-publish.warnRegistered"
}
```

- [ ] **Step 2: Update `MavenCentralUploaderExtension.kt` companion (lines 31-35)**

```kotlin
public companion object {
    public const val MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME = "mavenCentralPortal"
    public const val DEFAULT_CENTRAL_MAVEN_PORTAL_BASE_URL = "https://central.sonatype.com"
}
```

- [ ] **Step 3: Update `UploaderSettingsExtension` companion (lines 80-84)**

```kotlin
public companion object {
    public const val DEFAULT_MAX_STATUS_CHECKS = 20
    public val DEFAULT_STATUS_CHECK_DELAY: Duration = Duration.ofSeconds(10)
    public const val DEFAULT_MAX_BUNDLE_SIZE = 256L * 1024L * 1024L // 256 MB
}
```

Note: Keep `: Duration` on `DEFAULT_STATUS_CHECK_DELAY` since the type isn't obvious from the initializer.

- [ ] **Step 4: Update `MavenCentralApiClientImpl.kt` companion (lines 323-331)**

```kotlin
private companion object {
    private const val CRLF = "\r\n"
    private const val BUNDLE_FILE_PART_NAME = "bundle"

    private const val HTTP_OK = 200
    private const val HTTP_CREATED = 201
    private const val HTTP_NO_CONTENT = 204
    private const val HTTP_TOO_MANY_REQUESTS = 429
    // ...
}
```

- [ ] **Step 5: Update `NoOpMavenCentralApiClient.kt` — `TEST_BASE_URL`**

Already done in Task 3 (changed to `internal const val TEST_BASE_URL = "https://test.invalid"`). Verify.

- [ ] **Step 6: Update `DeploymentDropHelper.kt` — `HTTP_BAD_REQUEST`**

```kotlin
private const val HTTP_BAD_REQUEST = 400
```

- [ ] **Step 7: Update `SizeExtensions.kt` constants**

```kotlin
internal const val BYTES_PER_KB = 1024L
internal const val BYTES_PER_MB = 1024L * 1024L
internal const val BYTES_PER_GB = 1024L * 1024L * 1024L
```

- [ ] **Step 8: Run tests to verify**

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "refactor: remove redundant explicit types on const val declarations"
```

---

## Task 14: Final verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew check`
Expected: All unit tests and functional tests pass.

- [ ] **Step 2: Run functional tests specifically**

Run: `./gradlew functionalTest`
Expected: All functional tests pass.

- [ ] **Step 3: Verify no `@Suppress("UNCHECKED_CAST")` remains in Response.kt**

Search: `grep -r "UNCHECKED_CAST" src/main/`
Expected: No matches (or only in unrelated files).

- [ ] **Step 4: Verify no `ResultLike` references remain**

Search: `grep -r "ResultLike" src/`
Expected: No matches.

- [ ] **Step 5: Verify no `extension.PublishingType` references remain**

Search: `grep -r "extension\.PublishingType\|extension/PublishingType" src/`
Expected: No matches.
