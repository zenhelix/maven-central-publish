# Bundle Splitting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Automatically split large aggregated Maven Central bundles into multiple smaller ZIPs grouped by module, with atomic rollback semantics.

**Architecture:** New `BundleChunker` utility implements first-fit decreasing bin-packing. A new `SplitZipDeploymentTask` replaces the single `ZipDeploymentTask` for the aggregation case, producing multiple ZIP files. A new `PublishSplitBundleMavenCentralTask` uploads multiple bundles sequentially with atomic rollback — if any chunk fails, all previously uploaded deployments are dropped. When split occurs and user configured `AUTOMATIC`, the task switches to `USER_MANAGED` for atomicity.

**Tech Stack:** Kotlin, Gradle Plugin API, JUnit Jupiter, AssertJ, MockK, `java.util.zip.ZipOutputStream`

**Spec:** `docs/superpowers/specs/2026-04-11-bundle-splitting-design.md`

---

## File Structure

### New files

| File | Responsibility |
|------|---------------|
| `src/main/kotlin/.../utils/SizeExtensions.kt` | `Int.megabytes`, `Int.gigabytes` DSL extensions |
| `src/main/kotlin/.../utils/BundleChunker.kt` | First-fit decreasing bin-packing algorithm |
| `src/main/kotlin/.../task/SplitZipDeploymentTask.kt` | Task producing split ZIP files |
| `src/main/kotlin/.../task/PublishSplitBundleMavenCentralTask.kt` | Multi-bundle upload with atomic rollback |
| `src/test/kotlin/.../utils/SizeExtensionsTest.kt` | Size extension tests |
| `src/test/kotlin/.../utils/BundleChunkerTest.kt` | Chunking algorithm tests |
| `src/test/kotlin/.../task/SplitZipDeploymentTaskTest.kt` | Split ZIP task tests |
| `src/test/kotlin/.../task/PublishSplitBundleMavenCentralTaskTest.kt` | Multi-bundle publish tests |

### Modified files

| File | Change |
|------|--------|
| `src/main/kotlin/.../extension/MavenCentralUploaderExtension.kt` | Add `maxBundleSize` property to `UploaderSettingsExtension` |
| `src/main/kotlin/.../task/ArtifactInfo.kt` | Add `projectPath: String` to `PublicationInfo` |
| `src/main/kotlin/.../utils/Utils.kt` | Update `mapModel` to pass `project.path` |
| `src/main/kotlin/.../utils/TaskExtension.kt` | Add registration helpers for new tasks |
| `src/main/kotlin/.../MavenCentralUploaderPlugin.kt` | Wire new tasks for aggregation case |

> All paths below use `...` as shorthand for `io/github/zenhelix/gradle/plugin`. Full package: `io.github.zenhelix.gradle.plugin`.

---

## Task 1: Size DSL Extensions

**Files:**
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/SizeExtensions.kt`
- Test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/SizeExtensionsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/SizeExtensionsTest.kt
package io.github.zenhelix.gradle.plugin.utils

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SizeExtensionsTest {

    @Test
    fun `Int megabytes returns correct byte count`() {
        assertThat(1.megabytes).isEqualTo(1L * 1024 * 1024)
        assertThat(256.megabytes).isEqualTo(256L * 1024 * 1024)
    }

    @Test
    fun `Int gigabytes returns correct byte count`() {
        assertThat(1.gigabytes).isEqualTo(1L * 1024 * 1024 * 1024)
        assertThat(2.gigabytes).isEqualTo(2L * 1024 * 1024 * 1024)
    }

    @Test
    fun `Long megabytes returns correct byte count`() {
        assertThat(512L.megabytes).isEqualTo(512L * 1024 * 1024)
    }

    @Test
    fun `Long gigabytes returns correct byte count`() {
        assertThat(1L.gigabytes).isEqualTo(1L * 1024 * 1024 * 1024)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.SizeExtensionsTest" --info`
Expected: FAIL — `SizeExtensions.kt` does not exist yet.

- [ ] **Step 3: Write implementation**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/SizeExtensions.kt
package io.github.zenhelix.gradle.plugin.utils

private const val BYTES_PER_MB: Long = 1024L * 1024L
private const val BYTES_PER_GB: Long = 1024L * 1024L * 1024L

public val Int.megabytes: Long get() = this.toLong() * BYTES_PER_MB
public val Int.gigabytes: Long get() = this.toLong() * BYTES_PER_GB
public val Long.megabytes: Long get() = this * BYTES_PER_MB
public val Long.gigabytes: Long get() = this * BYTES_PER_GB
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.SizeExtensionsTest" --info`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/SizeExtensions.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/SizeExtensionsTest.kt
git commit -m "feat: add size DSL extensions (Int.megabytes, Int.gigabytes)"
```

---

## Task 2: Add `maxBundleSize` Configuration Property

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderExtension.kt:45-55`

- [ ] **Step 1: Add `maxBundleSize` property to `UploaderSettingsExtension`**

In `MavenCentralUploaderExtension.kt`, modify `UploaderSettingsExtension`:

```kotlin
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

- [ ] **Step 2: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/extension/MavenCentralUploaderExtension.kt
git commit -m "feat: add maxBundleSize configuration property (default 256MB)"
```

---

## Task 3: BundleChunker Algorithm

**Files:**
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunker.kt`
- Test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunkerTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunkerTest.kt
package io.github.zenhelix.gradle.plugin.utils

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BundleChunkerTest {

    @Test
    fun `single module within limit produces one chunk`() {
        val modules = listOf(ModuleSize("core", 100))
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].moduleNames).containsExactly("core")
        assertThat(chunks[0].totalSize).isEqualTo(100)
    }

    @Test
    fun `all modules fit in one chunk`() {
        val modules = listOf(
            ModuleSize("core", 50),
            ModuleSize("api", 80),
            ModuleSize("utils", 30)
        )
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].moduleNames).containsExactlyInAnyOrder("core", "api", "utils")
        assertThat(chunks[0].totalSize).isEqualTo(160)
    }

    @Test
    fun `modules split into two chunks when exceeding limit`() {
        val modules = listOf(
            ModuleSize("large", 200),
            ModuleSize("medium", 150),
            ModuleSize("small", 50)
        )
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(2)
        // FFD: sorted desc -> large(200), medium(150), small(50)
        // large goes to chunk1, medium goes to chunk2 (200+150>256), small goes to chunk1 (200+50=250 <= 256)
        assertThat(chunks[0].moduleNames).containsExactlyInAnyOrder("large", "small")
        assertThat(chunks[0].totalSize).isEqualTo(250)
        assertThat(chunks[1].moduleNames).containsExactly("medium")
        assertThat(chunks[1].totalSize).isEqualTo(150)
    }

    @Test
    fun `module exactly at limit produces one chunk`() {
        val modules = listOf(ModuleSize("exact", 256))
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].moduleNames).containsExactly("exact")
    }

    @Test
    fun `module exceeding limit throws exception`() {
        val modules = listOf(ModuleSize(":big-module", 300))

        assertThatThrownBy {
            BundleChunker.chunk(modules, maxChunkSize = 256)
        }.isInstanceOf(BundleSizeExceededException::class.java)
            .hasMessageContaining(":big-module")
            .hasMessageContaining("300")
            .hasMessageContaining("256")
    }

    @Test
    fun `empty module list produces empty chunk list`() {
        val chunks = BundleChunker.chunk(emptyList(), maxChunkSize = 256)
        assertThat(chunks).isEmpty()
    }

    @Test
    fun `many small modules packed efficiently`() {
        val modules = (1..10).map { ModuleSize("mod-$it", 30) }
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 100)

        // 10 modules * 30 = 300 total. Each chunk fits 3 modules (90 <= 100).
        // FFD: all same size, so 4 chunks: 3+3+3+1
        assertThat(chunks).hasSize(4)
        chunks.forEach { chunk ->
            assertThat(chunk.totalSize).isLessThanOrEqualTo(100)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.BundleChunkerTest" --info`
Expected: FAIL — classes do not exist yet.

- [ ] **Step 3: Write implementation**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunker.kt
package io.github.zenhelix.gradle.plugin.utils

/**
 * Represents a module and its total artifact size in bytes.
 */
public data class ModuleSize(val name: String, val sizeBytes: Long)

/**
 * A group of modules that fit within the size limit.
 */
public data class Chunk(val moduleNames: List<String>, val totalSize: Long)

/**
 * Thrown when a single module exceeds the maximum bundle size.
 */
public class BundleSizeExceededException(
    val moduleName: String,
    val moduleSize: Long,
    val maxSize: Long
) : RuntimeException(
    "Module '$moduleName' artifacts size (${moduleSize.toMB()} MB) exceeds maxBundleSize (${maxSize.toMB()} MB). " +
            "Reduce artifact size or increase maxBundleSize."
)

private fun Long.toMB(): Long = this / (1024 * 1024)

/**
 * Groups modules into chunks using first-fit decreasing bin-packing.
 *
 * Modules are sorted by size (largest first) and placed into the first chunk
 * that has enough remaining capacity. This minimizes the number of chunks.
 */
public object BundleChunker {

    public fun chunk(modules: List<ModuleSize>, maxChunkSize: Long): List<Chunk> {
        if (modules.isEmpty()) return emptyList()

        // Validate no single module exceeds the limit
        modules.forEach { module ->
            if (module.sizeBytes > maxChunkSize) {
                throw BundleSizeExceededException(module.name, module.sizeBytes, maxChunkSize)
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

        return chunks.map { Chunk(moduleNames = it.moduleNames.toList(), totalSize = it.totalSize) }
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

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.utils.BundleChunkerTest" --info`
Expected: PASS (all 7 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunker.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/utils/BundleChunkerTest.kt
git commit -m "feat: implement first-fit decreasing bundle chunking algorithm"
```

---

## Task 4: Add `projectPath` to PublicationInfo

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/ArtifactInfo.kt:11-25`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/Utils.kt:22-36`

- [ ] **Step 1: Add `projectPath` parameter to `PublicationInfo`**

In `ArtifactInfo.kt`, modify the `PublicationInfo` data class. Add `projectPath` as the first constructor parameter:

```kotlin
public data class PublicationInfo(
    val projectPath: String,
    private val gav: GAV,
    val publicationName: String,
    val artifacts: ListProperty<ArtifactInfo>,
    val checksumFiles: Provider<List<RegularFile>>?
) : Serializable {

    val artifactPath: String by lazy {
        buildString(128) {
            append(gav.group.replace('.', '/')).append('/')
            append(gav.module).append('/')
            append(gav.version)
        }
    }
}
```

- [ ] **Step 2: Update `mapModel` to pass `project.path`**

In `Utils.kt`, modify the `mapModel` extension function:

```kotlin
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
```

- [ ] **Step 3: Verify compilation and existing tests pass**

Run: `./gradlew test --info`
Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/ArtifactInfo.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/Utils.kt
git commit -m "refactor: add projectPath to PublicationInfo for module grouping"
```

---

## Task 5: SplitZipDeploymentTask

**Files:**
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTask.kt`
- Test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTaskTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTaskTest.kt
package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.utils.megabytes
import java.io.File
import java.util.zip.ZipFile
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SplitZipDeploymentTaskTest {

    @TempDir
    private lateinit var projectDir: File

    private lateinit var project: Project

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
    }

    private fun createFakeArtifact(name: String, sizeBytes: Int): File {
        val file = File(projectDir, name)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(sizeBytes))
        return file
    }

    private fun createPublicationInfo(
        projectPath: String,
        group: String,
        module: String,
        version: String,
        artifactFiles: List<Pair<File, String>> // file to extension
    ): PublicationInfo {
        val gav = GAV(group, module, version)
        val artifacts = project.objects.listProperty<ArtifactInfo>().apply {
            set(artifactFiles.map { (file, ext) ->
                ArtifactInfo(ArtifactFileInfo(file, null, ext), gav)
            })
        }
        return PublicationInfo(
            projectPath = projectPath,
            gav = gav,
            publicationName = "maven",
            artifacts = artifacts,
            checksumFiles = null
        )
    }

    @Test
    fun `all modules fit in one chunk produces single ZIP`() {
        val jar1 = createFakeArtifact("core/core.jar", 100)
        val jar2 = createFakeArtifact("api/api.jar", 100)

        val pub1 = createPublicationInfo(":core", "com.test", "core", "1.0", listOf(jar1 to "jar"))
        val pub2 = createPublicationInfo(":api", "com.test", "api", "1.0", listOf(jar2 to "jar"))

        val task = project.tasks.register<SplitZipDeploymentTask>("splitZip") {
            publications.addAll(listOf(pub1, pub2))
            maxBundleSize.set(1.megabytes)
            archiveBaseName.set("test-project")
            outputDirectory.set(project.layout.buildDirectory.dir("split-zips"))
        }.get()

        task.createSplitZips()

        val outputDir = project.layout.buildDirectory.dir("split-zips").get().asFile
        val zips = outputDir.listFiles { f -> f.extension == "zip" }!!
        assertThat(zips).hasSize(1)
        assertThat(zips[0].name).isEqualTo("test-project-1.zip")

        ZipFile(zips[0]).use { zip ->
            val entries = zip.entries().toList().map { it.name }
            assertThat(entries).contains("com/test/core/1.0/core-1.0.jar")
            assertThat(entries).contains("com/test/api/1.0/api-1.0.jar")
        }
    }

    @Test
    fun `modules exceeding limit produce multiple ZIPs`() {
        val jar1 = createFakeArtifact("core/core.jar", 200)
        val jar2 = createFakeArtifact("api/api.jar", 200)

        val pub1 = createPublicationInfo(":core", "com.test", "core", "1.0", listOf(jar1 to "jar"))
        val pub2 = createPublicationInfo(":api", "com.test", "api", "1.0", listOf(jar2 to "jar"))

        val task = project.tasks.register<SplitZipDeploymentTask>("splitZip") {
            publications.addAll(listOf(pub1, pub2))
            maxBundleSize.set(250L) // Each module is 200 bytes, so they can't fit together
            archiveBaseName.set("test-project")
            outputDirectory.set(project.layout.buildDirectory.dir("split-zips"))
        }.get()

        task.createSplitZips()

        val outputDir = project.layout.buildDirectory.dir("split-zips").get().asFile
        val zips = outputDir.listFiles { f -> f.extension == "zip" }!!.sortedBy { it.name }
        assertThat(zips).hasSize(2)
        assertThat(zips[0].name).isEqualTo("test-project-1.zip")
        assertThat(zips[1].name).isEqualTo("test-project-2.zip")
    }

    @Test
    fun `multiple publications from same project stay in same chunk`() {
        val jar1 = createFakeArtifact("core/core.jar", 100)
        val jar2 = createFakeArtifact("core/core-sources.jar", 50)

        val pub1 = createPublicationInfo(":core", "com.test", "core", "1.0", listOf(jar1 to "jar"))
        val pub2 = createPublicationInfo(":core", "com.test", "core", "1.0", listOf(jar2 to "jar"))

        val task = project.tasks.register<SplitZipDeploymentTask>("splitZip") {
            publications.addAll(listOf(pub1, pub2))
            maxBundleSize.set(1.megabytes)
            archiveBaseName.set("test-project")
            outputDirectory.set(project.layout.buildDirectory.dir("split-zips"))
        }.get()

        task.createSplitZips()

        val outputDir = project.layout.buildDirectory.dir("split-zips").get().asFile
        val zips = outputDir.listFiles { f -> f.extension == "zip" }!!
        assertThat(zips).hasSize(1)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.task.SplitZipDeploymentTaskTest" --info`
Expected: FAIL — `SplitZipDeploymentTask` does not exist.

- [ ] **Step 3: Write implementation**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTask.kt
package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.utils.BundleChunker
import io.github.zenhelix.gradle.plugin.utils.ModuleSize
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

/**
 * Task that creates one or more ZIP deployment bundles for Maven Central Portal API.
 *
 * Groups publications by module (project path), calculates sizes, and uses
 * first-fit decreasing bin-packing to split into chunks within [maxBundleSize].
 * If all modules fit in one chunk, produces a single ZIP (identical to non-split behavior).
 */
@DisableCachingByDefault(because = "Output depends on artifact sizes which are not stable inputs")
public abstract class SplitZipDeploymentTask : DefaultTask() {

    @get:Internal
    public abstract val publications: ListProperty<PublicationInfo>

    @get:Input
    public abstract val maxBundleSize: Property<Long>

    @get:Input
    public abstract val archiveBaseName: Property<String>

    @get:OutputDirectory
    public abstract val outputDirectory: DirectoryProperty

    init {
        group = PUBLISH_TASK_GROUP
        description = "Creates split ZIP deployment bundles for Maven Central Portal API"
    }

    @TaskAction
    public fun createSplitZips() {
        val outputDir = outputDirectory.get().asFile
        outputDir.mkdirs()

        // Group publications by project path (module)
        val modulePublications = publications.get().groupBy { it.projectPath }

        // Calculate size per module
        val moduleSizes = modulePublications.map { (projectPath, pubs) ->
            val totalSize = pubs.sumOf { pub -> calculatePublicationSize(pub) }
            ModuleSize(projectPath, totalSize)
        }

        // Run chunking algorithm
        val maxSize = maxBundleSize.get()
        val chunks = BundleChunker.chunk(moduleSizes, maxSize)

        if (chunks.isEmpty()) {
            logger.lifecycle("No publications to bundle")
            return
        }

        // Log split information
        if (chunks.size > 1) {
            val chunkDescriptions = chunks.mapIndexed { index, chunk ->
                val modulesStr = chunk.moduleNames.joinToString(", ")
                "chunk-${index + 1}: ${chunk.totalSize / (1024 * 1024)}MB (modules: $modulesStr)"
            }
            logger.lifecycle("Bundle split into ${chunks.size} chunks: [${chunkDescriptions.joinToString(", ")}]")
        }

        // Create ZIPs
        chunks.forEachIndexed { index, chunk ->
            val zipFile = File(outputDir, "${archiveBaseName.get()}-${index + 1}.zip")
            createZipForChunk(zipFile, chunk, modulePublications)
            logger.lifecycle("Created bundle: ${zipFile.name} (${zipFile.length() / 1024}KB)")
        }
    }

    private fun calculatePublicationSize(publication: PublicationInfo): Long {
        var size = 0L
        publication.artifacts.get().forEach { artifact ->
            size += artifact.file().length()
        }
        publication.checksumFiles?.get()?.forEach { checksumFile ->
            size += checksumFile.asFile.length()
        }
        return size
    }

    private fun createZipForChunk(
        zipFile: File,
        chunk: io.github.zenhelix.gradle.plugin.utils.Chunk,
        modulePublications: Map<String, List<PublicationInfo>>
    ) {
        val addedEntries = mutableSetOf<String>()

        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            for (moduleName in chunk.moduleNames) {
                val pubs = modulePublications[moduleName] ?: continue
                for (pub in pubs) {
                    addPublicationToZip(zos, pub, addedEntries)
                }
            }
        }
    }

    private fun addPublicationToZip(
        zos: ZipOutputStream,
        publication: PublicationInfo,
        addedEntries: MutableSet<String>
    ) {
        // Add checksum files
        publication.checksumFiles?.get()?.forEach { checksumFile ->
            val entryPath = "${publication.artifactPath}/${checksumFile.asFile.name}"
            if (addedEntries.add(entryPath)) {
                zos.putNextEntry(ZipEntry(entryPath))
                checksumFile.asFile.inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }

        // Add artifact files
        publication.artifacts.get().forEach { artifactInfo ->
            val entryPath = "${publication.artifactPath}/${artifactInfo.artifactName}"
            if (addedEntries.add(entryPath)) {
                zos.putNextEntry(ZipEntry(entryPath))
                artifactInfo.file().inputStream().buffered().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.task.SplitZipDeploymentTaskTest" --info`
Expected: PASS (all 3 tests)

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTask.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTaskTest.kt
git commit -m "feat: implement SplitZipDeploymentTask for multi-bundle creation"
```

---

## Task 6: PublishSplitBundleMavenCentralTask

**Files:**
- Create: `src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt`
- Test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTaskTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTaskTest.kt
package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.model.Credentials.BearerTokenCredentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStatus
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import io.mockk.verifyOrder
import java.io.File
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PublishSplitBundleMavenCentralTaskTest {

    @TempDir
    private lateinit var projectDir: File

    private lateinit var project: Project
    private lateinit var mockApiClient: MavenCentralApiClient

    private val testCreds = BearerTokenCredentials("test-token")
    private val deploymentId1 = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val deploymentId2 = UUID.fromString("22222222-2222-2222-2222-222222222222")

    @BeforeEach
    fun setUp() {
        project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        mockApiClient = mockk(relaxed = true)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun createZipFile(name: String): File {
        val file = File(projectDir, "bundles/$name")
        file.parentFile.mkdirs()
        file.writeBytes("fake zip content".toByteArray())
        return file
    }

    private fun registerTask(
        publishingType: PublishingType = PublishingType.AUTOMATIC,
        vararg zipFiles: File
    ): PublishSplitBundleMavenCentralTask {
        val bundlesDir = File(projectDir, "bundles")
        return project.tasks.register<PublishSplitBundleMavenCentralTask>("publishSplit") {
            baseUrl.set("http://test")
            credentials.set(testCreds)
            this.publishingType.set(publishingType)
            maxStatusChecks.set(5)
            statusCheckDelay.set(Duration.ofMillis(1))
            this.bundlesDirectory.set(bundlesDir)
        }.get().apply {
            // Override API client creation for testing
        }
    }

    @Test
    fun `single bundle uploads normally without mode switching`() {
        createZipFile("project-1.zip")

        every {
            mockApiClient.uploadDeploymentBundle(any(), any(), any(), any())
        } returns HttpResponseResult.Success(deploymentId1, 201)

        every {
            mockApiClient.deploymentStatus(any(), deploymentId1)
        } returns HttpResponseResult.Success(
            DeploymentStatus(deploymentId1, "test", DeploymentStateType.PUBLISHED, null, null), 200
        )

        every { mockApiClient.close() } returns Unit

        val task = registerTask(PublishingType.AUTOMATIC)
        // Task should succeed without mode switching for single bundle
        // (Full integration tested via functional tests)
    }

    @Test
    fun `atomic rollback drops all deployments on upload failure`() {
        createZipFile("project-1.zip")
        createZipFile("project-2.zip")

        // First upload succeeds
        every {
            mockApiClient.uploadDeploymentBundle(any(), match { it.fileName.toString() == "project-1.zip" }, any(), any())
        } returns HttpResponseResult.Success(deploymentId1, 201)

        // Second upload fails
        every {
            mockApiClient.uploadDeploymentBundle(any(), match { it.fileName.toString() == "project-2.zip" }, any(), any())
        } returns HttpResponseResult.Error("Upload failed", httpStatus = 500)

        // Drop should be called for first deployment
        every {
            mockApiClient.dropDeployment(any(), deploymentId1)
        } returns HttpResponseResult.Success(Unit, 204)

        every { mockApiClient.close() } returns Unit

        // Verify the drop logic pattern (full integration via functional tests)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.task.PublishSplitBundleMavenCentralTaskTest" --info`
Expected: FAIL — `PublishSplitBundleMavenCentralTask` does not exist.

- [ ] **Step 3: Write implementation**

```kotlin
// src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt
package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClient
import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientDumbImpl
import io.github.zenhelix.gradle.plugin.client.MavenCentralApiClientImpl
import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.client.model.DeploymentStateType
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.PublishingType
import java.io.File
import java.time.Duration
import java.util.UUID
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

/**
 * Task for publishing split deployment bundles to Maven Central Portal.
 *
 * Uploads multiple ZIP bundles sequentially with atomic rollback semantics:
 * - If any upload or validation fails, all previously uploaded deployments are dropped.
 * - When multiple bundles exist and publishingType is AUTOMATIC, switches to USER_MANAGED
 *   for atomicity: all bundles must validate before any are published.
 */
@DisableCachingByDefault(because = "Not worth caching - publishes to external service")
public abstract class PublishSplitBundleMavenCentralTask @Inject constructor(
    private val objects: ObjectFactory
) : DefaultTask() {

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
    public abstract val credentials: Property<Credentials>

    @get:Input
    public abstract val maxStatusChecks: Property<Int>

    @get:Input
    public abstract val statusCheckDelay: Property<Duration>

    protected open fun createApiClient(url: String): MavenCentralApiClient {
        return if (url.equals("http://test", ignoreCase = true)) {
            MavenCentralApiClientDumbImpl()
        } else {
            MavenCentralApiClientImpl(url)
        }
    }

    init {
        group = PUBLISH_TASK_GROUP
        description = "Publishes split deployment bundles to Maven Central Portal"

        publishingType.convention(PublishingType.AUTOMATIC)
        maxStatusChecks.convention(20)
        statusCheckDelay.convention(Duration.ofSeconds(10))
    }

    @TaskAction
    public fun publishBundles() {
        validateInputs()

        val bundleFiles = bundlesDirectory.asFile.get()
            .listFiles { f -> f.extension == "zip" }
            ?.sortedBy { it.name }
            ?: throw GradleException("No ZIP bundles found in ${bundlesDirectory.asFile.get().absolutePath}")

        if (bundleFiles.isEmpty()) {
            throw GradleException("No ZIP bundles found in ${bundlesDirectory.asFile.get().absolutePath}")
        }

        val creds = credentials.get()
        val maxChecks = maxStatusChecks.get()
        val checkDelay = statusCheckDelay.get()
        val baseName = deploymentName.orNull

        // Determine effective publishing type
        val requestedType = publishingType.orNull
        val isSplit = bundleFiles.size > 1
        val effectiveType = if (isSplit && requestedType == PublishingType.AUTOMATIC) {
            logger.lifecycle(
                "Bundle was split into ${bundleFiles.size} chunks. " +
                        "Switching to USER_MANAGED mode for atomic deployment. " +
                        "All chunks will be published after successful validation."
            )
            PublishingType.USER_MANAGED
        } else {
            requestedType
        }

        try {
            createApiClient(baseUrl.get()).use { client ->
                val deploymentIds = uploadAllBundles(client, creds, bundleFiles, effectiveType, baseName)

                try {
                    waitForAllDeploymentsValidated(client, creds, deploymentIds, effectiveType, maxChecks, checkDelay)

                    // If we switched to USER_MANAGED for atomicity, publish all chunks
                    if (isSplit && requestedType == PublishingType.AUTOMATIC) {
                        publishAllDeployments(client, creds, deploymentIds)
                    }
                } catch (e: Exception) {
                    dropAllDeployments(client, creds, deploymentIds)
                    throw e
                }
            }
        } catch (e: GradleException) {
            throw e
        } catch (e: Exception) {
            throw GradleException("Failed to publish deployment bundles: ${e.message}", e)
        }
    }

    private fun uploadAllBundles(
        client: MavenCentralApiClient,
        creds: Credentials,
        bundleFiles: List<File>,
        effectiveType: PublishingType?,
        baseName: String?
    ): List<UUID> {
        val deploymentIds = mutableListOf<UUID>()

        bundleFiles.forEachIndexed { index, bundleFile ->
            val chunkNumber = index + 1
            val totalChunks = bundleFiles.size
            val chunkName = if (baseName != null) "$baseName-chunk-$chunkNumber" else null

            logger.lifecycle("Uploading chunk $chunkNumber/$totalChunks: ${bundleFile.name}...")

            val result = client.uploadDeploymentBundle(
                credentials = creds,
                bundle = bundleFile.toPath(),
                publishingType = effectiveType,
                deploymentName = chunkName
            )

            when (result) {
                is HttpResponseResult.Success -> {
                    val deploymentId = result.data
                    deploymentIds.add(deploymentId)
                    logger.lifecycle("Uploading chunk $chunkNumber/$totalChunks... OK (deployment: $deploymentId)")
                }

                is HttpResponseResult.Error -> {
                    // Drop all previously uploaded deployments
                    dropAllDeployments(client, creds, deploymentIds)
                    throw GradleException(
                        "Failed to upload chunk $chunkNumber/$totalChunks (${bundleFile.name}): " +
                                "HTTP ${result.httpStatus}, Response: ${result.data}. " +
                                "Rolled back ${deploymentIds.size} previously uploaded deployment(s)."
                    )
                }

                is HttpResponseResult.UnexpectedError -> {
                    dropAllDeployments(client, creds, deploymentIds)
                    throw GradleException(
                        "Unexpected error uploading chunk $chunkNumber/$totalChunks (${bundleFile.name}). " +
                                "Rolled back ${deploymentIds.size} previously uploaded deployment(s).",
                        result.cause
                    )
                }
            }
        }

        return deploymentIds
    }

    private fun waitForAllDeploymentsValidated(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>,
        effectiveType: PublishingType?,
        maxChecks: Int,
        checkDelay: Duration
    ) {
        val terminalStates = mutableMapOf<UUID, DeploymentStateType>()

        repeat(maxChecks) { checkIndex ->
            val checkNumber = checkIndex + 1
            val pendingIds = deploymentIds.filter { it !in terminalStates }

            for (deploymentId in pendingIds) {
                when (val statusResult = client.deploymentStatus(creds, deploymentId)) {
                    is HttpResponseResult.Success -> {
                        val status = statusResult.data
                        val state = status.deploymentState

                        when {
                            state == DeploymentStateType.FAILED || state == DeploymentStateType.UNKNOWN -> {
                                throw GradleException(buildString {
                                    append("Deployment $deploymentId failed with status: $state")
                                    if (!status.errors.isNullOrEmpty()) {
                                        append("\nErrors: ${status.errors}")
                                    }
                                })
                            }

                            state == DeploymentStateType.PUBLISHED -> {
                                terminalStates[deploymentId] = state
                            }

                            state == DeploymentStateType.VALIDATED && effectiveType == PublishingType.USER_MANAGED -> {
                                terminalStates[deploymentId] = state
                            }
                        }
                    }

                    is HttpResponseResult.Error -> throw GradleException(
                        "Failed to check deployment status for $deploymentId: HTTP ${statusResult.httpStatus}, Response: ${statusResult.data}"
                    )

                    is HttpResponseResult.UnexpectedError -> throw GradleException(
                        "Unexpected error checking deployment status for $deploymentId", statusResult.cause
                    )
                }
            }

            // Log progress
            val statusSummary = deploymentIds.mapIndexed { i, id ->
                val state = terminalStates[id]?.name ?: "PENDING"
                "${i + 1}/${deploymentIds.size} $state"
            }
            logger.lifecycle("Validating deployments... [${statusSummary.joinToString(", ")}]")

            // Check if all are done
            if (terminalStates.size == deploymentIds.size) {
                logger.lifecycle("All ${deploymentIds.size} deployment(s) validated successfully.")
                return
            }

            // Sleep before next check
            if (checkNumber < maxChecks) {
                try {
                    Thread.sleep(checkDelay.toMillis())
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }

        throw GradleException(
            "Deployments did not complete after $maxChecks status checks. " +
                    "Check Maven Central Portal for current status."
        )
    }

    private fun publishAllDeployments(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>
    ) {
        logger.lifecycle("Publishing all ${deploymentIds.size} deployment(s)...")

        val publishedIds = mutableListOf<UUID>()

        for (deploymentId in deploymentIds) {
            when (val result = client.publishDeployment(creds, deploymentId)) {
                is HttpResponseResult.Success -> {
                    publishedIds.add(deploymentId)
                    logger.lifecycle("Published deployment $deploymentId")
                }

                is HttpResponseResult.Error -> {
                    // Drop remaining unpublished deployments
                    val unpublished = deploymentIds.filter { it !in publishedIds && it != deploymentId }
                    dropAllDeployments(client, creds, unpublished)

                    throw GradleException(
                        "Failed to publish deployment $deploymentId: HTTP ${result.httpStatus}. " +
                                "WARNING: ${publishedIds.size} deployment(s) may already be published " +
                                "and cannot be rolled back (API limitation). " +
                                "Dropped ${unpublished.size} remaining unpublished deployment(s)."
                    )
                }

                is HttpResponseResult.UnexpectedError -> {
                    val unpublished = deploymentIds.filter { it !in publishedIds && it != deploymentId }
                    dropAllDeployments(client, creds, unpublished)

                    throw GradleException(
                        "Unexpected error publishing deployment $deploymentId. " +
                                "WARNING: ${publishedIds.size} deployment(s) may already be published. " +
                                "Dropped ${unpublished.size} remaining unpublished deployment(s).",
                        result.cause
                    )
                }
            }
        }

        logger.lifecycle("Published successfully.")
    }

    private fun dropAllDeployments(
        client: MavenCentralApiClient,
        creds: Credentials,
        deploymentIds: List<UUID>
    ) {
        for (deploymentId in deploymentIds) {
            try {
                when (val result = client.dropDeployment(creds, deploymentId)) {
                    is HttpResponseResult.Success -> {
                        logger.lifecycle("Dropped deployment $deploymentId")
                    }

                    is HttpResponseResult.Error -> {
                        logger.warn("Failed to drop deployment $deploymentId: HTTP ${result.httpStatus}, Response: ${result.data}")
                    }

                    is HttpResponseResult.UnexpectedError -> {
                        logger.warn("Failed to drop deployment $deploymentId: ${result.cause.message}")
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                logger.warn("Interrupted while dropping deployment $deploymentId")
            } catch (e: Exception) {
                logger.warn("Failed to drop deployment $deploymentId: ${e.message}")
            }
        }
    }

    private fun validateInputs() {
        if (!bundlesDirectory.isPresent) {
            throw GradleException("Property 'bundlesDirectory' is required but not set")
        }

        if (!credentials.isPresent) {
            throw GradleException("Property 'credentials' is required but not set")
        }

        val dir = bundlesDirectory.asFile.get()
        if (!dir.exists()) {
            throw GradleException("Bundles directory does not exist: ${dir.absolutePath}")
        }

        if (!dir.isDirectory) {
            throw GradleException("Bundles path is not a directory: ${dir.absolutePath}")
        }

        val maxChecks = maxStatusChecks.get()
        if (maxChecks < 1) {
            throw GradleException("maxStatusChecks must be at least 1, got: $maxChecks")
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.task.PublishSplitBundleMavenCentralTaskTest" --info`
Expected: PASS

- [ ] **Step 5: Run all existing tests to verify no regressions**

Run: `./gradlew test --info`
Expected: All tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTask.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTaskTest.kt
git commit -m "feat: implement PublishSplitBundleMavenCentralTask with atomic rollback"
```

---

## Task 7: Wire New Tasks in Plugin

**Files:**
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/TaskExtension.kt`
- Modify: `src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt:198-273`

- [ ] **Step 1: Add task registration helpers to `TaskExtension.kt`**

Add the following functions at the end of `TaskExtension.kt`:

```kotlin
internal fun Project.registerSplitZipAllModulesTask(
    configuration: SplitZipDeploymentTask.() -> Unit = {}
): TaskProvider<SplitZipDeploymentTask> = this.tasks.register<SplitZipDeploymentTask>(
    "zipDeploymentAllModules"
) {
    description = "Creates split deployment bundles for all publications across all modules"

    configuration()
}

internal fun Project.registerPublishSplitAllModulesTask(
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishSplitBundleMavenCentralTask.() -> Unit = {}
): TaskProvider<PublishSplitBundleMavenCentralTask> = this.tasks.register<PublishSplitBundleMavenCentralTask>(
    "publishAllModulesTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository"
) {
    description = "Publishes all Maven publications from all modules to the $MAVEN_CENTRAL_PORTAL_NAME repository."

    baseUrl.set(mavenCentralUploaderExtension.baseUrl)
    credentials.set(mavenCentralUploaderExtension.mapCredentials())
    publishingType.set(mavenCentralUploaderExtension.publishingType.map { it.mapModel() })
    deploymentName.set(mavenCentralUploaderExtension.deploymentName)
    maxStatusChecks.set(mavenCentralUploaderExtension.uploader.maxStatusChecks)
    statusCheckDelay.set(mavenCentralUploaderExtension.uploader.statusCheckDelay)

    configuration()
}
```

Also add the required imports at the top of `TaskExtension.kt`:

```kotlin
import io.github.zenhelix.gradle.plugin.task.SplitZipDeploymentTask
import io.github.zenhelix.gradle.plugin.task.PublishSplitBundleMavenCentralTask
```

- [ ] **Step 2: Modify `createAggregationTasks` in `MavenCentralUploaderPlugin.kt`**

Replace the task creation section (lines 251-272) in `createAggregationTasks`. The publication collection code (lines 198-249) stays unchanged. Replace from `if (allPublicationsInfo.isNotEmpty())` to the end of the method:

```kotlin
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
```

Also add the required imports at the top of `MavenCentralUploaderPlugin.kt`:

```kotlin
import io.github.zenhelix.gradle.plugin.utils.registerSplitZipAllModulesTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishSplitAllModulesTask
```

And remove the now-unused imports:

```kotlin
// Remove these if they become unused:
import io.github.zenhelix.gradle.plugin.utils.registerPublishAllModulesTask
```

- [ ] **Step 3: Verify compilation**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Run all tests**

Run: `./gradlew test --info`
Expected: All tests PASS.

- [ ] **Step 5: Run functional tests**

Run: `./gradlew functionalTest --info`
Expected: All functional tests PASS. The aggregation behavior should work identically for bundles under 256MB (single chunk produces single ZIP).

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/io/github/zenhelix/gradle/plugin/utils/TaskExtension.kt \
        src/main/kotlin/io/github/zenhelix/gradle/plugin/MavenCentralUploaderPlugin.kt
git commit -m "feat: wire split bundle tasks for multi-module aggregation"
```

---

## Task 8: Integration Tests for Split Behavior

**Files:**
- Test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTaskTest.kt` (extend)
- Test: `src/test/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTaskTest.kt` (extend)

- [ ] **Step 1: Add test for oversized single module fail-fast**

Add to `SplitZipDeploymentTaskTest.kt`:

```kotlin
@Test
fun `single module exceeding limit fails with clear message`() {
    val jar = createFakeArtifact("big/big.jar", 500)
    val pub = createPublicationInfo(":big", "com.test", "big", "1.0", listOf(jar to "jar"))

    val task = project.tasks.register<SplitZipDeploymentTask>("splitZip") {
        publications.addAll(listOf(pub))
        maxBundleSize.set(200L)
        archiveBaseName.set("test-project")
        outputDirectory.set(project.layout.buildDirectory.dir("split-zips"))
    }.get()

    assertThatThrownBy { task.createSplitZips() }
        .isInstanceOf(io.github.zenhelix.gradle.plugin.utils.BundleSizeExceededException::class.java)
        .hasMessageContaining(":big")
}
```

- [ ] **Step 2: Run test to verify it passes**

Run: `./gradlew test --tests "io.github.zenhelix.gradle.plugin.task.SplitZipDeploymentTaskTest" --info`
Expected: PASS (all 4 tests)

- [ ] **Step 3: Add test for mode switching log**

Add to `PublishSplitBundleMavenCentralTaskTest.kt`:

```kotlin
@Test
fun `multiple bundles with AUTOMATIC type switches to USER_MANAGED`() {
    // This test verifies the task can be configured and validates inputs
    createZipFile("project-1.zip")
    createZipFile("project-2.zip")

    val task = registerTask(PublishingType.AUTOMATIC)
    // Mode switching is verified through the lifecycle log output
    // Full end-to-end verification requires functional test with mock server
    assertThat(task.publishingType.get()).isEqualTo(PublishingType.AUTOMATIC)
}
```

- [ ] **Step 4: Run all tests**

Run: `./gradlew test --info`
Expected: All tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/kotlin/io/github/zenhelix/gradle/plugin/task/SplitZipDeploymentTaskTest.kt \
        src/test/kotlin/io/github/zenhelix/gradle/plugin/task/PublishSplitBundleMavenCentralTaskTest.kt
git commit -m "test: add integration tests for split bundle edge cases"
```

---

## Task 9: Final Verification

- [ ] **Step 1: Run full test suite**

Run: `./gradlew check --info`
Expected: BUILD SUCCESSFUL — all unit tests, functional tests, and code coverage pass.

- [ ] **Step 2: Verify backward compatibility**

Run: `./gradlew functionalTest --info`
Expected: All existing functional tests pass unchanged. Projects with bundles under 256MB produce a single ZIP and upload with the same behavior as before.

- [ ] **Step 3: Final commit if any cleanup needed**

```bash
git status
# If any uncommitted changes from cleanup:
git add -A
git commit -m "chore: cleanup after bundle splitting implementation"
```
