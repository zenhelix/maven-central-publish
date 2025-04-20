package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.artifacts.repositories.resolver.ExternalResourceResolver
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.hash.HashFunction
import org.gradle.internal.hash.Hashing
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.Sign
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

public abstract class CreateChecksumTask : DefaultTask() {

    private val filteredArtifacts: List<ArtifactInfo>
        get() {
            val publication = project.extensions.getByType(PublishingExtension::class.java)
                .publications.getByName(publicationName.get()) as MavenPublicationInternal

            val gav = GAV(publication.groupId, publication.artifactId, publication.version)
            val signatureFiles = project.tasks.withType<Sign>().flatMap { it.signatureFiles }.toSet()

            return publication.publishableArtifacts
                .filterNot { signatureFiles.contains(it.file) }
                .map { ArtifactInfo(it, gav) }
        }

    @get:Input
    public abstract val publicationName: Property<String>

    @get:OutputFiles
    public val outputChecksumFiles: Provider<FileCollection> =
        project.provider {
            filteredArtifacts.flatMap { artifact ->
                algorithms.map { hashFunction -> fileHash(artifact, hashFunction) }
            }.let { project.files(it) }
        }

    init {
        group = PUBLISH_TASK_GROUP
    }

    @TaskAction
    public fun createChecksums() {
        filteredArtifacts.forEach { artifact ->
            algorithms.forEach { hashFunction ->
                val checksumFile = fileHash(artifact, hashFunction)
                checksumFile.parentFile.mkdirs()
                checksumFile.writeBytes(checksum(artifact.file(), hashFunction))
            }
        }
    }

    private fun checksum(content: File, hashFunction: HashFunction): ByteArray {
        val hash = hashFunction.hashFile(content)
        val formattedHashString = hash.toZeroPaddedString(hashFunction.hexDigits)
        return formattedHashString.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun fileHash(source: ArtifactInfo, hashFunction: HashFunction) = File(
        project.layout.buildDirectory
            .dir("checksums")
            .map { checksumsDir -> source.file().parentFile?.name?.let { checksumsDir.dir(it) } ?: checksumsDir }
            .get().asFile,
        "${source.artifactName}.${hashFunction.algorithm.lowercase(Locale.ROOT).replace("-", "")}"
    )

    private companion object {
        private val algorithms = setOf(Hashing.sha1(), Hashing.md5()).let {
            it + if (!ExternalResourceResolver.disableExtraChecksums()) {
                setOf(Hashing.sha256(), Hashing.sha512())
            } else {
                emptySet()
            }
        }
    }
}