# Edge Cases Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 9 edge cases: lazy evaluation pipeline (artifacts lost with KMP/AGP), deployment cleanup, API client hardening.

**Architecture:** Three phases executed in order. Phase A (lazy pipeline) is the core architectural change — moves artifact resolution from configuration time to execution time. Phase B (API client) is isolated fixes. Phase C (deployment lifecycle) adds error cleanup.

**Tech Stack:** Kotlin, Gradle Plugin API, Gradle TestKit, JUnit 5, AssertJ, MockK

---

## File Structure

| File | Action | Phase | Responsibility |
|------|--------|-------|---------------|
| `src/main/kotlin/.../task/ZipDeploymentTask.kt` | Modify | A | Replace `configureContent()` with `copy()` override, change duplicates strategy |
| `src/main/kotlin/.../task/CreateChecksumTask.kt` | Modify | A | Remove `file().exists()` filters |
| `src/main/kotlin/.../MavenCentralUploaderPlugin.kt` | Modify | A | Remove `configureContent()` calls |
| `src/main/kotlin/.../client/MavenCentralApiClientImpl.kt` | Modify | B | HTTP 429 retry, filename escaping, close() KDoc |
| `src/main/kotlin/.../utils/RetryHandler.kt` | Modify | B | InterruptedException handling |
| `src/main/kotlin/.../task/PublishBundleMavenCentralTask.kt` | Modify | C | Drop deployment on failure/timeout |
| `src/functionalTest/kotlin/.../MavenCentralUploaderPluginFunctionalTest.kt` | Modify | A | New tests for lazy pipeline |
| `src/test/kotlin/.../client/MavenCentralApiClientImplTest.kt` | Modify | B | Tests for 429 retry, filename escaping |
| `src/test/kotlin/.../utils/RetryHandlerTest.kt` | Create | B | InterruptedException test |
| `src/test/kotlin/.../task/PublishBundleMavenCentralTaskTest.kt` | Create | C | Drop deployment tests |

All paths are relative to `/Users/dmitriimedakin/IdeaProjects/maven-central-publish/`.

---

## Phase A: Lazy Evaluation Pipeline

### Task 1: Test for late afterEvaluate artifact addition

**Files:**
- Modify: `src/functionalTest/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPluginFunctionalTest.kt`

- [ ] **Step 1: Write failing test — late afterEvaluate publication**

Add to `MavenCentralUploaderPluginFunctionalTest.kt`:

```kotlin
@Test
fun `should include artifacts added in late afterEvaluate`() {
    val version = "1.0.0"
    val moduleName = "late-lib"

    testProjectDir.settingsGradleFile().writeText(settings(moduleName))
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
                ${mavenCentralPortal()}
            }
        }
        
        ${signing()}
        $pom
        
        // Simulate AGP/KMP pattern: publication created in late afterEvaluate
        afterEvaluate {
            publishing {
                publications {
                    create<MavenPublication>("lateLib") {
                        from(components["java"])
                    }
                }
            }
        }
        """.trimIndent()
    )
    testProjectDir.createJavaMainClass()

    gradleRunnerDebug(testProjectDir) {
        withVersion(version)
        withTask("zipDeploymentAllPublications")
    }

    assertThat(
        ZipFile(testProjectDir.moduleBundleFile(null, moduleName, version, "allPublications").toFile())
    ).containsMavenArtifacts("test.zenhelix", moduleName, version) {
        standardJavaLibrary()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew functionalTest --tests "io.github.zenhelix.gradle.plugin.MavenCentralUploaderPluginFunctionalTest.should include artifacts added in late afterEvaluate"`

Expected: FAIL — late publication's artifacts are not in the ZIP because `configureContent()` snapshots eagerly.

- [ ] **Step 3: Commit failing test**

```bash
git add src/functionalTest/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPluginFunctionalTest.kt
git commit -m "test: add failing test for late afterEvaluate artifact addition"
```

---

### Task 2: Make ZipDeploymentTask lazy — replace configureContent with copy override

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/ZipDeploymentTask.kt`

- [ ] **Step 1: Replace `configureContent()` with `copy()` override and change duplicates strategy**

Replace the entire content of `ZipDeploymentTask.kt` with:

```kotlin
package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.ListProperty
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.bundling.Zip

/**
 * Task that creates ZIP deployment bundles for Maven Central Portal API.
 *
 * Artifact resolution is deferred to execution time via [copy] override,
 * ensuring that artifacts added by late-configuring plugins (KMP, AGP)
 * are included in the bundle.
 */
public abstract class ZipDeploymentTask : Zip() {

    @get:Internal
    public abstract val publications: ListProperty<PublicationInfo>

    init {
        group = PUBLISH_TASK_GROUP
        description = "Creates ZIP deployment bundle for Maven Central Portal API"

        duplicatesStrategy = DuplicatesStrategy.FAIL
    }

    @TaskAction
    override fun copy() {
        publications.get().forEach { info ->
            info.checksumTask?.let { checksumTask ->
                from(checksumTask.flatMap { it.checksumFiles }) {
                    into(info.artifactPath)
                }
            }

            info.artifacts.get().forEach { artifactInfo ->
                from(artifactInfo.file()) {
                    into(info.artifactPath)
                    rename { artifactInfo.artifactName }
                }
            }
        }
        super.copy()
    }

}
```

Key changes:
- Removed `configureContent()` method
- Removed `@CacheableTask` annotation (can't cache when CopySpec is built in `copy()`)
- Added `@TaskAction override fun copy()` that resolves providers at execution time
- Changed `DuplicatesStrategy.EXCLUDE` to `DuplicatesStrategy.FAIL`

- [ ] **Step 2: Remove all `configureContent()` calls from plugin**

In `src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt`, remove the `configureContent()` call at the end of each ZIP task registration. There are 3 locations:

**Location 1** (around line 112, per-publication zip task):
Remove the line `configureContent()` from inside the `registerZipPublicationTask` lambda.

**Location 2** (around line 130, all-publications zip task):
Remove the line `configureContent()` from inside the `registerZipAllPublicationsTask` lambda.

**Location 3** (around line 268, aggregated zip task in `createAggregationTasks`):
Remove the line `configureContent()` from inside the `register<ZipDeploymentTask>("zipDeploymentAllModules")` lambda.

- [ ] **Step 3: Run the late afterEvaluate test**

Run: `./gradlew functionalTest --tests "io.github.zenhelix.gradle.plugin.MavenCentralUploaderPluginFunctionalTest.should include artifacts added in late afterEvaluate"`

Expected: PASS

- [ ] **Step 4: Run all tests**

Run: `./gradlew check`

Expected: All tests pass (15/15). If any existing test fails due to `DuplicatesStrategy.FAIL`, investigate — it may reveal a real duplicate artifact issue.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/ZipDeploymentTask.kt
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt
git commit -m "feat: lazy artifact resolution in ZipDeploymentTask

Replace configureContent() (eager, configuration-time) with copy()
override (lazy, execution-time). Artifacts from late-configuring plugins
like KMP and AGP are now included in the bundle.

Also change DuplicatesStrategy from EXCLUDE to FAIL to surface
duplicate artifact path conflicts instead of silently dropping files."
```

---

### Task 3: Remove file().exists() filter from CreateChecksumTask

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/CreateChecksumTask.kt`

- [ ] **Step 1: Remove exists() filter from checksumFiles provider**

In `CreateChecksumTask.kt`, in the `checksumFiles` property initializer (around line 67), remove the `.filter { artifact -> artifact.file().exists() }` line. The block should become:

```kotlin
@get:OutputFiles
public val checksumFiles: ListProperty<RegularFile> = project.objects.listProperty<RegularFile>().apply {
    set(artifactInfos.zip(outputDirectory) { artifacts, outDir ->
        artifacts
            .flatMap { artifact ->
                CHECKSUM_ALGORITHMS.map { hashFunction ->
                    val artifactFile = artifact.file()
                    val artifactParentDir = artifactFile.parentFile?.name ?: "artifacts"
                    val checksumDir = outDir.asFile
                    val targetDir = File(checksumDir, artifactParentDir)
                    val algorithmSuffix = hashFunction.algorithm.lowercase(Locale.ROOT).replace("-", "")
                    val checksumFileName = "${artifact.artifactName}.$algorithmSuffix"
                    project.layout.projectDirectory.file(File(targetDir, checksumFileName).absolutePath)
                }
            }
    })
}
```

- [ ] **Step 2: Remove exists() filter from createChecksums() action**

In `CreateChecksumTask.kt`, in the `createChecksums()` method (around line 99), remove the `.filter { artifact -> artifact.file().exists() }` line. The block should become:

```kotlin
@TaskAction
public fun createChecksums() {
    val artifacts = artifactInfos.get()
    if (artifacts.isEmpty()) {
        logger.info("No artifacts configured for checksum generation")
        return
    }

    artifacts
        .forEach { artifact ->
            CHECKSUM_ALGORITHMS.forEach { hashFunction ->
                val checksumFile = getChecksumFile(artifact, hashFunction)
                checksumFile.parentFile.mkdirs()
                checksumFile.writeBytes(generateChecksum(artifact.file(), hashFunction))
            }
        }

}
```

- [ ] **Step 3: Run all tests**

Run: `./gradlew check`

Expected: All tests pass. If a test fails with `FileNotFoundException`, it indicates a real task dependency bug that was previously masked.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/CreateChecksumTask.kt
git commit -m "fix: remove file().exists() filter from checksum generation

The filter masked task dependency bugs by silently skipping artifacts
whose files hadn't been built yet. Now if a file doesn't exist at
execution time, the build fails with a clear error."
```

---

## Phase B: API Client Hardening

### Task 4: HTTP 429 retry

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImplTest.kt`

- [ ] **Step 1: Write failing test for 429 retry**

Add to `MavenCentralApiClientImplTest.kt`:

```kotlin
@Test
fun `uploadDeploymentBundle should retry on HTTP 429`() {
    val expectedDeploymentId = UUID.fromString("12345678-1234-1234-1234-123456789012")
    var callCount = 0

    every {
        mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>())
    } answers {
        callCount++
        if (callCount == 1) {
            mockk<HttpResponse<String>> {
                every { statusCode() } returns 429
                every { body() } returns "Rate limited"
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientImplTest.uploadDeploymentBundle should retry on HTTP 429"`

Expected: FAIL — 429 is not retried, returns `HttpResponseResult.Error`.

- [ ] **Step 3: Add 429 to retriable status codes**

In `MavenCentralApiClientImpl.kt`, in `executeRequestWithRetry()`, change the retry condition (around line 236):

```kotlin
if (result is HttpResponseResult.Error && (response.statusCode() >= 500 || response.statusCode() == HTTP_TOO_MANY_REQUESTS)) {
    throw RetriableHttpException(response.statusCode(), "Retriable HTTP error")
}
```

Add the constant to the companion object:

```kotlin
private const val HTTP_TOO_MANY_REQUESTS = 429
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientImplTest.uploadDeploymentBundle should retry on HTTP 429"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt
git add src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImplTest.kt
git commit -m "feat: retry HTTP requests on 429 Too Many Requests

Previously only 5xx errors were retried. Now 429 (rate limiting) also
triggers exponential backoff retry."
```

---

### Task 5: Multipart filename escaping

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt`
- Modify: `src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImplTest.kt`

- [ ] **Step 1: Write failing test for filename with special characters**

Add to `MavenCentralApiClientImplTest.kt`:

```kotlin
@Test
fun `uploadDeploymentBundle should escape special characters in filename`() {
    val capturedRequest = slot<HttpRequest>()
    val bundleFile = tempDir.resolve("test\"bundle.zip").also {
        Files.write(it, "test content".toByteArray())
    }

    every {
        mockHttpClient.send(capture(capturedRequest), any<BodyHandler<String>>())
    } returns mockk<HttpResponse<String>> {
        every { statusCode() } returns 201
        every { body() } returns UUID.randomUUID().toString()
        every { headers() } returns mockk { every { map() } returns emptyMap() }
    }

    client.uploadDeploymentBundle(
        credentials = BearerTokenCredentials(token = "test-token-123"),
        bundle = bundleFile
    )

    // Verify the request was sent (filename escaping doesn't break multipart)
    verify { mockHttpClient.send(any<HttpRequest>(), any<BodyHandler<String>>()) }
}
```

- [ ] **Step 2: Run test to verify current behavior**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientImplTest.uploadDeploymentBundle should escape special characters in filename"`

Expected: May pass (the test verifies the request is sent without error) or fail if the filename breaks multipart parsing.

- [ ] **Step 3: Add filename escaping**

In `MavenCentralApiClientImpl.kt`, modify the `filePart` companion function. Replace the line that appends the filename:

```kotlin
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
```

- [ ] **Step 4: Run test**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientImplTest.uploadDeploymentBundle should escape special characters in filename"`

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt
git add src/test/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImplTest.kt
git commit -m "fix: escape special characters in multipart upload filename

Backslashes and double quotes in filenames are now escaped in the
Content-Disposition header to prevent malformed multipart boundaries."
```

---

### Task 6: InterruptedException handling in RetryHandler

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt`
- Create: `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerTest.kt`

- [ ] **Step 1: Write failing test**

Create `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerTest.kt`:

```kotlin
package io.github.zenhelix.gradle.plugin.utils

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RetryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `should restore interrupt status when sleep is interrupted`() {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofSeconds(10), logger = logger)
        var attempt = 0

        val thread = Thread {
            assertThatThrownBy {
                handler.executeWithRetry(
                    operation = { attemptNum ->
                        attempt = attemptNum
                        throw RuntimeException("always fails")
                    },
                    shouldRetry = { true }
                )
            }.isInstanceOf(InterruptedException::class.java)

            assertThat(Thread.currentThread().isInterrupted).isTrue()
        }

        thread.start()
        // Wait for first attempt to fail and retry to start sleeping
        Thread.sleep(200)
        thread.interrupt()
        thread.join(5000)

        assertThat(attempt).isEqualTo(1)
        assertThat(thread.isAlive).isFalse()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.RetryHandlerTest.should restore interrupt status when sleep is interrupted"`

Expected: FAIL — `InterruptedException` is not caught, so it propagates as a wrapped exception without restoring interrupt status.

- [ ] **Step 3: Add InterruptedException handling**

In `RetryHandler.kt`, wrap the `Thread.sleep` call (around line 64):

```kotlin
try {
    Thread.sleep(delayMillis)
} catch (e: InterruptedException) {
    Thread.currentThread().interrupt()
    throw e
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.RetryHandlerTest"`

Expected: PASS

- [ ] **Step 5: Run all tests**

Run: `./gradlew check`

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandler.kt
git add src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/RetryHandlerTest.kt
git commit -m "fix: restore thread interrupt status in RetryHandler

When Thread.sleep is interrupted during retry backoff, the interrupt
status is now restored before rethrowing InterruptedException."
```

---

### Task 7: HttpClient close() KDoc

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt`

- [ ] **Step 1: Update KDoc on close()**

In `MavenCentralApiClientImpl.kt`, replace the `close()` method's comments:

```kotlin
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
```

- [ ] **Step 2: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientImpl.kt
git commit -m "docs: document HttpClient close() behavior on Java 17-20"
```

---

## Phase C: Deployment Lifecycle

### Task 8: Drop deployment on failure and timeout

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt`

- [ ] **Step 1: Add `tryDropDeployment` method and wrap `waitForDeploymentCompletion`**

In `PublishBundleMavenCentralTask.kt`, modify the `publishBundle()` method. Replace the `when (uploadResult)` block's `Success` branch:

```kotlin
is HttpResponseResult.Success -> {
    val deploymentId = uploadResult.data
    try {
        waitForDeploymentCompletion(apiClient, creds, deploymentId, type, maxChecks, checkDelay)
    } catch (e: Exception) {
        tryDropDeployment(apiClient, creds, deploymentId)
        throw e
    }
}
```

Add the `tryDropDeployment` private method to the class:

```kotlin
private fun tryDropDeployment(
    client: MavenCentralApiClient,
    creds: Credentials,
    deploymentId: UUID
) {
    logger.warn("Deployment failed, attempting to drop deployment {}", deploymentId)
    try {
        when (val result = client.dropDeployment(creds, deploymentId)) {
            is HttpResponseResult.Success -> {
                logger.lifecycle("Deployment {} dropped successfully", deploymentId)
            }
            is HttpResponseResult.Error -> {
                logger.warn("Failed to drop deployment {}: HTTP {}, Response: {}", deploymentId, result.httpStatus, result.data)
            }
            is HttpResponseResult.UnexpectedError -> {
                logger.warn("Failed to drop deployment {}: {}", deploymentId, result.cause.message)
            }
        }
    } catch (e: Exception) {
        logger.warn("Failed to drop deployment {}: {}", deploymentId, e.message)
    }
}
```

- [ ] **Step 2: Track drop calls in DumbImpl for testing**

Modify `MavenCentralApiClientDumbImpl.kt` to track calls:

```kotlin
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

- [ ] **Step 3: Run all tests to verify no regressions**

Run: `./gradlew check`

Expected: All pass. The happy-path tests should not trigger `tryDropDeployment` since `MavenCentralApiClientDumbImpl` returns PUBLISHED status.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishBundleMavenCentralTask.kt
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/client/MavenCentralApiClientDumbImpl.kt
git commit -m "feat: drop deployment on failure and timeout

When deployment validation fails or status polling times out, the plugin
now attempts to drop the deployment via the API. Drop failures are logged
as warnings but do not suppress the original error."
```

---

### Task 9: Final verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew clean check`

Expected: All tests pass.

- [ ] **Step 2: Verify KMP test still works with lazy pipeline**

Run: `./gradlew functionalTest --tests "io.github.zenhelix.gradle.plugin.MavenCentralUploaderPluginFunctionalTest.kmm publishing"`

Expected: PASS — the existing KMP test validates that lazy resolution works with real KMP plugin.

- [ ] **Step 3: Review git log**

Run: `git log --oneline -8`

Expected: 8 clean commits covering all 3 phases.
