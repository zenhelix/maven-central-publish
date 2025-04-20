package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.provider.Property
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.bundling.Zip

public abstract class ZipDeploymentTask : Zip() {

    @get:Internal
    public abstract val publicationInfo: Property<PublicationInfo>

    @get:Internal
    public abstract val publicationName: Property<String>

    @get:Internal
    public abstract val needModuleName: Property<Boolean>

    init {
        group = PUBLISH_TASK_GROUP

        archiveAppendix.convention(project.provider {
            if (needModuleName.getOrElse(false)) {
                publicationName.getOrElse("")
            } else {
                ""
            }
        })
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
