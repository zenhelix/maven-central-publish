package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.PublishingType
import io.github.zenhelix.gradle.plugin.task.ArtifactInfo
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.GAV
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.tasks.TaskProvider


internal fun MavenCentralUploaderExtension.mapCredentials() = credentials.username.flatMap { username ->
    credentials.password.map { password -> Credentials.UsernamePasswordCredentials(username, password) }
}

internal fun MavenPublicationInternal.mapModel(checksumTask: TaskProvider<CreateChecksumTask>?) = PublicationInfo(
    gav = GAV.of(this),
    publicationName = this.name,
    artifacts = this.publishableArtifacts.map { ArtifactInfo(artifact = it, gav = GAV.of(this)) },
    checksumTask = checksumTask
)

internal fun MavenCentralUploaderExtension.aggregateModulePublications() = this.uploader.aggregate.modulePublications.get()
internal fun MavenCentralUploaderExtension.aggregateModules() = this.uploader.aggregate.modules.get()

internal fun PublishingType.mapModel() = when (this) {
    PublishingType.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
    PublishingType.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
}