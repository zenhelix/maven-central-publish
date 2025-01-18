package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.DefaultTask
import org.gradle.api.file.FileCollection
import org.gradle.api.internal.artifacts.repositories.resolver.ExternalResourceResolver
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.hash.HashFunction
import org.gradle.internal.hash.Hashing
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale

public abstract class CreateChecksumTask : DefaultTask() {

    private val algorithms = setOf(Hashing.sha1(), Hashing.md5()).let {
        it + if (!ExternalResourceResolver.disableExtraChecksums()) {
            setOf(Hashing.sha256(), Hashing.sha512())
        } else {
            emptySet()
        }
    }

    @Internal
    public lateinit var sources: List<ArtifactInfo>

    @InputFiles
    public fun getSourceFiles(): List<File> = sources.map { it.file() }

    @Input
    public fun getArtifactNames(): List<String> = sources.map { it.artifactName }

    @OutputFiles
    public fun getOutputFiles(): FileCollection = project.files(
        sources.flatMap { file -> algorithms.map { hashFunction -> fileHash(file, hashFunction) } }
    )

    private fun fileHash(source: ArtifactInfo, hashFunction: HashFunction) =
        File(
            project.layout.buildDirectory
                .dir("checksums")
                .map { checksumsDir -> source.file().parentFile?.name?.let { checksumsDir.dir(it) } ?: checksumsDir }
                .get().asFile,
            "${source.artifactName}.${hashFunction.algorithm.lowercase(Locale.ROOT).replace("-", "")}"
        )

    @TaskAction
    public fun createChecksums() {
        sources.forEach { sourceFile ->
            algorithms.forEach { hashFunction ->
                val checksumFile = fileHash(sourceFile, hashFunction)
                checksumFile.writeBytes(checksum(sourceFile.file(), hashFunction))
            }
        }
    }

    private fun checksum(content: File, hashFunction: HashFunction): ByteArray {
        val hash = hashFunction.hashFile(content)
        val formattedHashString = hash.toZeroPaddedString(hashFunction.hexDigits)
        return formattedHashString.toByteArray(StandardCharsets.US_ASCII)
    }

}