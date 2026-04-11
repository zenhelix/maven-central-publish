package io.github.zenhelix.gradle.plugin.task

import java.io.File
import java.io.Serializable
import org.gradle.api.provider.ListProperty
import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider

public data class PublicationInfo(
    private val gav: GAV,
    val publicationName: String,
    val artifacts: ListProperty<ArtifactInfo>,
    val checksumTask: TaskProvider<CreateChecksumTask>? // TODO remove from dto
) : Serializable {

    val artifactPath: String by lazy {
        buildString(128) {
            append(gav.group.replace('.', '/')).append('/')
            append(gav.module).append('/')
            append(gav.version)
        }
    }
}

public data class GAV(val group: String, val module: String, val version: String) : Serializable {
    internal companion object {
        internal fun of(
            publication: MavenPublication
        ): GAV = GAV(publication.groupId, publication.artifactId, publication.version)
    }
}

public data class ArtifactInfo(
    private val artifact: ArtifactFileInfo,
    private val gav: GAV
) : Serializable {

    public fun file(): File = artifact.file

    val artifactPath: String by lazy {
        buildString(128) {
            append(gav.group.replace('.', '/')).append('/')
            append(gav.module).append('/')
            append(gav.version)
        }
    }

    val artifactName: String by lazy {
        buildString(128) {
            append(gav.module).append('-').append(gav.version)
            artifact.classifier?.also { append('-').append(it) }
            artifact.extension.takeIf { it.isNotEmpty() }?.also { append('.').append(it) }
        }
    }
}

public data class ArtifactFileInfo(
    public val file: File,
    public val classifier: String?,
    public val extension: String
) : Serializable {

    internal companion object {
        internal fun of(
            artifact: MavenArtifact
        ): ArtifactFileInfo = ArtifactFileInfo(artifact.file, artifact.classifier, artifact.extension)
    }
}