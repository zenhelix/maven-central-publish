package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.PublishingType
import io.github.zenhelix.gradle.plugin.task.ArtifactFileInfo
import io.github.zenhelix.gradle.plugin.task.ArtifactInfo
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.GAV
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.listProperty

internal fun MavenCentralUploaderExtension.mapCredentials(
): Provider<Credentials.UsernamePasswordCredentials> = credentials.username.flatMap { username ->
    credentials.password.map { password -> Credentials.UsernamePasswordCredentials(username, password) }
}

internal fun MavenPublicationInternal.mapModel(
    project: Project,
    checksumTask: TaskProvider<CreateChecksumTask>
): PublicationInfo = PublicationInfo(
    gav = GAV.of(this),
    publicationName = this.name,
    artifacts = project.objects.listProperty<ArtifactInfo>().apply {
        convention(project.provider {
            this@mapModel.publishableArtifacts.map {
                ArtifactInfo(artifact = ArtifactFileInfo.of(it), gav = GAV.of(this@mapModel))
            }
        })
    },
    checksumTask = checksumTask
)

internal fun PublishingType.mapModel(): io.github.zenhelix.gradle.plugin.client.model.PublishingType = when (this) {
    PublishingType.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
    PublishingType.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
}