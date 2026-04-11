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
 * Content configuration is done via [configureContent], which sets up the
 * CopySpec. Providers within the spec resolve lazily at execution time,
 * ensuring that artifacts added by late-configuring plugins (KMP, AGP)
 * are included in the bundle.
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

    public fun configureContent() {
        publications.get().forEach { info ->
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

}
