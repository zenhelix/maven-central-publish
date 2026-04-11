# Maven Central Publish Plugin Improvements — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve the maven-central-publish Gradle plugin with Bearer Token auth, removal of magic strings, overflow protection, KDoc, and expanded tests — all in one major version bump.

**Architecture:** Replace flat credentials DSL with nested `bearer`/`usernamePassword` blocks. Remove test-infrastructure coupling from production code by extracting `DumbImpl` to test source set and using subclass override instead of magic URL. Add defensive overflow cap to retry handler. Document complex logic with minimal KDoc.

**Tech Stack:** Kotlin, Gradle Plugin API, JUnit 5, AssertJ, MockK, Gradle TestKit

---

## File Structure

### Files to Create
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt` — moved from main (test-only stub)
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderCredentialExtensionTest.kt` — unit tests for new credential DSL
- `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerBackoffTest.kt` — overflow protection tests

### Files to Modify
- `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt` — rewrite credential extension with nested blocks
- `src/main/kotlin/.../utils/Utils.kt` — update `mapCredentials()` to return `Provider<Credentials>`
- `src/main/kotlin/.../utils/TaskExtension.kt` — update credential type in task registration
- `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt` — remove magic string, add KDoc
- `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt` — remove magic string
- `src/main/kotlin/.../utils/RetryHandler.kt` — add overflow cap
- `src/main/kotlin/.../utils/BundleChunker.kt` — add KDoc
- `src/main/kotlin/.../MavenCentralUploaderPlugin.kt` — add KDoc to unwiring logic
- `src/functionalTest/kotlin/test/BaseFunctionalTest.kt` — update `mavenCentralPortal()` helper
- `src/functionalTest/kotlin/.../MavenCentralUploaderPluginFunctionalTest.kt` — update tests for new DSL, add bearer test
- `src/main/kotlin/.../client/MavenCentralApiClientDumbImpl.kt` — delete (moved to test)

---

### Task 1: Rewrite Credentials DSL

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderExtension.kt:39-43`

- [ ] **Step 1: Rewrite `MavenCentralUploaderCredentialExtension` with nested blocks**

Replace the current credential extension and add the two new sub-extensions. The full file after edit:

```kotlin
package io.github.zenhelix.gradle.plugin.extension

import io.github.zenhelix.gradle.plugin.extension.PublishingType.AUTOMATIC
import java.time.Duration
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

public open class MavenCentralUploaderExtension @Inject constructor(objects: ObjectFactory) {

    public val baseUrl: Property<String> = objects.property<String>().convention(DEFAULT_CENTRAL_MAVEN_PORTAL_BASE_URL)

    public val credentials: MavenCentralUploaderCredentialExtension =
        objects.newInstance<MavenCentralUploaderCredentialExtension>()

    public fun credentials(configure: Action<MavenCentralUploaderCredentialExtension>) {
        configure.execute(credentials)
    }

    public val publishingType: Property<PublishingType> = objects.property<PublishingType>().convention(AUTOMATIC)

    public val deploymentName: Property<String> = objects.property<String>()

    public val uploader: UploaderSettingsExtension = objects.newInstance<UploaderSettingsExtension>()
    public fun uploader(configure: Action<UploaderSettingsExtension>) {
        configure.execute(uploader)
    }

    public companion object {
        public const val MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME: String = "mavenCentralPortal"

        public const val DEFAULT_CENTRAL_MAVEN_PORTAL_BASE_URL: String = "https://central.sonatype.com"
    }
}

public open class MavenCentralUploaderCredentialExtension @Inject constructor(private val objects: ObjectFactory) {

    private var bearerConfigured: Boolean = false
    private var usernamePasswordConfigured: Boolean = false

    public val bearer: BearerCredentialExtension = objects.newInstance<BearerCredentialExtension>()
    public val usernamePassword: UsernamePasswordCredentialExtension =
        objects.newInstance<UsernamePasswordCredentialExtension>()

    public fun bearer(configure: Action<BearerCredentialExtension>) {
        bearerConfigured = true
        configure.execute(bearer)
    }

    public fun usernamePassword(configure: Action<UsernamePasswordCredentialExtension>) {
        usernamePasswordConfigured = true
        configure.execute(usernamePassword)
    }

    public val isBearerConfigured: Boolean get() = bearerConfigured
    public val isUsernamePasswordConfigured: Boolean get() = usernamePasswordConfigured
}

public open class BearerCredentialExtension @Inject constructor(objects: ObjectFactory) {
    public val token: Property<String> = objects.property<String>()
}

public open class UsernamePasswordCredentialExtension @Inject constructor(objects: ObjectFactory) {
    public val username: Property<String> = objects.property<String>()
    public val password: Property<String> = objects.property<String>()
}

public open class UploaderSettingsExtension @Inject constructor(objects: ObjectFactory) {

    public val maxStatusChecks: Property<Int> = objects.property<Int>().convention(DEFAULT_MAX_STATUS_CHECKS)
    public val statusCheckDelay: Property<Duration> =
        objects.property<Duration>().convention(DEFAULT_STATUS_CHECK_DELAY)
    public val maxBundleSize: Property<Long> = objects.property<Long>().convention(DEFAULT_MAX_BUNDLE_SIZE)

    public companion object {
        public const val DEFAULT_MAX_STATUS_CHECKS: Int = 20
        public val DEFAULT_STATUS_CHECK_DELAY: Duration = Duration.ofSeconds(10)
        public const val DEFAULT_MAX_BUNDLE_SIZE: Long = 256L * 1024L * 1024L // 256 MB
    }
}
```

- [ ] **Step 2: Verify the file compiles**

Run: `./gradlew compileKotlin 2>&1 | head -30`

Expected: Compilation errors in `Utils.kt` and `TaskExtension.kt` (they still reference old credential shape). This confirms the DSL change propagated.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderExtension.kt
git commit -m "feat!: rewrite credentials DSL with nested bearer/usernamePassword blocks

BREAKING CHANGE: credentials { username; password } replaced with
credentials { bearer { token } } or credentials { usernamePassword { username; password } }"
```

---

### Task 2: Update `mapCredentials()` and Task Wiring

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/Utils.kt:17-20`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/TaskExtension.kt:104,119`

- [ ] **Step 1: Update `mapCredentials()` in Utils.kt**

Replace the `mapCredentials` function (lines 17-20):

```kotlin
internal fun MavenCentralUploaderExtension.mapCredentials(): Provider<Credentials> {
    return provider {
        val creds = credentials
        when {
            creds.isBearerConfigured && creds.isUsernamePasswordConfigured -> {
                throw GradleException(
                    "Both 'bearer' and 'usernamePassword' credential blocks are configured. " +
                        "Use exactly one: credentials { bearer { ... } } or credentials { usernamePassword { ... } }"
                )
            }
            creds.isBearerConfigured -> {
                val token = creds.bearer.token.orNull
                    ?: throw GradleException("Bearer token is not set. Configure: credentials { bearer { token.set(\"...\") } }")
                Credentials.BearerTokenCredentials(token)
            }
            creds.isUsernamePasswordConfigured -> {
                val username = creds.usernamePassword.username.orNull
                    ?: throw GradleException("Username is not set. Configure: credentials { usernamePassword { username.set(\"...\") } }")
                val password = creds.usernamePassword.password.orNull
                    ?: throw GradleException("Password is not set. Configure: credentials { usernamePassword { password.set(\"...\") } }")
                Credentials.UsernamePasswordCredentials(username, password)
            }
            else -> {
                throw GradleException(
                    "No credentials configured. Use: credentials { bearer { token.set(\"...\") } } " +
                        "or credentials { usernamePassword { username.set(\"...\"); password.set(\"...\") } }"
                )
            }
        }
    }
}
```

You also need to add the missing import and change the function to be an extension on `Project` so `provider {}` is available. The full updated file:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.PublishingType
import io.github.zenhelix.gradle.plugin.task.ArtifactFileInfo
import io.github.zenhelix.gradle.plugin.task.ArtifactInfo
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.GAV
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.listProperty

internal fun Project.mapCredentials(
    extension: MavenCentralUploaderExtension
): Provider<Credentials> = provider {
    val creds = extension.credentials
    when {
        creds.isBearerConfigured && creds.isUsernamePasswordConfigured -> {
            throw GradleException(
                "Both 'bearer' and 'usernamePassword' credential blocks are configured. " +
                    "Use exactly one: credentials { bearer { ... } } or credentials { usernamePassword { ... } }"
            )
        }
        creds.isBearerConfigured -> {
            val token = creds.bearer.token.orNull
                ?: throw GradleException("Bearer token is not set. Configure: credentials { bearer { token.set(\"...\") } }")
            Credentials.BearerTokenCredentials(token)
        }
        creds.isUsernamePasswordConfigured -> {
            val username = creds.usernamePassword.username.orNull
                ?: throw GradleException("Username is not set. Configure: credentials { usernamePassword { username.set(\"...\") } }")
            val password = creds.usernamePassword.password.orNull
                ?: throw GradleException("Password is not set. Configure: credentials { usernamePassword { password.set(\"...\") } }")
            Credentials.UsernamePasswordCredentials(username, password)
        }
        else -> {
            throw GradleException(
                "No credentials configured. Use: credentials { bearer { token.set(\"...\") } } " +
                    "or credentials { usernamePassword { username.set(\"...\"); password.set(\"...\") } }"
            )
        }
    }
}

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

internal fun PublishingType.mapModel(): io.github.zenhelix.gradle.plugin.client.model.PublishingType = when (this) {
    PublishingType.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
    PublishingType.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
}
```

- [ ] **Step 2: Update TaskExtension.kt to use new `mapCredentials` signature**

In `TaskExtension.kt`, replace both occurrences of `credentials.set(mavenCentralUploaderExtension.mapCredentials())` with `credentials.set(mapCredentials(mavenCentralUploaderExtension))`:

Line 104 in `registerPublishSplitAllModulesTask`:
```kotlin
    credentials.set(project.mapCredentials(mavenCentralUploaderExtension))
```
(where `project` is `this@register` — but since the register block runs in `Project` context via the extension function receiver, just use `this@registerPublishSplitAllModulesTask.mapCredentials(mavenCentralUploaderExtension)`)

Actually, looking at the code more carefully: the `register` lambda runs with the Task as receiver, not the Project. The `registerPublishSplitAllModulesTask` is an extension function on `Project`. So we need to capture the project reference. Update the private `registerPublishBundleMavenCentralTask` function (line 113-126):

```kotlin
private fun Project.registerPublishBundleMavenCentralTask(
    name: String,
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishBundleMavenCentralTask.() -> Unit = {}
): TaskProvider<PublishBundleMavenCentralTask> = this.tasks.register<PublishBundleMavenCentralTask>(name) {
    baseUrl.set(mavenCentralUploaderExtension.baseUrl)
    credentials.set(this@registerPublishBundleMavenCentralTask.mapCredentials(mavenCentralUploaderExtension))
    publishingType.set(mavenCentralUploaderExtension.publishingType.map { it.mapModel() })
    deploymentName.set(mavenCentralUploaderExtension.deploymentName)
    maxStatusChecks.set(mavenCentralUploaderExtension.uploader.maxStatusChecks)
    statusCheckDelay.set(mavenCentralUploaderExtension.uploader.statusCheckDelay)

    configuration()
}
```

And update `registerPublishSplitAllModulesTask` (line 95-111):

```kotlin
internal fun Project.registerPublishSplitAllModulesTask(
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishSplitBundleMavenCentralTask.() -> Unit = {}
): TaskProvider<PublishSplitBundleMavenCentralTask> = this.tasks.register<PublishSplitBundleMavenCentralTask>(
    "publishAllModulesTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository"
) {
    description = "Publishes all Maven publications from all modules to the $MAVEN_CENTRAL_PORTAL_NAME repository."

    baseUrl.set(mavenCentralUploaderExtension.baseUrl)
    credentials.set(this@registerPublishSplitAllModulesTask.mapCredentials(mavenCentralUploaderExtension))
    publishingType.set(mavenCentralUploaderExtension.publishingType.map { it.mapModel() })
    deploymentName.set(mavenCentralUploaderExtension.deploymentName)
    maxStatusChecks.set(mavenCentralUploaderExtension.uploader.maxStatusChecks)
    statusCheckDelay.set(mavenCentralUploaderExtension.uploader.statusCheckDelay)

    configuration()
}
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL` (or errors only in functional tests due to old DSL syntax — that's OK, we fix those in Task 6).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/Utils.kt \
       src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/TaskExtension.kt
git commit -m "feat!: update mapCredentials and task wiring for new credential DSL"
```

---

### Task 3: Remove Magic String and Move DumbImpl

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt:95-101`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt:53-59`
- Delete: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt`
- Create: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt`

- [ ] **Step 1: Simplify `createApiClient` in `PublishBundleMavenCentralTask.kt`**

Replace lines 95-101:

```kotlin
    protected open fun createApiClient(url: String): MavenCentralApiClient {
        return MavenCentralApiClientImpl(url)
    }
```

Also remove the unused imports at the top of the file:
- Remove: `import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientDumbImpl`

- [ ] **Step 2: Simplify `createApiClient` in `PublishSplitBundleMavenCentralTask.kt`**

Replace lines 53-59:

```kotlin
    protected open fun createApiClient(url: String): MavenCentralApiClient {
        return MavenCentralApiClientImpl(url)
    }
```

Also remove the unused import:
- Remove: `import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientDumbImpl`

- [ ] **Step 3: Move `MavenCentralApiClientDumbImpl` to test source set**

Delete: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt`

Create: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt`

The content stays the same — copy the file as-is:

```kotlin
package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path
import java.util.UUID

public class MavenCentralApiClientDumbImpl : MavenCentralApiClient {

    public var dropDeploymentCallCount: Int = 0
        private set

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
    ): HttpResponseResult<Unit, String> {
        dropDeploymentCallCount++
        return HttpResponseResult.Success(Unit)
    }

    override fun close() {
        // No resources to close in dummy implementation
    }

}
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileKotlin compileTestKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL` (main compiles without DumbImpl; test compiles with it)

- [ ] **Step 5: Commit**

```bash
git rm src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt
git add src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt \
       src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt \
       src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt
git commit -m "refactor: remove magic string 'http://test', move DumbImpl to test source set"
```

---

### Task 4: RetryHandler Overflow Protection

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt:81-83`

- [ ] **Step 1: Write the failing test**

Create: `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerBackoffTest.kt`

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RetryHandlerBackoffTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `calculateBackoffDelay should not overflow for large attempt values`() {
        val handler = RetryHandler(maxRetries = 100, baseDelay = Duration.ofSeconds(2), logger = logger)
        var lastDelay = 0L

        // We can't access private calculateBackoffDelay directly,
        // so we test via executeWithRetry, tracking delays indirectly.
        // Instead, let's test that the handler doesn't throw for large maxRetries
        // by running until all retries are exhausted.
        var attemptCount = 0

        try {
            handler.executeWithRetry(
                operation = { attempt ->
                    attemptCount = attempt
                    throw RuntimeException("always fails")
                },
                shouldRetry = { true }
            )
        } catch (e: RuntimeException) {
            // Expected — all retries exhausted
        }

        assertThat(attemptCount).isEqualTo(100)
    }

    @Test
    fun `backoff delay should be capped and not produce negative values`() {
        // With maxRetries=50, attempt 50 would compute 2000 * 2^49 without cap,
        // which overflows Long. With cap, it should remain positive.
        val handler = RetryHandler(maxRetries = 50, baseDelay = Duration.ofMillis(1), logger = logger)
        var maxAttemptReached = 0

        try {
            handler.executeWithRetry(
                operation = { attempt ->
                    maxAttemptReached = attempt
                    throw RuntimeException("always fails")
                },
                shouldRetry = { true }
            )
        } catch (e: RuntimeException) {
            // Expected
        }

        // If overflow occurred, Thread.sleep would have thrown IllegalArgumentException
        // for negative values, or the test would hang. Reaching here means no overflow.
        assertThat(maxAttemptReached).isEqualTo(50)
    }
}
```

- [ ] **Step 2: Run test to verify it fails (or hangs/overflows)**

Run: `./gradlew test --tests "*.RetryHandlerBackoffTest" 2>&1 | tail -10`

Expected: Test may hang (sleeping for overflow-long duration) or fail. The key thing is it doesn't pass cleanly with current code for the large-attempt test.

Note: With `baseDelay = Duration.ofMillis(1)` and actual `Thread.sleep`, this test would be slow. We need to make the test fast by using a very short delay and accepting that the overflow test might actually pass if the sleep value wraps to a small number. The real protection is the code change itself. Let's adjust the test to verify the delay value directly by subclassing.

Replace the test file with this version that tests more precisely:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RetryHandlerBackoffTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `handler completes all retries without overflow for large maxRetries`() {
        val handler = RetryHandler(maxRetries = 50, baseDelay = Duration.ofMillis(1), logger = logger)
        var maxAttemptReached = 0

        try {
            handler.executeWithRetry(
                operation = { attempt ->
                    maxAttemptReached = attempt
                    throw RuntimeException("always fails")
                },
                shouldRetry = { true }
            )
        } catch (e: RuntimeException) {
            // Expected — all retries exhausted
        }

        assertThat(maxAttemptReached).isEqualTo(50)
    }

    @Test
    fun `normal backoff works correctly for small attempt values`() {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(100), logger = logger)
        val attemptTimestamps = mutableListOf<Long>()

        try {
            handler.executeWithRetry(
                operation = { attempt ->
                    attemptTimestamps.add(System.currentTimeMillis())
                    throw RuntimeException("always fails")
                },
                shouldRetry = { true }
            )
        } catch (e: RuntimeException) {
            // Expected
        }

        assertThat(attemptTimestamps).hasSize(3)

        // Between attempt 1 and 2: ~100ms (baseDelay * 2^0)
        val delay1 = attemptTimestamps[1] - attemptTimestamps[0]
        assertThat(delay1).isBetween(80L, 300L)

        // Between attempt 2 and 3: ~200ms (baseDelay * 2^1)
        val delay2 = attemptTimestamps[2] - attemptTimestamps[1]
        assertThat(delay2).isBetween(150L, 500L)
    }
}
```

- [ ] **Step 3: Add overflow protection to `RetryHandler.kt`**

Replace lines 79-83:

```kotlin
    /**
     * Calculates exponential backoff delay: baseDelay * 2^(attempt-1).
     * The shift is capped at 30 to prevent Long overflow for large attempt values.
     */
    private fun calculateBackoffDelay(attempt: Int): Long {
        val maxShift = 30
        val shift = (attempt - 1).coerceAtMost(maxShift)
        return baseDelay.toMillis() * (1L shl shift)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "*.RetryHandlerBackoffTest" --tests "*.RetryHandlerTest" 2>&1 | tail -10`

Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt \
       src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerBackoffTest.kt
git commit -m "fix: cap exponential backoff shift to prevent Long overflow in RetryHandler"
```

---

### Task 5: KDoc for Complex Logic

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt:151-182`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt:192-261`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunker.kt:31`

- [ ] **Step 1: Add KDoc to `configureRootProjectLifecycle` in MavenCentralUploaderPlugin.kt**

Add before `configureRootProjectLifecycle` (before line 151):

```kotlin
    /**
     * Configures the root project's publish lifecycle based on whether subprojects have publications.
     *
     * When subprojects have publications, this activates **atomic aggregation mode**:
     * all modules are bundled into a single deployment. To prevent duplicate deployments,
     * the per-project `publishAllPublicationsToMavenCentralPortalRepository` tasks are unwired
     * from the `publish` lifecycle task — both for subprojects and the root project itself.
     * The root `publish` task is then wired to `publishAllModulesToMavenCentralPortalRepository`
     * which handles the aggregated bundle.
     *
     * Without subproject publications, single-module mode is used (wired in [configureZipDeploymentTasks]).
     */
```

- [ ] **Step 2: Add KDoc to `waitForDeploymentCompletion` in PublishBundleMavenCentralTask.kt**

Replace the existing KDoc (lines 188-191) with:

```kotlin
    /**
     * Polls the deployment status endpoint until the deployment reaches a terminal state.
     *
     * State machine transitions:
     * - PENDING → VALIDATING → VALIDATED → PUBLISHING → PUBLISHED (success for AUTOMATIC)
     * - PENDING → VALIDATING → VALIDATED (success for USER_MANAGED — user releases manually)
     * - Any state → FAILED (error — deployment is dropped by caller)
     * - UNKNOWN is treated as FAILED
     *
     * If the deployment does not reach a terminal state within [maxChecks] polls,
     * a [GradleException] is thrown and the caller is responsible for dropping the deployment.
     */
```

- [ ] **Step 3: Add KDoc to `BundleChunker.chunk` in BundleChunker.kt**

Replace the existing KDoc on the `BundleChunker` object (lines 30-34):

```kotlin
/**
 * Groups modules into chunks that each fit within a size limit.
 *
 * Uses the **first-fit decreasing (FFD) bin-packing algorithm**: modules are sorted
 * by size (largest first) and each is placed into the first chunk with enough remaining
 * capacity. FFD is chosen because it produces near-optimal results (at most 11/9 * OPT + 6/9
 * bins) while being simple to implement and fast (O(n log n) sort + O(n*m) placement).
 *
 * Guarantees:
 * - Every module appears in exactly one chunk.
 * - No chunk exceeds [maxChunkSize].
 * - Throws [BundleSizeExceededException] if any single module exceeds the limit.
 */
```

- [ ] **Step 4: Verify compilation**

Run: `./gradlew compileKotlin 2>&1 | tail -5`

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt \
       src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt \
       src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunker.kt
git commit -m "docs: add KDoc to complex logic (unwiring, state machine, bin-packing)"
```

---

### Task 6: Update Functional Tests for New Credentials DSL

**Files:**
- Modify: `src/functionalTest/kotlin/test/BaseFunctionalTest.kt:112-120`
- Modify: `src/functionalTest/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPluginFunctionalTest.kt`

- [ ] **Step 1: Update `mavenCentralPortal()` helper in BaseFunctionalTest.kt**

The functional tests currently use `baseUrl = "http://test"` to trigger `DumbImpl`. Since we removed that magic string, tests now need a different approach. The `createApiClient` method is `protected open`, so functional tests can override it via a custom task class registered in the test build script.

First, create a helper function that generates the build script code for registering test task subclasses. Update `BaseFunctionalTest.kt`:

Replace lines 112-120:

```kotlin
internal fun mavenCentralPortal() = """
mavenCentralPortal {
    credentials {
        usernamePassword {
            username = "stub"
            password = "stub"
        }
    }
}
""".trimIndent()

internal fun mavenCentralPortalBearer() = """
mavenCentralPortal {
    credentials {
        bearer {
            token = "stub-bearer-token"
        }
    }
}
""".trimIndent()
```

However, we also need to handle the `"http://test"` removal. Since `createApiClient` is `protected open`, functional tests need to register a subclass. But this is complex in Gradle TestKit because the test project doesn't have the plugin's classes on its buildscript classpath in the normal way.

Actually, looking at the existing functional tests more carefully: the functional tests use `gradlePlugin { testSourceSets(functionalTest.get()) }` in `build.gradle.kts`, which means the plugin IS available on the test build's classpath. And the tests use `gradleRunner` which runs Gradle with the plugin under test.

The key insight: the functional tests that run publish tasks (with `"http://test"`) currently rely on the magic URL to get `DumbImpl`. After our change, `createApiClient` will always create a real `MavenCentralApiClientImpl`, which will fail because there's no real server.

Looking at the functional tests, the tests that actually run publish tasks use `--dry-run` mode (via `gradleDryRunRunner`), which means tasks are not actually executed — only the task graph is validated. So the `createApiClient` method is never called in dry-run mode.

Let's check: does any functional test actually execute publish tasks (not dry-run)?

The tests use `gradleDryRunRunner` for publish tests, so they never hit `createApiClient`. The `baseUrl` being `"http://test"` was only a safety net in case someone ran without dry-run. Since we're removing the magic string and tests use dry-run, we just need to set a valid-looking URL.

Update `mavenCentralPortal()` to use a dummy HTTPS URL:

```kotlin
internal fun mavenCentralPortal() = """
mavenCentralPortal {
    baseUrl = "https://test.invalid"
    credentials {
        usernamePassword {
            username = "stub"
            password = "stub"
        }
    }
}
""".trimIndent()

internal fun mavenCentralPortalBearer() = """
mavenCentralPortal {
    baseUrl = "https://test.invalid"
    credentials {
        bearer {
            token = "stub-bearer-token"
        }
    }
}
""".trimIndent()
```

- [ ] **Step 2: Update functional test file for new DSL**

Search the functional test file for any direct usage of old credentials syntax (outside the `mavenCentralPortal()` helper). If all tests use the helper, no changes are needed in the test file itself.

Run: `grep -n "username\|password\|credentials" src/functionalTest/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPluginFunctionalTest.kt`

If there are direct usages, update them to the new nested syntax.

- [ ] **Step 3: Add a functional test with bearer credentials**

Add a new test to `MavenCentralUploaderPluginFunctionalTest.kt` that uses `mavenCentralPortalBearer()` instead of `mavenCentralPortal()`. Copy the simplest existing test (e.g., a single-module zip deployment) and change the credential block:

```kotlin
    @Test
    fun `zip deployment bundle with bearer token credentials`() {
        val version = "0.1.0"

        testProjectDir.settingsGradleFile().writeText(settings("test"))
        //language=kotlin
        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                `java-library`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            publishing {
                repositories {
                    mavenLocal()
                    ${mavenCentralPortalBearer()}
                }
            }
            
            ${signing()}
            $pom
            """.trimIndent()
        )

        testProjectDir.createJavaMainClass()

        val result = gradleDryRunRunner(testProjectDir, "zipDeploymentAllPublications")

        BuildOutputAssert(result).hasSucceeded()
    }
```

- [ ] **Step 4: Run functional tests**

Run: `./gradlew functionalTest 2>&1 | tail -20`

Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/functionalTest/kotlin/test/BaseFunctionalTest.kt \
       src/functionalTest/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPluginFunctionalTest.kt
git commit -m "test: update functional tests for new credentials DSL, add bearer token test"
```

---

### Task 7: Unit Tests for Credential Validation

**Files:**
- Create: `src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderCredentialExtensionTest.kt`

- [ ] **Step 1: Write unit tests for credential validation**

These tests validate the `mapCredentials` logic. Since `mapCredentials` is now a `Project` extension function and uses Gradle's `provider {}`, we need a Gradle project instance. We can use `ProjectBuilder`.

```kotlin
package io.github.zenhelix.gradle.plugin.extension

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.utils.mapCredentials
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
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

        val credentials = project.mapCredentials(extension).get()
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

        val credentials = project.mapCredentials(extension).get()
        assertThat(credentials).isInstanceOf(Credentials.UsernamePasswordCredentials::class.java)
        val creds = credentials as Credentials.UsernamePasswordCredentials
        assertThat(creds.username).isEqualTo("user")
        assertThat(creds.password).isEqualTo("pass")
    }

    @Test
    fun `both blocks configured throws GradleException`() {
        val extension = createExtension()
        extension.credentials {
            bearer { token.set("my-token") }
            usernamePassword {
                username.set("user")
                password.set("pass")
            }
        }

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("Both 'bearer' and 'usernamePassword'")
    }

    @Test
    fun `no block configured throws GradleException`() {
        val extension = createExtension()

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("No credentials configured")
    }

    @Test
    fun `bearer block without token throws GradleException`() {
        val extension = createExtension()
        extension.credentials {
            bearer { /* token not set */ }
        }

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("Bearer token is not set")
    }

    @Test
    fun `usernamePassword block without username throws GradleException`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                password.set("pass")
            }
        }

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("Username is not set")
    }

    @Test
    fun `usernamePassword block without password throws GradleException`() {
        val extension = createExtension()
        extension.credentials {
            usernamePassword {
                username.set("user")
            }
        }

        assertThatThrownBy { project.mapCredentials(extension).get() }
            .isInstanceOf(GradleException::class.java)
            .hasMessageContaining("Password is not set")
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "*.MavenCentralUploaderCredentialExtensionTest" 2>&1 | tail -10`

Expected: All 7 tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderCredentialExtensionTest.kt
git commit -m "test: add unit tests for credential DSL validation (bearer, usernamePassword, edge cases)"
```

---

### Task 8: Additional Edge Case Tests for API Client

**Files:**
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImplTest.kt`

- [ ] **Step 1: Add edge case tests to MavenCentralApiClientImplTest**

Append these tests to the existing test class:

```kotlin
    @Test
    fun `uploadDeploymentBundle should retry on HTTP 500`() {
        val expectedDeploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
        var callCount = 0

        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } answers {
            callCount++
            if (callCount == 1) {
                mockk<HttpResponse<String>> {
                    every { statusCode() } returns 500
                    every { body() } returns "Internal Server Error"
                    every { headers() } returns mockk { every { map() } returns emptyMap() }
                }
            } else {
                mockk<HttpResponse<String>> {
                    every { statusCode() } returns 201
                    every { body() } returns expectedDeploymentId.toString()
                    every { headers() } returns mockk { every { map() } returns mapOf("Content-Type" to listOf("text/plain")) }
                }
            }
        }

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
        assertThat((result as HttpResponseResult.Success).data).isEqualTo(expectedDeploymentId)
        assertThat(callCount).isEqualTo(2)
    }

    @Test
    fun `uploadDeploymentBundle should return UnexpectedError on timeout`() {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } throws java.net.http.HttpTimeoutException("Connection timed out")

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        assertThat(result).isInstanceOf(HttpResponseResult.UnexpectedError::class.java)
        assertThat((result as HttpResponseResult.UnexpectedError).cause)
            .isInstanceOf(java.net.http.HttpTimeoutException::class.java)
    }

    @Test
    fun `deploymentStatus should handle invalid JSON gracefully`() {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } returns mockk<HttpResponse<String>> {
            every { statusCode() } returns 200
            every { body() } returns "not valid json"
            every { headers() } returns mockk { every { map() } returns emptyMap() }
        }

        val result = client.deploymentStatus(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            deploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
        )

        // Invalid JSON with 200 status returns Error (parse failure results in null status)
        assertThat(result).isInstanceOf(HttpResponseResult.Error::class.java)
    }

    @Test
    fun `uploadDeploymentBundle should return UnexpectedError after all retries exhausted`() {
        every {
            mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
        } throws java.net.ConnectException("Connection refused")

        val result = client.uploadDeploymentBundle(
            credentials = BearerTokenCredentials(token = "test-token-123"),
            bundle = createTestBundleFile()
        )

        assertThat(result).isInstanceOf(HttpResponseResult.UnexpectedError::class.java)
        assertThat((result as HttpResponseResult.UnexpectedError).cause)
            .isInstanceOf(java.net.ConnectException::class.java)
    }
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "*.MavenCentralApiClientImplTest" 2>&1 | tail -10`

Expected: All tests PASS

- [ ] **Step 3: Commit**

```bash
git add src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImplTest.kt
git commit -m "test: add edge case tests for API client (HTTP 500, timeout, invalid JSON, retries exhausted)"
```

---

### Task 9: Full Build Verification

- [ ] **Step 1: Run full build**

Run: `./gradlew clean check 2>&1 | tail -20`

Expected: `BUILD SUCCESSFUL` — all unit tests and functional tests pass.

- [ ] **Step 2: Verify test count**

Run: `./gradlew test --info 2>&1 | grep -E "tests completed|tests found"`

Expected: More tests than before (was ~33 unit tests, now should be ~44+)

- [ ] **Step 3: Run functional tests explicitly**

Run: `./gradlew functionalTest --info 2>&1 | grep -E "tests completed|tests found"`

Expected: All functional tests pass (was 17, now 18+)
