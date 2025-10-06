package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension.Companion.MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import io.github.zenhelix.gradle.plugin.task.ZipDeploymentTask
import io.github.zenhelix.gradle.plugin.utils.findMavenPublications
import io.github.zenhelix.gradle.plugin.utils.findPublishLifecycleTask
import io.github.zenhelix.gradle.plugin.utils.mapModel
import io.github.zenhelix.gradle.plugin.utils.registerChecksumTask
import io.github.zenhelix.gradle.plugin.utils.registerChecksumsAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishAllModulesTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishPublicationTask
import io.github.zenhelix.gradle.plugin.utils.registerZipAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerZipPublicationTask
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.ListProperty
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.tasks.TaskDependency
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.plugins.signing.SigningPlugin

public class MavenCentralUploaderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        val mavenCentralUploaderExtension: MavenCentralUploaderExtension = target.createExtension()

        // Configure standard tasks for all projects (including root) in afterEvaluate
        target.afterEvaluate {
            configureZipDeploymentTasks(this, mavenCentralUploaderExtension)
        }

        // Configure lifecycle and aggregation only for root project
        if (target == target.rootProject) {
            target.gradle.projectsEvaluated {
                configureRootProjectLifecycle(target, mavenCentralUploaderExtension)
            }
        }
    }

    private fun configureZipDeploymentTasks(project: Project, extension: MavenCentralUploaderExtension) {
        val publications: NamedDomainObjectCollection<MavenPublicationInternal>? = project.findMavenPublications()
        if (publications.isNullOrEmpty()) {
            return
        }

        val allTaskDependencies: Map<String, ListProperty<TaskDependency>> =
            publications.associateBy({ it.name }) { publication ->
                project.objects.listProperty<TaskDependency>().apply {
                    publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
                }
            }

        val publicationInfos = publications.associateWith { publication ->
            val checksumTask = project.registerChecksumTask(publication.name) {
                dependsOn(allTaskDependencies.getValue(publication.name))
            }
            publication.mapModel(project, checksumTask)
        }

        publicationInfos.forEach { (publication, publicationInfo) ->
            val zipTask = project.registerZipPublicationTask(publication.name) {
                dependsOn(allTaskDependencies.getValue(publication.name))
                dependsOn(publicationInfo.checksumTask)

                this.publications.add(publicationInfo)

                archiveFileName.set(project.provider { "${project.name}-${publication.name}-${project.version}.zip" })

                configureContent()
            }

            val publishTask = project.registerPublishPublicationTask(publication.name, extension) {
                dependsOn(zipTask)

                zipFile.set(zipTask.flatMap { it.archiveFile })
            }
        }

        val checksumsAllPublicationsTask = project.registerChecksumsAllPublicationsTask {
            dependsOn(publicationInfos.values.mapNotNull { it.checksumTask })
        }

        val zipAllPublicationsTask = project.registerZipAllPublicationsTask {
            dependsOn(allTaskDependencies.values)
            dependsOn(checksumsAllPublicationsTask)

            this.publications.addAll(publicationInfos.values)

            archiveFileName.set(project.provider { "${project.name}-allPublications-${project.version}.zip" })

            configureContent()
        }

        val publishAllPublicationsTask = project.registerPublishAllPublicationsTask(extension) {
            dependsOn(zipAllPublicationsTask)

            zipFile.set(zipAllPublicationsTask.flatMap { it.archiveFile })
        }
    }

    private fun configureRootProjectLifecycle(rootProject: Project, extension: MavenCentralUploaderExtension) {
        val hasSubprojectsWithPublications = rootProject.subprojects.any {
            it.findMavenPublications()?.isNotEmpty() == true
        }

        if (hasSubprojectsWithPublications) {
            // Multi-module project: create aggregation and publish all modules together
            createAggregationTasks(rootProject, extension)

            rootProject.findPublishLifecycleTask().configure {
                dependsOn(rootProject.tasks.findByName("publishAllModulesToMavenCentralPortalRepository"))
            }
        } else {
            // Single-module project: publish root project publications only
            rootProject.findPublishLifecycleTask().configure {
                dependsOn(rootProject.tasks.findByName("publishAllPublicationsToMavenCentralPortalRepository"))
            }
        }
    }

    private fun createAggregationTasks(rootProject: Project, extension: MavenCentralUploaderExtension) {
        val allPublicationsInfo = mutableListOf<PublicationInfo>()
        val allChecksumsAndBuildTasks = mutableListOf<Any>()

        // Process root project publications if any
        val rootPublications = rootProject.findMavenPublications()
        if (!rootPublications.isNullOrEmpty()) {
            val rootTaskDependencies: Map<String, ListProperty<TaskDependency>> =
                rootPublications.associateBy({ it.name }) { publication ->
                    rootProject.objects.listProperty<TaskDependency>().apply {
                        publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
                    }
                }

            val rootPublicationInfos = rootPublications.associateWith { publication ->
                publication.mapModel(
                    rootProject,
                    rootProject.tasks.named<CreateChecksumTask>("checksum${publication.name.capitalized()}Publication")
                )
            }

            allPublicationsInfo.addAll(rootPublicationInfos.values)
            allChecksumsAndBuildTasks.addAll(rootTaskDependencies.values)
        }

        // Process subprojects
        rootProject.subprojects.forEach { subproject ->
            subproject.tasks.findByName("checksumAllPublications")?.let { checksumTask ->
                allChecksumsAndBuildTasks.add(checksumTask)
            }

            val publications = subproject.findMavenPublications()
            publications?.forEach { publication ->
                val publicationDependencies = subproject.objects.listProperty<TaskDependency>().apply {
                    publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
                }
                allChecksumsAndBuildTasks.add(publicationDependencies)

                val checksumTask = subproject.tasks.findByName("checksum${publication.name.capitalized()}Publication")

                if (checksumTask is CreateChecksumTask) {
                    val publicationInfo = publication.mapModel(
                        subproject,
                        subproject.tasks.named(
                            "checksum${publication.name.capitalized()}Publication",
                            CreateChecksumTask::class.java
                        )
                    )
                    allPublicationsInfo.add(publicationInfo)
                }
            }
        }

        if (allPublicationsInfo.isNotEmpty()) {
            val aggregatedZipTask = rootProject.tasks.register<ZipDeploymentTask>(
                "zipDeploymentAllModules"
            ) {
                description = "Creates deployment bundle for all publications across all modules"

                dependsOn(allChecksumsAndBuildTasks)

                this.publications.addAll(allPublicationsInfo)

                archiveFileName.set(rootProject.provider {
                    "${rootProject.name}-allModules-${rootProject.version}.zip"
                })

                configureContent()
            }

            rootProject.registerPublishAllModulesTask(extension) {
                dependsOn(aggregatedZipTask)
                zipFile.set(aggregatedZipTask.flatMap { it.archiveFile })
            }
        }
    }

    private fun Project.createExtension(
    ): MavenCentralUploaderExtension = this.extensions.create<MavenCentralUploaderExtension>(
        MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
    )

    public companion object {
        public const val MAVEN_CENTRAL_PORTAL_NAME: String = "mavenCentralPortal"
        public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID: String = "io.github.zenhelix.maven-central-publish"
    }
}