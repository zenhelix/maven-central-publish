package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.bundling.Zip

public abstract class ZipDeploymentTask : Zip() {

    @get:Internal
    public abstract val publicationInfo: Property<PublicationInfo>

    init {
        group = PUBLISH_TASK_GROUP
    }

    public fun configureArtifacts(vararg from: Any) {
        val info = publicationInfo.get()

        into(info.artifactPath)
        from(from)
        info.artifacts.forEach { artifactInfo ->
            from(artifactInfo.file()) { rename { artifactInfo.artifactName } }
        }
    }

}
