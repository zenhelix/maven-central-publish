package io.github.zenhelix.gradle.plugin.utils

import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

internal fun Project.mavenPublication(publicationName: String) = mavenPublications().getByName(publicationName)

internal fun Project.findMavenPublication(publicationName: String) = findMavenPublications()?.findByName(publicationName)

internal fun Project.mavenPublications() = this.extensions.getByType<PublishingExtension>().publications.withType<MavenPublicationInternal>()

internal fun Project.findMavenPublications() = this.extensions.findByType<PublishingExtension>()?.publications?.withType<MavenPublicationInternal>()

internal fun Project.findPublishLifecycleTask() = this.tasks.named(PUBLISH_LIFECYCLE_TASK_NAME)