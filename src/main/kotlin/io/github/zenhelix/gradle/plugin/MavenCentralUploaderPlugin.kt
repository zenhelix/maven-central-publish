package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.client.model.Credentials
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension.Companion.MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
import io.github.zenhelix.gradle.plugin.extension.PublishingType
import io.github.zenhelix.gradle.plugin.task.ArtifactInfo
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.GAV
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import io.github.zenhelix.gradle.plugin.task.PublishBundleMavenCentralTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.plugins.signing.SigningPlugin

public class MavenCentralUploaderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        val mavenCentralUploaderExtension =
            target.extensions.create<MavenCentralUploaderExtension>(MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME)

        val publishLifecycleTask = target.tasks.named(PUBLISH_LIFECYCLE_TASK_NAME)

        val publishAllPublicationsTask =
            target.tasks.register<Task>("publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository") {
                group = PUBLISH_TASK_GROUP
                description =
                    "Publishes all Maven publications produced by this project to the $MAVEN_CENTRAL_PORTAL_NAME repository."
            }

        val zipAllPublicationsTask = target.tasks.register<Task>("zipDeploymentAllPublications") {
            group = PUBLISH_TASK_GROUP
            description = "Deployment bundle for all publications"
        }

        if (target.rootProject == target) {
            val aggregateZipTask = target.tasks.register<Zip>("zipAggregateDeployment") {
                group = PUBLISH_TASK_GROUP
                description = "Create aggregate deployment bundle from all modules"

                onlyIf { mavenCentralUploaderExtension.uploader.aggregatePublications.get() }

                archiveBaseName.set("aggregate-${target.name}")
                if (target.version != Project.DEFAULT_VERSION) {
                    archiveVersion.set(target.version.toString())
                }
                duplicatesStrategy = DuplicatesStrategy.INCLUDE
            }

            val publishAggregateTask =
                target.tasks.register<PublishBundleMavenCentralTask>("publishAggregateToMavenCentralPortal") {
                    group = PUBLISH_TASK_GROUP
                    description = "Publishes aggregate deployment bundle to Maven Central"

                    onlyIf { mavenCentralUploaderExtension.uploader.aggregatePublications.get() }

                    zipFile.set(aggregateZipTask.flatMap { it.archiveFile })
                    baseUrl.set(mavenCentralUploaderExtension.baseUrl)
                    credentials.set(
                        mavenCentralUploaderExtension.credentials.username.flatMap { username ->
                            mavenCentralUploaderExtension.credentials.password.map { password ->
                                Credentials.UsernamePasswordCredentials(username, password)
                            }
                        }
                    )

                    publishingType.set(mavenCentralUploaderExtension.publishingType.map {
                        when (it) {
                            PublishingType.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
                            PublishingType.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
                        }
                    })

                    deploymentName.set(mavenCentralUploaderExtension.deploymentName)
                    maxRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.maxRetriesStatusCheck)
                    delayRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.delayRetriesStatusCheck)
                }

            publishLifecycleTask.configure {
                dependsOn(publishAggregateTask)
            }

            target.gradle.projectsEvaluated {
                if (mavenCentralUploaderExtension.uploader.aggregatePublications.get()) {
                    collectAllZipsFromSubprojects(target, aggregateZipTask)
                }
            }
        }

        target.afterEvaluate {

            val mavenPublications =
                this.extensions.findByType<PublishingExtension>()?.publications?.withType<MavenPublicationInternal>()

            val allTaskDependencies = mutableListOf<TaskDependency>()
            mavenPublications?.forEach { it.allPublishableArtifacts { allTaskDependencies.add(buildDependencies) } }

            mavenPublications?.forEach { publication ->
                val publicationName = publication.name

                val createChecksums =
                    this.tasks.register<CreateChecksumTask>("checksum${publicationName.capitalized()}") {
                        group = PUBLISH_TASK_GROUP
                        description = "Generate checksums for $publicationName"

                        allTaskDependencies.forEach { this.dependsOn(it) }

                        this.publicationName.set(publicationName)
                    }

                val zipTask = this.tasks.register<Zip>("zipDeployment${publicationName.capitalized()}Publication") {
                    group = PUBLISH_TASK_GROUP
                    description = "Deployment bundle for $publicationName"

                    allTaskDependencies.forEach { this.dependsOn(it) }
                    dependsOn(createChecksums)

                    if (mavenPublications.size > 1) {
                        archiveAppendix.set(publicationName)
                    }

                    from(createChecksums) {
                        val pub = project.extensions.getByType(PublishingExtension::class.java).publications.getByName(
                            publicationName
                        ) as MavenPublicationInternal
                        val publicationInfo = GAV(pub.groupId, pub.artifactId, pub.version).let { gav ->
                            PublicationInfo(
                                gav = gav,
                                artifacts = pub.publishableArtifacts.map { ArtifactInfo(artifact = it, gav = gav) }
                            )
                        }

                        into(publicationInfo.artifactPath)
                        publicationInfo.artifacts.forEach { artifactInfo ->
                            from(artifactInfo.file()) { rename { artifactInfo.artifactName } }
                        }
                    }
                }
                zipAllPublicationsTask.configure { dependsOn(zipTask) }

                val publishPublicationTask =
                    this.tasks.register<PublishBundleMavenCentralTask>("publish${publicationName.capitalized()}PublicationTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}") {
                        group = PUBLISH_TASK_GROUP
                        description =
                            "Publishes Maven publication '$publicationName' to Maven repository '$MAVEN_CENTRAL_PORTAL_NAME'."

                        onlyIf {
                            project.rootProject.extensions.findByType<MavenCentralUploaderExtension>()?.uploader?.aggregatePublications?.get() != true
                        }

                        dependsOn(zipTask)

                        zipFile.set(zipTask.flatMap { it.archiveFile })

                        baseUrl.set(mavenCentralUploaderExtension.baseUrl)
                        credentials.set(
                            mavenCentralUploaderExtension.credentials.username.flatMap { username ->
                                mavenCentralUploaderExtension.credentials.password.map { password ->
                                    Credentials.UsernamePasswordCredentials(username, password)
                                }
                            }
                        )

                        publishingType.set(mavenCentralUploaderExtension.publishingType.map {
                            when (it) {
                                PublishingType.AUTOMATIC -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.AUTOMATIC
                                PublishingType.USER_MANAGED -> io.github.zenhelix.gradle.plugin.client.model.PublishingType.USER_MANAGED
                            }
                        })
                        deploymentName.set(mavenCentralUploaderExtension.deploymentName)

                        maxRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.maxRetriesStatusCheck)
                        delayRetriesStatusCheck.set(mavenCentralUploaderExtension.uploader.delayRetriesStatusCheck)
                    }

                publishLifecycleTask.configure {
                    if (project.rootProject.extensions.findByType<MavenCentralUploaderExtension>()?.uploader?.aggregatePublications?.get() != true) {
                        dependsOn(publishPublicationTask)
                    }
                }
                publishAllPublicationsTask.configure {
                    if (project.rootProject.extensions.findByType<MavenCentralUploaderExtension>()?.uploader?.aggregatePublications?.get() != true) {
                        dependsOn(publishPublicationTask)
                    }
                }
            }
        }
    }

    private fun collectAllZipsFromSubprojects(rootProject: Project, aggregateZipTask: TaskProvider<Zip>) {
        val allSubprojects = rootProject.allprojects
        val zipTasks = mutableListOf<Pair<Project, String>>()

        allSubprojects.forEach { project ->
            project.tasks.names.filter { it.startsWith("zipDeployment") && it.endsWith("Publication") }
                .forEach { taskName ->
                    zipTasks.add(project to taskName)
                }
        }

        aggregateZipTask.configure {
            zipTasks.forEach { (project, taskName) ->
                val zipTaskProvider = project.tasks.named<Zip>(taskName)
                dependsOn(zipTaskProvider)

                from(zipTaskProvider.map { zipTask ->
                    project.layout.buildDirectory.dir("tmp/aggregate/${taskName}").map { tmpDir ->
                        tmpDir.asFile.mkdirs()
                        project.copy {
                            from(project.zipTree(zipTask.archiveFile))
                            into(tmpDir)
                        }
                        tmpDir.asFile
                    }
                })
            }
        }
    }

    public companion object {
        public const val MAVEN_CENTRAL_PORTAL_NAME: String = "mavenCentralPortal"
        public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID: String = "io.github.zenhelix.maven-central-publish"
    }
}