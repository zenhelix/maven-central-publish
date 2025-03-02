package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.publish.maven.MavenArtifact
import java.io.File
import java.io.Serializable

internal data class PublicationInfo(
    private val gav: GAV,
    val artifacts: List<ArtifactInfo>
) {

    val artifactPath: String by lazy {
        buildString(128) {
            append(gav.group.replace('.', '/')).append('/')
            append(gav.module).append('/')
            append(gav.version)
        }
    }
}

public data class GAV(val group: String, val module: String, val version: String)

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
