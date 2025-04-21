package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.publish.maven.MavenArtifact
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.io.Serializable

public data class PublicationInfo(
    private val gav: GAV,
    val publicationName: String,
    val artifacts: List<ArtifactInfo>,
    val checksumTask: TaskProvider<CreateChecksumTask>
) {

    val artifactPath: String by lazy {
        buildString(128) {
            append(gav.group.replace('.', '/')).append('/')
            append(gav.module).append('/')
            append(gav.version)
        }
    }
}

public data class GAV(val group: String, val module: String, val version: String) {
    internal companion object {
        internal fun of(publication: MavenPublication) = GAV(publication.groupId, publication.artifactId, publication.version)
    }
}

public data class ArtifactInfo(
    private val artifact: MavenArtifact,
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