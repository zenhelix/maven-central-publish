package io.github.zenhelix.gradle.plugin.task

import io.github.zenhelix.gradle.plugin.client.model.toGradleException
import io.github.zenhelix.gradle.plugin.client.BundleChunker
import io.github.zenhelix.gradle.plugin.client.Chunk
import io.github.zenhelix.gradle.plugin.client.ModuleSize
import io.github.zenhelix.gradle.plugin.extension.toDisplayKB
import io.github.zenhelix.gradle.plugin.extension.toDisplayMB
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
        val maxSize = maxBundleSize.get()
        require(maxSize > 0) { "maxBundleSize must be positive, got: $maxSize" }

        val outputDir = outputDirectory.get().asFile
        // Clean stale ZIPs from previous runs to prevent uploading outdated chunks
        if (outputDir.exists()) {
            outputDir.listFiles { f -> f.extension == "zip" }?.forEach { it.delete() }
        }
        outputDir.mkdirs()

        val modulePublications = publications.get().groupBy { it.projectPath }

        val moduleSizes = modulePublications.map { (projectPath, pubs) ->
            val totalSize = pubs.sumOf { pub -> calculatePublicationSize(pub) }
            ModuleSize(projectPath, totalSize)
        }

        val chunks = BundleChunker.chunk(moduleSizes, maxSize).fold(
            onSuccess = { it },
            onFailure = { throw it.toGradleException() }
        )

        if (chunks.isEmpty()) {
            logger.lifecycle("No publications to bundle")
            return
        }

        if (chunks.size > 1) {
            val chunkDescriptions = chunks.mapIndexed { index, chunk ->
                val modulesStr = chunk.moduleNames.joinToString(", ")
                "chunk-${index + 1}: ${chunk.totalSize.toDisplayMB()}MB (modules: $modulesStr)"
            }
            logger.lifecycle("Bundle split into ${chunks.size} chunks: [${chunkDescriptions.joinToString(", ")}]")
        }

        val baseName = archiveBaseName.get()
        chunks.forEachIndexed { index, chunk ->
            val zipFile = File(outputDir, "$baseName-${index + 1}.zip")
            createZipForChunk(zipFile, chunk, modulePublications)
            logger.lifecycle("Created bundle: ${zipFile.name} (${zipFile.length().toDisplayKB()}KB)")
        }
    }

    private fun calculatePublicationSize(publication: PublicationInfo): Long =
        publication.artifacts.get().sumOf { it.file().length() } +
                (publication.checksumFiles?.get()?.sumOf { it.asFile.length() } ?: 0L)

    private fun createZipForChunk(
        zipFile: File,
        chunk: Chunk,
        modulePublications: Map<String, List<PublicationInfo>>
    ) {
        val addedEntries = mutableSetOf<String>()

        ZipOutputStream(zipFile.outputStream()).use { zos ->
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
        publication.checksumFiles?.get()?.forEach { checksumFile ->
            val entryPath = "${publication.artifactPath}/${checksumFile.asFile.name}"
            if (addedEntries.add(entryPath)) {
                zos.putNextEntry(ZipEntry(entryPath))
                try {
                    checksumFile.asFile.inputStream().buffered().use { it.copyTo(zos) }
                } finally {
                    zos.closeEntry()
                }
            }
        }

        publication.artifacts.get().forEach { artifactInfo ->
            val entryPath = "${publication.artifactPath}/${artifactInfo.artifactName}"
            if (addedEntries.add(entryPath)) {
                zos.putNextEntry(ZipEntry(entryPath))
                try {
                    artifactInfo.file().inputStream().buffered().use { it.copyTo(zos) }
                } finally {
                    zos.closeEntry()
                }
            }
        }
    }
}
