package io.github.zenhelix.gradle.plugin.task

import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.ListProperty
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.bundling.Zip

/**
 * Task that creates ZIP deployment bundles for Maven Central Portal API.
 *
 * Use [configureContentFor] to add a single publication's artifacts to the CopySpec.
 * Each publication should be added exactly once to avoid duplicate entries.
 */
@CacheableTask
public abstract class ZipDeploymentTask : Zip() {

    @get:Internal
    public abstract val publications: ListProperty<PublicationInfo>

    init {
        group = PUBLISH_TASK_GROUP
        description = "Creates ZIP deployment bundle for Maven Central Portal API"

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    public fun configureContentFor(info: PublicationInfo) {
        info.checksumTask?.let { checksumTask ->
            from(checksumTask.flatMap { it.checksumFiles }) {
                into(info.artifactPath)
            }
        }

        info.artifacts.get().forEach { artifactInfo ->
            from(artifactInfo.file()) {
                into(info.artifactPath)
                rename { artifactInfo.artifactName }
            }
        }
    }

}
