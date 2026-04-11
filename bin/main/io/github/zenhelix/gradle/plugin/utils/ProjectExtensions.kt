package io.github.zenhelix.gradle.plugin.utils

import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.NamedDomainObjectProvider
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

internal fun Project.mavenPublication(publicationName: String): NamedDomainObjectProvider<MavenPublicationInternal> =
    mavenPublications().named(publicationName)

internal fun Project.mavenPublications(): NamedDomainObjectCollection<MavenPublicationInternal> =
    this.extensions.getByType<PublishingExtension>().publications.withType<MavenPublicationInternal>()

internal fun Project.findMavenPublications(): NamedDomainObjectCollection<MavenPublicationInternal>? =
    this.extensions.findByType<PublishingExtension>()?.publications?.withType<MavenPublicationInternal>()

internal fun Project.findPublishLifecycleTask(): TaskProvider<Task> = this.tasks.named(PUBLISH_LIFECYCLE_TASK_NAME)
