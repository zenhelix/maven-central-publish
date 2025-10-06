package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.MavenCentralUploaderPlugin.Companion.MAVEN_CENTRAL_PORTAL_NAME
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.PublishBundleMavenCentralTask
import io.github.zenhelix.gradle.plugin.task.ZipDeploymentTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.register

internal fun Project.registerZipAllPublicationsTask(
    configuration: ZipDeploymentTask.() -> Unit = {}
): TaskProvider<ZipDeploymentTask> = this.tasks.register<ZipDeploymentTask>("zipDeploymentAllPublications") {
    description = "Deployment bundle for all publications"

    configuration()
}

internal fun Project.registerZipPublicationTask(
    publicationName: String,
    configuration: ZipDeploymentTask.() -> Unit = {}
): TaskProvider<ZipDeploymentTask> = this.tasks.register<ZipDeploymentTask>(
    "zipDeployment${publicationName.capitalized()}Publication"
) {
    description = "Deployment bundle for $publicationName"

    configuration()
}

internal fun Project.registerPublishAllPublicationsTask(
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishBundleMavenCentralTask.() -> Unit = {}
): TaskProvider<PublishBundleMavenCentralTask> = registerPublishBundleMavenCentralTask(
    "publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository", mavenCentralUploaderExtension
) {
    description =
        "Publishes all Maven publications produced by this project to the $MAVEN_CENTRAL_PORTAL_NAME repository."

    configuration()
}

internal fun Project.registerPublishPublicationTask(
    publicationName: String,
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishBundleMavenCentralTask.() -> Unit = {}
): TaskProvider<PublishBundleMavenCentralTask> = registerPublishBundleMavenCentralTask(
    "publish${publicationName.capitalized()}PublicationTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository",
    mavenCentralUploaderExtension
) {
    description = "Publishes Maven publication '$publicationName' to Maven repository '$MAVEN_CENTRAL_PORTAL_NAME'."

    configuration()
}

internal fun Project.registerChecksumsAllPublicationsTask(
    configuration: Task.() -> Unit = {}
): TaskProvider<Task> = this.tasks.register<Task>("checksumAllPublications") {
    group = PUBLISH_TASK_GROUP
    description = "Generate checksums"

    configuration()
}

internal fun Project.registerChecksumTask(
    publicationName: String,
    configuration: CreateChecksumTask.() -> Unit = {}
): TaskProvider<CreateChecksumTask> = this.tasks.register<CreateChecksumTask>(
    "checksum${publicationName.capitalized()}Publication"
) {
    description = "Generate checksums for $publicationName"

    this.publicationName.set(publicationName)

    configureFromMavenPublication(publicationName)

    configuration()
}

internal fun Project.registerPublishAllModulesTask(
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishBundleMavenCentralTask.() -> Unit = {}
): TaskProvider<PublishBundleMavenCentralTask> = registerPublishBundleMavenCentralTask(
    "publishAllModulesTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository",
    mavenCentralUploaderExtension
) {
    description = "Publishes all Maven publications from all modules to the $MAVEN_CENTRAL_PORTAL_NAME repository."
    configuration()
}

private fun Project.registerPublishBundleMavenCentralTask(
    name: String,
    mavenCentralUploaderExtension: MavenCentralUploaderExtension,
    configuration: PublishBundleMavenCentralTask.() -> Unit = {}
): TaskProvider<PublishBundleMavenCentralTask> = this.tasks.register<PublishBundleMavenCentralTask>(name) {
    baseUrl.set(mavenCentralUploaderExtension.baseUrl)
    credentials.set(mavenCentralUploaderExtension.mapCredentials())
    publishingType.set(mavenCentralUploaderExtension.publishingType.map { it.mapModel() })
    deploymentName.set(mavenCentralUploaderExtension.deploymentName)
    maxStatusChecks.set(mavenCentralUploaderExtension.uploader.maxStatusChecks)
    statusCheckDelay.set(mavenCentralUploaderExtension.uploader.statusCheckDelay)

    configuration()
}