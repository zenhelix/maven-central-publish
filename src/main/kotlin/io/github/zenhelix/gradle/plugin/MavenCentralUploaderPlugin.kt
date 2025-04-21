package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension.Companion.MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
import io.github.zenhelix.gradle.plugin.utils.aggregateModulePublications
import io.github.zenhelix.gradle.plugin.utils.findMavenPublications
import io.github.zenhelix.gradle.plugin.utils.findPublishLifecycleTask
import io.github.zenhelix.gradle.plugin.utils.mapModel
import io.github.zenhelix.gradle.plugin.utils.mavenPublication
import io.github.zenhelix.gradle.plugin.utils.registerCreateChecksumsAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerCreatePublicationChecksumsTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishPublicationTask
import io.github.zenhelix.gradle.plugin.utils.registerZipAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerZipPublicationTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.TaskDependency
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.plugins.signing.SigningPlugin

public class MavenCentralUploaderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        val mavenCentralUploaderExtension = target.createExtension()

        val publishLifecycleTask = target.findPublishLifecycleTask()

        val checksumsAllPublicationsTask = target.registerCreateChecksumsAllPublicationsTask()

        target.afterEvaluate {
            val mavenPublications = this.findMavenPublications() ?: emptyList()

            val allTaskDependencies = mutableListOf<TaskDependency>()
            mavenPublications.forEach { it.allPublishableArtifacts { allTaskDependencies.add(buildDependencies) } }

            val createChecksumsTasks = mavenPublications.associate { mavenPublication ->
                val publicationName = mavenPublication.name

                val createChecksumsTask = this.registerCreatePublicationChecksumsTask(publicationName) {
                    allTaskDependencies.forEach { this.dependsOn(it) }
                }

                publicationName to createChecksumsTask
            }
            createChecksumsTasks.values.forEach { checksumsAllPublicationsTask.configure { dependsOn(it) } }

            if (mavenCentralUploaderExtension.aggregateModulePublications() && mavenPublications.size > 1) {
                val zipAllPublicationsTask = this.registerZipAllPublicationsTask {
                    allTaskDependencies.forEach { this.dependsOn(it) }
                    dependsOn(checksumsAllPublicationsTask)

                    this.publicationInfos.set(mavenPublications.map { it.mapModel(createChecksumsTasks[it.name]) })

                    configureArtifacts()
                }

                val publishAllPublicationsTask = registerPublishAllPublicationsTask(mavenCentralUploaderExtension) {
                    dependsOn(zipAllPublicationsTask)

                    zipFile.set(zipAllPublicationsTask.flatMap { it.archiveFile })
                }

                publishLifecycleTask.configure { dependsOn(publishAllPublicationsTask) }

            } else {
                val zipAllPublicationsTask = target.registerZipAllPublicationsTask()
                val publishAllPublicationsTask = target.registerPublishAllPublicationsTask()

                val zipTasks = createChecksumsTasks.mapValues { (publicationName, createChecksumsTask) ->
                    val zipTask = this.registerZipPublicationTask(publicationName) {
                        allTaskDependencies.forEach { this.dependsOn(it) }
                        dependsOn(createChecksumsTask)

                        val publicationInfo = mavenPublication(publicationName).mapModel(createChecksumsTask)

                        if (mavenPublications.size > 1) {
                            this.archiveAppendix.set(publicationName)
                        }
                        this.publicationInfos.set(listOf(publicationInfo))

                        configureArtifacts()
                    }

                    zipTask
                }

                zipTasks.values.forEach { zipAllPublicationsTask.configure { dependsOn(it) } }

                val publishPublicationTasks = zipTasks.mapValues { (publicationName, zipTask) ->
                    val publishPublicationTask = registerPublishPublicationTask(publicationName, mavenCentralUploaderExtension) {
                        dependsOn(zipTask)

                        zipFile.set(zipTask.flatMap { it.archiveFile })
                    }

                    publishPublicationTask
                }

                publishPublicationTasks.values.forEach {
                    publishLifecycleTask.configure { dependsOn(it) }
                    publishAllPublicationsTask.configure { dependsOn(it) }
                }
            }
        }
    }

    private fun Project.createExtension() = this.extensions.create<MavenCentralUploaderExtension>(MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME)

    public companion object {
        public const val MAVEN_CENTRAL_PORTAL_NAME: String = "mavenCentralPortal"
        public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID: String = "io.github.zenhelix.maven-central-publish"
    }

}