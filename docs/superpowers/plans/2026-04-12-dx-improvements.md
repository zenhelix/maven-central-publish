# DX Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Improve developer experience, API quality, configurability, and test coverage of the maven-central-publish Gradle plugin.

**Architecture:** Phased approach — (1) KDoc + visibility narrowing + version catalog, (2) configurable retry/backoff + POM helpers, (3) Javadoc/Sources jar scaffolding, (4) tests throughout. Each task is self-contained and produces a working build.

**Tech Stack:** Kotlin, Gradle Plugin API, Gradle TestKit, JUnit 5, MockK, AssertJ

**Base package:** `io.github.zenhelix.gradle.plugin`  
**Source root:** `src/main/kotlin/io/github/zenhelix/gradle/plugin/`  
**Test root:** `src/test/kotlin/io/github/zenhelix/gradle/plugin/`  
**Functional test root:** `src/functionalTest/kotlin/io/github/zenhelix/gradle/plugin/`

---

## Task 1: Gradle Version Catalog

**Files:**
- Create: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`

- [ ] **Step 1: Create `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.3.20"
jackson = "2.21.2"
coroutines = "1.10.2"
assertj = "3.27.7"
mockk = "1.14.9"
bouncycastle = "1.83"

[libraries]
kotlin-gradle-plugin = { module = "org.jetbrains.kotlin:kotlin-gradle-plugin", version.ref = "kotlin" }
jackson-module-kotlin = { module = "com.fasterxml.jackson.module:jackson-module-kotlin", version.ref = "jackson" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
bouncycastle-bcpg = { module = "org.bouncycastle:bcpg-jdk18on", version.ref = "bouncycastle" }
```

- [ ] **Step 2: Refactor `build.gradle.kts` dependencies section**

Replace lines 43-72 and 129-133. In the `testing` block, replace hardcoded versions:

```kotlin
testing {
    suites {
        configureEach {
            if (this is JvmTestSuite) {
                useJUnitJupiter()
                dependencies {
                    implementation(libs.assertj.core)
                }
            }
        }

        val test by getting(JvmTestSuite::class) {
            dependencies {
                implementation(libs.mockk)
                implementation(libs.coroutines.test)
            }
        }
        val functionalTest by registering(JvmTestSuite::class) {
            dependencies {
                implementation(project())
                implementation(gradleTestKit())
                implementation(libs.bouncycastle.bcpg)
            }

            targets {
                all { testTask.configure { shouldRunAfter(test) } }
            }
        }
    }
}
```

Replace the `dependencies` block at the bottom:

```kotlin
dependencies {
    compileOnly(libs.kotlin.gradle.plugin)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.coroutines.core)
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew classes testClasses functionalTestClasses`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run all tests**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml build.gradle.kts
git commit -m "refactor: migrate dependency versions to Gradle Version Catalog"
```

---

## Task 2: Visibility Narrowing

**Files:**
- Modify: `src/main/kotlin/.../client/DefaultMavenCentralApiClient.kt`
- Modify: `src/main/kotlin/.../client/BundleChunker.kt`
- Modify: `src/main/kotlin/.../client/model/ChunkError.kt`
- Modify: `src/main/kotlin/.../client/model/MavenCentralExceptions.kt`
- Modify: `src/main/kotlin/.../utils/RetryHandler.kt`

- [ ] **Step 1: Change `DefaultMavenCentralApiClient` from `public` to `internal`**

In `client/DefaultMavenCentralApiClient.kt`, line 37:

```kotlin
// Before:
public class DefaultMavenCentralApiClient(
// After:
internal class DefaultMavenCentralApiClient(
```

- [ ] **Step 2: Change `BundleChunker`, `ModuleSize`, `Chunk` from `public` to `internal`**

In `client/BundleChunker.kt`:

Line 8:
```kotlin
// Before:
public data class ModuleSize(val name: String, val sizeBytes: Long)
// After:
internal data class ModuleSize(val name: String, val sizeBytes: Long)
```

Line 10:
```kotlin
// Before:
public data class Chunk(val moduleNames: List<String>, val totalSize: Long)
// After:
internal data class Chunk(val moduleNames: List<String>, val totalSize: Long)
```

Line 12:
```kotlin
// Before:
public object BundleChunker {
// After:
internal object BundleChunker {
```

Line 14 (the `chunk` method):
```kotlin
// Before:
    public fun chunk(modules: List<ModuleSize>, maxChunkSize: Long): Outcome<List<Chunk>, ChunkError> {
// After:
    fun chunk(modules: List<ModuleSize>, maxChunkSize: Long): Outcome<List<Chunk>, ChunkError> {
```

- [ ] **Step 3: Change `ChunkError` from `public` to `internal`**

In `client/model/ChunkError.kt`:

Line 5:
```kotlin
// Before:
public sealed class ChunkError(public val message: String) {
    public data class ModuleTooLarge(val moduleName: String, val moduleSize: Long, val maxSize: Long)
// After:
internal sealed class ChunkError(val message: String) {
    data class ModuleTooLarge(val moduleName: String, val moduleSize: Long, val maxSize: Long)
```

Line 14:
```kotlin
// Before:
public fun ChunkError.toGradleException(): MavenCentralChunkException =
// After:
internal fun ChunkError.toGradleException(): MavenCentralChunkException =
```

- [ ] **Step 4: Change `MavenCentralChunkException` from `public` to `internal`**

In `client/model/MavenCentralExceptions.kt`:

```kotlin
// Before:
public class MavenCentralChunkException(
    public val error: ChunkError,
    message: String
) : GradleException(message)
// After:
internal class MavenCentralChunkException(
    val error: ChunkError,
    message: String
) : GradleException(message)
```

- [ ] **Step 5: Change `RetryHandler` from `public` to `internal`**

In `utils/RetryHandler.kt`, line 11:

```kotlin
// Before:
public class RetryHandler(
// After:
internal class RetryHandler(
```

Also change the `executeWithRetry` method (line 22):
```kotlin
// Before:
    public suspend fun <T> executeWithRetry(
// After:
    suspend fun <T> executeWithRetry(
```

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew classes testClasses functionalTestClasses`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Run all tests**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: narrow visibility of internal API classes to internal"
```

---

## Task 3: KDoc on Public API

**Files:**
- Modify: `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt`
- Modify: `src/main/kotlin/.../extension/PublishingMode.kt`
- Modify: `src/main/kotlin/.../client/MavenCentralApiClient.kt`
- Modify: `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt`
- Modify: `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt`

- [ ] **Step 1: Add KDoc to `MavenCentralUploaderExtension` and nested classes**

In `extension/MavenCentralUploaderExtension.kt`, add KDoc before line 12:

```kotlin
/**
 * Main configuration extension for the Maven Central Publisher plugin.
 *
 * Applied via the `mavenCentralPortal { }` block in your build script:
 * ```kotlin
 * mavenCentralPortal {
 *     baseUrl = "https://central.sonatype.com"
 *     publishingType = PublishingMode.AUTOMATIC
 *     credentials {
 *         bearer { token.set("...") }
 *     }
 *     uploader {
 *         maxStatusChecks = 20
 *         statusCheckDelay = Duration.ofSeconds(10)
 *     }
 * }
 * ```
 *
 * @see PublishingMode
 * @see MavenCentralUploaderCredentialExtension
 * @see UploaderSettingsExtension
 */
public open class MavenCentralUploaderExtension @Inject constructor(objects: ObjectFactory) {
```

Add KDoc before `MavenCentralUploaderCredentialExtension` (line 38):
```kotlin
/**
 * Configures credentials for authenticating with the Maven Central Publisher API.
 *
 * Exactly one credential mode must be configured — either bearer token or username/password:
 * ```kotlin
 * credentials {
 *     bearer { token.set(providers.environmentVariable("MAVEN_CENTRAL_TOKEN")) }
 * }
 * ```
 * or:
 * ```kotlin
 * credentials {
 *     usernamePassword {
 *         username.set(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
 *         password.set(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
 *     }
 * }
 * ```
 *
 * Configuring both modes simultaneously results in a validation error at publish time.
 */
public open class MavenCentralUploaderCredentialExtension @Inject constructor(objects: ObjectFactory) {
```

Add KDoc before `BearerCredentialExtension` (line 75):
```kotlin
/**
 * Bearer token credential configuration for the Maven Central Publisher API.
 *
 * Obtain a token from the Maven Central Portal at [https://central.sonatype.com](https://central.sonatype.com).
 */
public open class BearerCredentialExtension @Inject constructor(objects: ObjectFactory) {
```

Add KDoc before `UsernamePasswordCredentialExtension` (line 79):
```kotlin
/**
 * Username/password credential configuration for the Maven Central Publisher API.
 *
 * The username and password are Base64-encoded into a bearer token at runtime.
 */
public open class UsernamePasswordCredentialExtension @Inject constructor(objects: ObjectFactory) {
```

Add KDoc before `UploaderSettingsExtension` (line 84):
```kotlin
/**
 * Advanced uploader settings controlling deployment behavior.
 *
 * ```kotlin
 * uploader {
 *     maxStatusChecks = 30          // poll up to 30 times (default: 20)
 *     statusCheckDelay = Duration.ofSeconds(15)  // wait 15s between polls (default: 10s)
 *     maxBundleSize = 512.megabytes // split threshold (default: 256 MB)
 * }
 * ```
 *
 * @property maxStatusChecks Maximum number of deployment status polls before timeout (default: 20).
 * @property statusCheckDelay Delay between consecutive deployment status polls (default: 10 seconds).
 * @property maxBundleSize Maximum bundle size in bytes before automatic splitting (default: 256 MB).
 *   Use [Int.megabytes] or [Int.gigabytes] extension properties for readable values.
 */
public open class UploaderSettingsExtension @Inject constructor(objects: ObjectFactory) {
```

- [ ] **Step 2: Add KDoc to `PublishingMode`**

In `extension/PublishingMode.kt`, replace the entire file:

```kotlin
package io.github.zenhelix.gradle.plugin.extension

/**
 * Controls how deployments are published after validation.
 */
public enum class PublishingMode {
    /**
     * Deployment is published automatically after passing validation.
     * This is the default mode.
     */
    AUTOMATIC,

    /**
     * Deployment is validated but not published automatically.
     * The user must manually publish via the Maven Central Portal UI.
     */
    USER_MANAGED
}
```

- [ ] **Step 3: Add KDoc to `MavenCentralApiClient`**

In `client/MavenCentralApiClient.kt`, replace the entire file:

```kotlin
package io.github.zenhelix.gradle.plugin.client

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentId
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.nio.file.Path

/**
 * Client interface for the Maven Central Publisher API.
 *
 * Provides operations for the full deployment lifecycle: upload, status polling, publish, and drop.
 *
 * @see <a href="https://central.sonatype.org/publish/publish-portal-api/">Maven Central Publisher API</a>
 */
public interface MavenCentralApiClient : AutoCloseable {

    /**
     * Uploads a deployment bundle (ZIP) to Maven Central.
     *
     * @param credentials authentication credentials
     * @param bundle path to the ZIP bundle file
     * @param publishingType optional publishing mode override (defaults to server setting)
     * @param deploymentName optional human-readable name for the deployment
     * @return [HttpResponseResult] containing the [DeploymentId] on success
     */
    public suspend fun uploadDeploymentBundle(
        credentials: Credentials, bundle: Path, publishingType: PublishingType? = null, deploymentName: String? = null
    ): HttpResponseResult<DeploymentId, String>

    /**
     * Retrieves the current status of a deployment.
     *
     * @param credentials authentication credentials
     * @param deploymentId the deployment to check
     * @return [HttpResponseResult] containing the [DeploymentStatus] on success
     */
    public suspend fun deploymentStatus(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<DeploymentStatus, String>

    /**
     * Publishes a validated deployment, making it available on Maven Central.
     *
     * Only applicable to deployments in `VALIDATED` state (typically with [PublishingType.USER_MANAGED]).
     *
     * @param credentials authentication credentials
     * @param deploymentId the deployment to publish
     */
    public suspend fun publishDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String>

    /**
     * Drops (deletes) a deployment, removing it from Maven Central Portal.
     *
     * Only applicable to deployments in a droppable state (PENDING, VALIDATING, VALIDATED, FAILED).
     *
     * @param credentials authentication credentials
     * @param deploymentId the deployment to drop
     */
    public suspend fun dropDeployment(credentials: Credentials, deploymentId: DeploymentId): HttpResponseResult<Unit, String>

}
```

- [ ] **Step 4: Add KDoc to `PublishBundleMavenCentralTask`**

In `task/PublishBundleMavenCentralTask.kt`, add KDoc before the class declaration (before line 35):

```kotlin
/**
 * Publishes a single deployment bundle to Maven Central via the Publisher API.
 *
 * This task uploads a ZIP bundle, polls for deployment status, and handles
 * failures with automatic recovery (dropping failed deployments).
 *
 * Registered automatically by the plugin for each publication and for all publications combined.
 */
@DisableCachingByDefault(because = "Not worth caching - publishes to external service")
public abstract class PublishBundleMavenCentralTask @Inject constructor(
```

- [ ] **Step 5: Add KDoc to `PublishSplitBundleMavenCentralTask`**

In `task/PublishSplitBundleMavenCentralTask.kt`, add KDoc before the class declaration (before line 36):

```kotlin
/**
 * Publishes split deployment bundles to Maven Central via the Publisher API.
 *
 * Used when the total bundle size exceeds [UploaderSettingsExtension.maxBundleSize].
 * Uploads multiple chunks, validates all deployments, and publishes them atomically.
 * If any chunk fails, all previously uploaded chunks are dropped for cleanup.
 *
 * For multi-chunk deployments, the publishing type is automatically switched to
 * [PublishingType.USER_MANAGED] to ensure atomic validation before publishing.
 */
@DisableCachingByDefault(because = "Not worth caching - publishes to external service")
public abstract class PublishSplitBundleMavenCentralTask : DefaultTask() {
```

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs: add KDoc to all public API classes and interfaces"
```

---

## Task 4: Configurable Retry/Backoff — Extension & Task Properties

**Files:**
- Modify: `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt` (UploaderSettingsExtension)
- Modify: `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt`
- Modify: `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt`
- Modify: `src/main/kotlin/.../utils/TaskRegistration.kt`

- [ ] **Step 1: Add retry properties to `UploaderSettingsExtension`**

In `extension/MavenCentralUploaderExtension.kt`, add 4 new properties to `UploaderSettingsExtension` after `maxBundleSize` (after line 89):

```kotlin
    public val requestTimeout: Property<Duration> = objects.property<Duration>().convention(DEFAULT_REQUEST_TIMEOUT)
    public val connectTimeout: Property<Duration> = objects.property<Duration>().convention(DEFAULT_CONNECT_TIMEOUT)
    public val maxRetries: Property<Int> = objects.property<Int>().convention(DEFAULT_MAX_RETRIES)
    public val retryBaseDelay: Property<Duration> = objects.property<Duration>().convention(DEFAULT_RETRY_BASE_DELAY)
```

Add constants in the companion object (after line 94):

```kotlin
        public val DEFAULT_REQUEST_TIMEOUT: Duration = Duration.ofMinutes(5)
        public val DEFAULT_CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        public const val DEFAULT_MAX_RETRIES: Int = 3
        public val DEFAULT_RETRY_BASE_DELAY: Duration = Duration.ofSeconds(2)
```

- [ ] **Step 2: Add retry task properties to `PublishBundleMavenCentralTask`**

In `task/PublishBundleMavenCentralTask.kt`, add 4 new abstract properties after `statusCheckDelay` (after line 62):

```kotlin
    @get:Input
    public abstract val requestTimeout: Property<Duration>

    @get:Input
    public abstract val connectTimeout: Property<Duration>

    @get:Input
    public abstract val maxRetries: Property<Int>

    @get:Input
    public abstract val retryBaseDelay: Property<Duration>
```

Update the `init` block to add conventions for these new properties:

```kotlin
    init {
        group = PUBLISH_TASK_GROUP
        description = "Publishes a deployment bundle to Maven Central Portal"

        publishingType.convention(PublishingType.AUTOMATIC)
        maxStatusChecks.convention(20)
        statusCheckDelay.convention(Duration.ofSeconds(10))
        requestTimeout.convention(Duration.ofMinutes(5))
        connectTimeout.convention(Duration.ofSeconds(30))
        maxRetries.convention(3)
        retryBaseDelay.convention(Duration.ofSeconds(2))
    }
```

Update `createApiClient` to pass retry properties (replace line 64):

```kotlin
    protected open fun createApiClient(url: String): MavenCentralApiClient = createDefaultApiClient(
        baseUrl = url,
        requestTimeout = requestTimeout.get(),
        connectTimeout = connectTimeout.get(),
        maxRetries = maxRetries.get(),
        retryBaseDelay = retryBaseDelay.get()
    )
```

- [ ] **Step 3: Update `createApiClient` factory function to accept parameters**

In `client/DefaultMavenCentralApiClient.kt` (or wherever `createApiClient` is defined), find the `createApiClient` internal function and update it to accept parameters. Check for a top-level `createApiClient` function — if it doesn't exist as a standalone function, it may be imported directly. Search for the function:

The `createApiClient` is imported as `import io.github.zenhelix.gradle.plugin.client.createApiClient as createDefaultApiClient`. Find this function and update it:

```kotlin
internal fun createApiClient(
    baseUrl: String,
    requestTimeout: Duration = Duration.ofMinutes(5),
    connectTimeout: Duration = Duration.ofSeconds(30),
    maxRetries: Int = 3,
    retryBaseDelay: Duration = Duration.ofSeconds(2)
): MavenCentralApiClient = DefaultMavenCentralApiClient(
    baseUrl = baseUrl,
    requestTimeout = requestTimeout,
    connectTimeout = connectTimeout,
    maxRetries = maxRetries,
    retryDelay = retryBaseDelay
)
```

- [ ] **Step 4: Add same retry properties to `PublishSplitBundleMavenCentralTask`**

In `task/PublishSplitBundleMavenCentralTask.kt`, add the same 4 properties after `statusCheckDelay` (after line 61), same conventions in `init`, and update `createApiClient` the same way as in Step 2.

- [ ] **Step 5: Wire extension properties to task properties in `TaskRegistration.kt`**

In `utils/TaskRegistration.kt`, update `registerPublishBundleMavenCentralTask` (the private function near the bottom) to wire the new properties:

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
    requestTimeout.set(mavenCentralUploaderExtension.uploader.requestTimeout)
    connectTimeout.set(mavenCentralUploaderExtension.uploader.connectTimeout)
    maxRetries.set(mavenCentralUploaderExtension.uploader.maxRetries)
    retryBaseDelay.set(mavenCentralUploaderExtension.uploader.retryBaseDelay)

    configuration()
}
```

Also update `registerPublishSplitAllModulesTask` the same way — add the 4 new property wiring lines.

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew classes testClasses`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Run all tests**

Run: `./gradlew check`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: add configurable retry/backoff parameters to uploader DSL"
```

---

## Task 5: POM Extension Classes

**Files:**
- Create: `src/main/kotlin/.../extension/PomExtension.kt`

- [ ] **Step 1: Write tests for POM helpers**

Create `src/test/kotlin/io/github/zenhelix/gradle/plugin/extension/PomExtensionTest.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.extension

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PomExtensionTest {

    @Test
    fun `apache2 license preset returns correct values`() {
        val license = LicensePresets.apache2()
        assertThat(license.name).isEqualTo("The Apache License, Version 2.0")
        assertThat(license.url).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0.txt")
        assertThat(license.distribution).isEqualTo("repo")
    }

    @Test
    fun `mit license preset returns correct values`() {
        val license = LicensePresets.mit()
        assertThat(license.name).isEqualTo("MIT License")
        assertThat(license.url).isEqualTo("https://opensource.org/licenses/MIT")
        assertThat(license.distribution).isEqualTo("repo")
    }

    @Test
    fun `bsd2 license preset returns correct values`() {
        val license = LicensePresets.bsd2()
        assertThat(license.name).isEqualTo("BSD 2-Clause License")
        assertThat(license.url).isEqualTo("https://opensource.org/licenses/BSD-2-Clause")
        assertThat(license.distribution).isEqualTo("repo")
    }

    @Test
    fun `bsd3 license preset returns correct values`() {
        val license = LicensePresets.bsd3()
        assertThat(license.name).isEqualTo("BSD 3-Clause License")
        assertThat(license.url).isEqualTo("https://opensource.org/licenses/BSD-3-Clause")
        assertThat(license.distribution).isEqualTo("repo")
    }

    @Test
    fun `fromGithub generates correct SCM URLs`() {
        val scm = ScmDefaults.fromGithub("zenhelix", "maven-central-publish")
        assertThat(scm.connection).isEqualTo("scm:git:git://github.com/zenhelix/maven-central-publish.git")
        assertThat(scm.developerConnection).isEqualTo("scm:git:ssh://github.com/zenhelix/maven-central-publish.git")
        assertThat(scm.url).isEqualTo("https://github.com/zenhelix/maven-central-publish")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*.PomExtensionTest"`
Expected: FAIL — classes not found

- [ ] **Step 3: Create `PomExtension.kt`**

Create `src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/PomExtension.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.extension

import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

/**
 * POM metadata defaults for Maven Central publications.
 *
 * Values configured here are applied as defaults to all [org.gradle.api.publish.maven.MavenPublication]
 * instances. Standard Gradle `pom { }` blocks take precedence over these defaults.
 *
 * ```kotlin
 * mavenCentralPortal {
 *     pom {
 *         name = "My Library"
 *         description = "A useful library"
 *         url = "https://github.com/org/repo"
 *         license { apache2() }
 *         developer {
 *             id = "user"
 *             name = "User Name"
 *         }
 *         scm { fromGithub("org", "repo") }
 *     }
 * }
 * ```
 */
public open class PomExtension @Inject constructor(private val objects: ObjectFactory) {

    public val name: Property<String> = objects.property<String>()
    public val description: Property<String> = objects.property<String>()
    public val url: Property<String> = objects.property<String>()
    public val inceptionYear: Property<String> = objects.property<String>()

    public val licenses: ListProperty<PomLicenseData> = objects.listProperty<PomLicenseData>()
    public val developers: ListProperty<PomDeveloperData> = objects.listProperty<PomDeveloperData>()
    public val scm: PomScmExtension = objects.newInstance<PomScmExtension>()

    public fun license(configure: Action<PomLicenseBuilder>) {
        val builder = objects.newInstance<PomLicenseBuilder>()
        configure.execute(builder)
        licenses.add(builder.build())
    }

    public fun developer(configure: Action<PomDeveloperBuilder>) {
        val builder = objects.newInstance<PomDeveloperBuilder>()
        configure.execute(builder)
        developers.add(builder.build())
    }

    public fun scm(configure: Action<PomScmExtension>) {
        configure.execute(scm)
    }
}

/**
 * Builder for POM license entries. Supports preset licenses via [apache2], [mit], [bsd2], [bsd3].
 */
public open class PomLicenseBuilder @Inject constructor(objects: ObjectFactory) {
    public val name: Property<String> = objects.property<String>()
    public val url: Property<String> = objects.property<String>()
    public val distribution: Property<String> = objects.property<String>().convention("repo")

    public fun apache2() {
        val preset = LicensePresets.apache2()
        name.set(preset.name)
        url.set(preset.url)
        distribution.set(preset.distribution)
    }

    public fun mit() {
        val preset = LicensePresets.mit()
        name.set(preset.name)
        url.set(preset.url)
        distribution.set(preset.distribution)
    }

    public fun bsd2() {
        val preset = LicensePresets.bsd2()
        name.set(preset.name)
        url.set(preset.url)
        distribution.set(preset.distribution)
    }

    public fun bsd3() {
        val preset = LicensePresets.bsd3()
        name.set(preset.name)
        url.set(preset.url)
        distribution.set(preset.distribution)
    }

    internal fun build(): PomLicenseData = PomLicenseData(
        name = name.get(),
        url = url.get(),
        distribution = distribution.orNull
    )
}

/**
 * Builder for POM developer entries.
 */
public open class PomDeveloperBuilder @Inject constructor(objects: ObjectFactory) {
    public val id: Property<String> = objects.property<String>()
    public val name: Property<String> = objects.property<String>()
    public val email: Property<String> = objects.property<String>()
    public val url: Property<String> = objects.property<String>()

    internal fun build(): PomDeveloperData = PomDeveloperData(
        id = id.orNull,
        name = name.orNull,
        email = email.orNull,
        url = url.orNull
    )
}

/**
 * SCM configuration with shortcuts for common hosting providers.
 */
public open class PomScmExtension @Inject constructor(objects: ObjectFactory) {
    public val connection: Property<String> = objects.property<String>()
    public val developerConnection: Property<String> = objects.property<String>()
    public val url: Property<String> = objects.property<String>()

    /**
     * Configures SCM URLs for a GitHub repository.
     *
     * @param owner GitHub organization or user
     * @param repo repository name
     */
    public fun fromGithub(owner: String, repo: String) {
        val defaults = ScmDefaults.fromGithub(owner, repo)
        connection.set(defaults.connection)
        developerConnection.set(defaults.developerConnection)
        url.set(defaults.url)
    }
}

/** Immutable snapshot of license data. */
public data class PomLicenseData(
    val name: String,
    val url: String,
    val distribution: String?
) : java.io.Serializable

/** Immutable snapshot of developer data. */
public data class PomDeveloperData(
    val id: String?,
    val name: String?,
    val email: String?,
    val url: String?
) : java.io.Serializable

/** Immutable snapshot of SCM data. */
public data class PomScmData(
    val connection: String,
    val developerConnection: String,
    val url: String
)

/** License presets for common open-source licenses. */
public object LicensePresets {
    public fun apache2(): PomLicenseData = PomLicenseData(
        name = "The Apache License, Version 2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt",
        distribution = "repo"
    )

    public fun mit(): PomLicenseData = PomLicenseData(
        name = "MIT License",
        url = "https://opensource.org/licenses/MIT",
        distribution = "repo"
    )

    public fun bsd2(): PomLicenseData = PomLicenseData(
        name = "BSD 2-Clause License",
        url = "https://opensource.org/licenses/BSD-2-Clause",
        distribution = "repo"
    )

    public fun bsd3(): PomLicenseData = PomLicenseData(
        name = "BSD 3-Clause License",
        url = "https://opensource.org/licenses/BSD-3-Clause",
        distribution = "repo"
    )
}

/** SCM URL generators for common hosting providers. */
public object ScmDefaults {
    public fun fromGithub(owner: String, repo: String): PomScmData = PomScmData(
        connection = "scm:git:git://github.com/$owner/$repo.git",
        developerConnection = "scm:git:ssh://github.com/$owner/$repo.git",
        url = "https://github.com/$owner/$repo"
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*.PomExtensionTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add POM extension classes with license presets and SCM shortcuts"
```

---

## Task 6: Wire POM Extension to Plugin

**Files:**
- Modify: `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt`
- Modify: `src/main/kotlin/.../MavenCentralUploaderPlugin.kt`
- Modify: `src/main/kotlin/.../configurator/ZipDeploymentConfigurator.kt`

- [ ] **Step 1: Add `pom` property to `MavenCentralUploaderExtension`**

In `extension/MavenCentralUploaderExtension.kt`, add after the `uploader` property (after line 29):

```kotlin
    public val pom: PomExtension = objects.newInstance<PomExtension>()
    public fun pom(configure: Action<PomExtension>) {
        configure.execute(pom)
    }
```

- [ ] **Step 2: Apply POM defaults to publications in `ZipDeploymentConfigurator`**

In `configurator/ZipDeploymentConfigurator.kt`, add a POM configuration step inside `configureZipDeploymentTasks`, after `val publications = ...` (after line 27). Add an import for `PomExtension` and `MavenPom`, then insert:

```kotlin
        applyPomDefaults(project, extension.pom, publications)
```

Add the helper method to the `ZipDeploymentConfigurator` object:

```kotlin
    private fun applyPomDefaults(
        project: Project,
        pom: PomExtension,
        publications: NamedDomainObjectCollection<MavenPublicationInternal>
    ) {
        publications.configureEach {
            this.pom { mavenPom ->
                pom.name.orNull?.let { mavenPom.name.convention(it) }
                pom.description.orNull?.let { mavenPom.description.convention(it) }
                pom.url.orNull?.let { mavenPom.url.convention(it) }
                pom.inceptionYear.orNull?.let { mavenPom.inceptionYear.convention(it) }

                val licenses = pom.licenses.getOrElse(emptyList())
                if (licenses.isNotEmpty()) {
                    mavenPom.licenses { licenseSpec ->
                        licenses.forEach { licenseData ->
                            licenseSpec.license { license ->
                                license.name.set(licenseData.name)
                                license.url.set(licenseData.url)
                                licenseData.distribution?.let { license.distribution.set(it) }
                            }
                        }
                    }
                }

                val developers = pom.developers.getOrElse(emptyList())
                if (developers.isNotEmpty()) {
                    mavenPom.developers { devSpec ->
                        developers.forEach { devData ->
                            devSpec.developer { dev ->
                                devData.id?.let { dev.id.set(it) }
                                devData.name?.let { dev.name.set(it) }
                                devData.email?.let { dev.email.set(it) }
                                devData.url?.let { dev.url.set(it) }
                            }
                        }
                    }
                }

                val scm = pom.scm
                if (scm.connection.isPresent || scm.developerConnection.isPresent || scm.url.isPresent) {
                    mavenPom.scm { scmSpec ->
                        scm.connection.orNull?.let { scmSpec.connection.set(it) }
                        scm.developerConnection.orNull?.let { scmSpec.developerConnection.set(it) }
                        scm.url.orNull?.let { scmSpec.url.set(it) }
                    }
                }
            }
        }
    }
```

Note: The method signature uses `NamedDomainObjectCollection<MavenPublicationInternal>`. Add the necessary import:
```kotlin
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: wire POM extension to plugin, apply defaults to publications"
```

---

## Task 7: POM Validation on Publish

**Files:**
- Create: `src/main/kotlin/.../utils/PomValidation.kt`
- Modify: `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt`
- Modify: `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt`
- Test: `src/test/kotlin/.../utils/PomValidationTest.kt`

- [ ] **Step 1: Write tests for POM validation**

Create `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/PomValidationTest.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.Failure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PomValidationTest {

    @Test
    fun `valid POM passes validation`() {
        val result = validatePomFields(
            name = "lib", description = "desc", url = "https://example.com",
            hasLicenses = true, hasDevelopers = true, hasScm = true
        )
        assertThat(result).isInstanceOf(Success::class.java)
    }

    @Test
    fun `missing name fails validation`() {
        val result = validatePomFields(
            name = null, description = "desc", url = "https://example.com",
            hasLicenses = true, hasDevelopers = true, hasScm = true
        )
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error.missingFields).contains("name")
    }

    @Test
    fun `multiple missing fields reported together`() {
        val result = validatePomFields(
            name = null, description = null, url = null,
            hasLicenses = false, hasDevelopers = false, hasScm = false
        )
        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error.missingFields).containsExactlyInAnyOrder(
            "name", "description", "url", "licenses", "developers", "scm"
        )
    }

    @Test
    fun `empty string name fails validation`() {
        val result = validatePomFields(
            name = "", description = "desc", url = "https://example.com",
            hasLicenses = true, hasDevelopers = true, hasScm = true
        )
        assertThat(result).isInstanceOf(Failure::class.java)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "*.PomValidationTest"`
Expected: FAIL

- [ ] **Step 3: Create `PomValidation.kt`**

Create `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/PomValidation.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success

internal data class PomValidationError(
    val publicationName: String,
    val missingFields: List<String>
) {
    fun toMessage(): String =
        "Publication '$publicationName' is missing required POM fields for Maven Central: ${missingFields.joinToString(", ")}. " +
            "Configure them via mavenCentralPortal { pom { ... } } or the standard publishing { publications { ... { pom { ... } } } } block."
}

internal fun validatePomFields(
    name: String?,
    description: String?,
    url: String?,
    hasLicenses: Boolean,
    hasDevelopers: Boolean,
    hasScm: Boolean
): Outcome<Unit, PomValidationError> {
    val missing = buildList {
        if (name.isNullOrBlank()) add("name")
        if (description.isNullOrBlank()) add("description")
        if (url.isNullOrBlank()) add("url")
        if (!hasLicenses) add("licenses")
        if (!hasDevelopers) add("developers")
        if (!hasScm) add("scm")
    }

    return if (missing.isEmpty()) {
        Success(Unit)
    } else {
        Failure(PomValidationError(publicationName = "", missingFields = missing))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "*.PomValidationTest"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add POM field validation for Maven Central requirements"
```

---

## Task 8: Javadoc/Sources Jar Scaffolding

**Files:**
- Create: `src/main/kotlin/.../configurator/JarConfigurator.kt`
- Modify: `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt`
- Modify: `src/main/kotlin/.../MavenCentralUploaderPlugin.kt`

- [ ] **Step 1: Add `autoConfigureJars` property to extension**

In `extension/MavenCentralUploaderExtension.kt`, add to `MavenCentralUploaderExtension` after the `pom` property:

```kotlin
    /**
     * When `true` (default), automatically registers `javadocJar` and `sourcesJar` tasks
     * and attaches them to all Maven publications. Existing tasks with the same name are not overwritten.
     *
     * If the Dokka plugin is applied, its output is used for the javadoc jar content.
     */
    public val autoConfigureJars: Property<Boolean> = objects.property<Boolean>().convention(true)
```

- [ ] **Step 2: Create `JarConfigurator.kt`**

Create `src/main/kotlin/io/github/zenhelix/gradle/plugin/configurator/JarConfigurator.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.configurator

import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.PublishingExtension
import org.gradle.kotlin.dsl.findByType

internal object JarConfigurator {

    fun configure(project: Project) {
        project.afterEvaluate {
            configureJars(this)
        }
    }

    private fun configureJars(project: Project) {
        val javadocJarTask = registerJavadocJarIfAbsent(project)
        val sourcesJarTask = registerSourcesJarIfAbsent(project)

        if (javadocJarTask != null || sourcesJarTask != null) {
            project.extensions.findByType<PublishingExtension>()
                ?.publications
                ?.withType<MavenPublication>()
                ?.configureEach {
                    javadocJarTask?.let { artifact(it) }
                    sourcesJarTask?.let { artifact(it) }
                }
        }
    }

    private fun registerJavadocJarIfAbsent(project: Project): Any? {
        if (project.tasks.findByName("javadocJar") != null) return null

        val dokkaApplied = project.plugins.hasPlugin("org.jetbrains.dokka")

        return project.tasks.register<Jar>("javadocJar") {
            archiveClassifier.set("javadoc")

            if (dokkaApplied) {
                val dokkaTask = project.tasks.findByName("dokkaHtml")
                    ?: project.tasks.findByName("dokkaJavadoc")
                if (dokkaTask != null) {
                    dependsOn(dokkaTask)
                    from(dokkaTask.outputs)
                }
            }
        }
    }

    private fun registerSourcesJarIfAbsent(project: Project): Any? {
        if (project.tasks.findByName("sourcesJar") != null) return null

        return project.tasks.register<Jar>("sourcesJar") {
            archiveClassifier.set("sources")

            val kotlinExtension = project.extensions.findByName("kotlin")
            if (kotlinExtension != null) {
                // KMP or JVM Kotlin project
                try {
                    val sourceSets = kotlinExtension.javaClass.getMethod("getSourceSets").invoke(kotlinExtension)
                    val commonMain = sourceSets?.javaClass?.getMethod("findByName", String::class.java)
                        ?.invoke(sourceSets, "commonMain")
                    val main = sourceSets?.javaClass?.getMethod("findByName", String::class.java)
                        ?.invoke(sourceSets, "main")
                    val sourceSet = commonMain ?: main
                    if (sourceSet != null) {
                        val kotlin = sourceSet.javaClass.getMethod("getKotlin").invoke(sourceSet)
                        if (kotlin is org.gradle.api.file.SourceDirectorySet) {
                            from(kotlin)
                        }
                    }
                } catch (_: Exception) {
                    // Fallback: use Java source sets
                    project.convention.findPlugin(org.gradle.api.plugins.JavaPluginConvention::class.java)
                        ?.sourceSets?.findByName("main")?.allSource?.let { from(it) }
                }
            } else {
                // Pure Java project
                project.convention.findPlugin(org.gradle.api.plugins.JavaPluginConvention::class.java)
                    ?.sourceSets?.findByName("main")?.allSource?.let { from(it) }
            }
        }
    }
}
```

- [ ] **Step 3: Wire `JarConfigurator` in plugin `apply()`**

In `MavenCentralUploaderPlugin.kt`, add after `ZipDeploymentConfigurator.configure(target, extension)` (after line 41):

```kotlin
        if (extension.autoConfigureJars.get()) {
            JarConfigurator.configure(target)
        }
```

Wait — `autoConfigureJars` is a `Property<Boolean>` and at plugin apply time the value may not be resolved yet. We need to defer:

```kotlin
        target.afterEvaluate {
            if (extension.autoConfigureJars.get()) {
                JarConfigurator.configure(this)
            }
        }
```

But `JarConfigurator.configure` already uses `afterEvaluate`, so we need to restructure. Instead, pass the property into `JarConfigurator`:

In `MavenCentralUploaderPlugin.kt`:
```kotlin
        JarConfigurator.configure(target, extension.autoConfigureJars)
```

Update `JarConfigurator.configure`:
```kotlin
    fun configure(project: Project, autoConfigureJars: Property<Boolean>) {
        project.afterEvaluate {
            if (autoConfigureJars.get()) {
                configureJars(this)
            }
        }
    }
```

Add import: `import org.gradle.api.provider.Property`

- [ ] **Step 4: Verify build compiles**

Run: `./gradlew classes`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add Javadoc/Sources jar scaffolding with autoConfigureJars option"
```

---

## Task 9: Functional Tests — POM Defaults

**Files:**
- Modify: `src/functionalTest/kotlin/.../MavenCentralUploaderPluginFunctionalTest.kt`

- [ ] **Step 1: Add functional test for POM defaults applied to publication**

Add to the existing functional test class `MavenCentralUploaderPluginFunctionalTest.kt`. Follow the existing patterns in the file — use `BaseFunctionalTest` utilities and `GradleRunner`. Add a test that:

1. Sets up a single-module project with the plugin
2. Configures `mavenCentralPortal { pom { name = "TestLib"; description = "A test"; url = "https://example.com"; license { apache2() }; developer { id = "dev" }; scm { fromGithub("org", "repo") } } }`
3. Runs `./gradlew generatePomFileForMavenPublication` (dry run or actual)
4. Reads the generated POM XML and asserts the fields are present

The exact test code depends on the existing test utilities — follow the pattern of existing tests in the file.

- [ ] **Step 2: Add functional test for POM defaults can be overridden**

Add a test that:
1. Configures both `mavenCentralPortal { pom { name = "Default" } }` and standard `publishing { publications { create<MavenPublication>("maven") { pom { name.set("Override") } } } }`
2. Verifies the generated POM has `name = "Override"`

- [ ] **Step 3: Add functional test for jar scaffolding**

Add a test that:
1. Sets up a project with `mavenCentralPortal { autoConfigureJars = true }`
2. Runs `./gradlew tasks --all` (dry run)
3. Asserts `javadocJar` and `sourcesJar` tasks are registered

- [ ] **Step 4: Add functional test for jar scaffolding disabled**

Add a test that:
1. Sets up a project with `mavenCentralPortal { autoConfigureJars = false }`
2. Verifies `javadocJar` is NOT registered by the plugin (note: it may still be registered by `java { withJavadocJar() }` in the build — test should check the right thing)

- [ ] **Step 5: Run functional tests**

Run: `./gradlew functionalTest`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "test: add functional tests for POM defaults and jar scaffolding"
```

---

## Task 10: Unit Tests — CreateChecksumTask

**Files:**
- Create: `src/test/kotlin/.../task/CreateChecksumTaskTest.kt`

- [ ] **Step 1: Write tests for checksum generation**

Create `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/CreateChecksumTaskTest.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.task

import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class CreateChecksumTaskTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `checksum task generates files for each algorithm`() {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        project.plugins.apply("maven-publish")
        project.plugins.apply("signing")
        project.plugins.apply("io.github.zenhelix.maven-central-publish")

        val artifactFile = File(tempDir.toFile(), "test-artifact.jar").apply {
            writeText("test content for checksum generation")
        }

        val task = project.tasks.register("testChecksum", CreateChecksumTask::class.java).get()
        task.publicationName.set("test")
        task.artifactInfos.set(listOf(
            ArtifactInfo(
                ArtifactFileInfo(artifactFile, null, "jar"),
                GAV("com.example", "test", "1.0")
            )
        ))
        task.outputDirectory.set(File(tempDir.toFile(), "checksums"))

        task.createChecksums()

        val outputDir = File(tempDir.toFile(), "checksums")
        assertThat(outputDir).isDirectory()

        // Check that at least MD5 and SHA1 checksums exist
        val checksumFiles = outputDir.walkTopDown().filter { it.isFile }.toList()
        assertThat(checksumFiles).isNotEmpty()

        val extensions = checksumFiles.map { it.extension }.toSet()
        assertThat(extensions).contains("md5", "sha1")
    }

    @Test
    fun `checksum content is valid hex string`() {
        val project = ProjectBuilder.builder().withProjectDir(tempDir.toFile()).build()
        project.plugins.apply("maven-publish")
        project.plugins.apply("signing")
        project.plugins.apply("io.github.zenhelix.maven-central-publish")

        val artifactFile = File(tempDir.toFile(), "test.jar").apply {
            writeText("hello world")
        }

        val task = project.tasks.register("testChecksum2", CreateChecksumTask::class.java).get()
        task.publicationName.set("test")
        task.artifactInfos.set(listOf(
            ArtifactInfo(
                ArtifactFileInfo(artifactFile, null, "jar"),
                GAV("com.example", "test", "1.0")
            )
        ))
        task.outputDirectory.set(File(tempDir.toFile(), "checksums"))

        task.createChecksums()

        val md5File = File(tempDir.toFile(), "checksums").walkTopDown()
            .firstOrNull { it.extension == "md5" }
        assertThat(md5File).isNotNull()
        assertThat(md5File!!.readText()).matches("[0-9a-f]+")
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "*.CreateChecksumTaskTest"`
Expected: PASS (implementation already exists)

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: add unit tests for CreateChecksumTask"
```

---

## Task 11: Unit Tests — CredentialMapping

**Files:**
- Create: `src/test/kotlin/.../utils/CredentialMappingTest.kt`

- [ ] **Step 1: Write tests for credential mapping**

Create `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/CredentialMappingTest.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.ValidationError
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test

class CredentialMappingTest {

    @Test
    fun `bearer token credentials are mapped correctly`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create(
            "mavenCentralPortal", MavenCentralUploaderExtension::class.java
        )
        extension.credentials { creds ->
            creds.bearer { it.token.set("test-token") }
        }

        val result = project.mapCredentials(extension).get()

        assertThat(result).isInstanceOf(Success::class.java)
        val creds = (result as Success).value
        assertThat(creds).isInstanceOf(Credentials.BearerTokenCredentials::class.java)
        assertThat((creds as Credentials.BearerTokenCredentials).token).isEqualTo("test-token")
    }

    @Test
    fun `username password credentials are mapped correctly`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create(
            "mavenCentralPortal", MavenCentralUploaderExtension::class.java
        )
        extension.credentials { creds ->
            creds.usernamePassword {
                it.username.set("user")
                it.password.set("pass")
            }
        }

        val result = project.mapCredentials(extension).get()

        assertThat(result).isInstanceOf(Success::class.java)
        val creds = (result as Success).value
        assertThat(creds).isInstanceOf(Credentials.UsernamePasswordCredentials::class.java)
    }

    @Test
    fun `no credentials returns NoCredentials error`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create(
            "mavenCentralPortal", MavenCentralUploaderExtension::class.java
        )

        val result = project.mapCredentials(extension).get()

        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isEqualTo(ValidationError.NoCredentials)
    }

    @Test
    fun `both credentials configured returns AmbiguousCredentials error`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create(
            "mavenCentralPortal", MavenCentralUploaderExtension::class.java
        )
        extension.credentials { creds ->
            creds.bearer { it.token.set("token") }
            creds.usernamePassword {
                it.username.set("user")
                it.password.set("pass")
            }
        }

        val result = project.mapCredentials(extension).get()

        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ValidationError.AmbiguousCredentials::class.java)
    }

    @Test
    fun `bearer configured but token missing returns MissingCredential`() {
        val project = ProjectBuilder.builder().build()
        val extension = project.extensions.create(
            "mavenCentralPortal", MavenCentralUploaderExtension::class.java
        )
        extension.credentials { creds ->
            creds.bearer { /* token not set */ }
        }

        val result = project.mapCredentials(extension).get()

        assertThat(result).isInstanceOf(Failure::class.java)
        val error = (result as Failure).error
        assertThat(error).isInstanceOf(ValidationError.MissingCredential::class.java)
    }
}
```

- [ ] **Step 2: Run tests**

Run: `./gradlew test --tests "*.CredentialMappingTest"`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: add unit tests for CredentialMapping"
```

---

## Task 12: Functional Tests — Configurators and Retry Config

**Files:**
- Modify: `src/functionalTest/kotlin/.../MavenCentralUploaderPluginFunctionalTest.kt`

- [ ] **Step 1: Add functional test for retry config DSL**

Add a test that verifies custom retry values are accepted by the DSL:

1. Set up a project with:
   ```kotlin
   mavenCentralPortal {
       uploader {
           requestTimeout = java.time.Duration.ofMinutes(10)
           connectTimeout = java.time.Duration.ofSeconds(60)
           maxRetries = 5
           retryBaseDelay = java.time.Duration.ofSeconds(5)
       }
   }
   ```
2. Run a dry-run build to ensure the build configures without errors
3. Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Add functional test for configurator task registration**

Add a test verifying that for a multi-module project with the plugin on root:
1. Root project tasks include: `zipDeploymentAllModules`, `publishAllModulesToMavenCentralPortalRepository`
2. Subproject tasks include: `checksumAllPublications`, `zipDeploymentAllPublications`

Use the existing `GradleDryRunOutputAssert` pattern to verify task names.

- [ ] **Step 3: Run functional tests**

Run: `./gradlew functionalTest`
Expected: All tests pass

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: add functional tests for retry config and configurator task registration"
```

---

## Task 13: Final Verification

- [ ] **Step 1: Run full build with all tests**

Run: `./gradlew clean check`
Expected: BUILD SUCCESSFUL, all unit and functional tests pass

- [ ] **Step 2: Verify code coverage report**

Run: `./gradlew testCodeCoverageReport`
Check: `build/reports/jacoco/testCodeCoverageReport/html/index.html`

- [ ] **Step 3: Final commit if any remaining changes**

```bash
git status
# If there are changes:
git add -A
git commit -m "chore: final cleanup after DX improvements"
```
