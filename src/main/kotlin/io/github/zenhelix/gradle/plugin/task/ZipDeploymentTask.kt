package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.provider.ListProperty
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.bundling.Zip

public abstract class ZipDeploymentTask : Zip() {

    @get:Internal
    public abstract val publicationInfos: ListProperty<PublicationInfo>

    init {
        group = PUBLISH_TASK_GROUP
    }

    public fun configureArtifacts() {
        publicationInfos.get().forEach { info ->
            into(info.artifactPath)
            from(info.checksumTask)
            info.artifacts.forEach { artifactInfo ->
                from(artifactInfo.file()) { rename { artifactInfo.artifactName } }
            }
        }
    }

}
