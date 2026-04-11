package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension.Companion.MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import io.github.zenhelix.gradle.plugin.task.ZipDeploymentTask
import io.github.zenhelix.gradle.plugin.utils.findMavenPublications
import io.github.zenhelix.gradle.plugin.utils.findPublishLifecycleTask
import io.github.zenhelix.gradle.plugin.utils.hasMavenCentralPortalExtension
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

/**
 * Gradle plugin for publishing artifacts to Maven Central via the Publisher API.
 *
 * Supports two publishing modes:
 * - **Independent (default):** Each project that applies the plugin publishes its own bundle.
 *   The `publish` lifecycle task depends on `publishAllPublicationsToMavenCentralPortalRepository`.
 * - **Atomic aggregation:** When applied to the root project and subprojects have publications,
 *   all modules are aggregated into a single deployment bundle. Subproject `publish` tasks
 *   do not trigger independent Maven Central uploads.
 *
 * Mode detection:
 * - Plugin on root + subprojects with publications → atomic aggregation
 * - Plugin on root only (no subproject publications) → independent (single-module)
 * - Plugin on subprojects only → independent per subproject (with warning)
 */
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
        } else {
            // Subproject: register one-time warning check on root
            val rootProject = target.rootProject
            if (!rootProject.extensions.extraProperties.has(WARN_REGISTERED_FLAG)) {
                rootProject.extensions.extraProperties[WARN_REGISTERED_FLAG] = true
                target.gradle.projectsEvaluated {
                    emitIndependentPublishingWarningIfNeeded(rootProject)
                }
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
                publicationInfo.checksumTask?.also { dependsOn(it) }

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

        // Wire publish lifecycle task to Maven Central Portal publish task
        project.findPublishLifecycleTask().configure {
            dependsOn(publishAllPublicationsTask)
        }
    }

    private fun configureRootProjectLifecycle(rootProject: Project, extension: MavenCentralUploaderExtension) {
        val subprojectsWithPublications = rootProject.subprojects.filter {
            it.findMavenPublications()?.isNotEmpty() == true
        }

        if (subprojectsWithPublications.isNotEmpty()) {
            // Mode 2: Atomic aggregation — create aggregation and override subproject wiring
            createAggregationTasks(rootProject, extension)

            rootProject.findPublishLifecycleTask().configure {
                rootProject.tasks.findByName("publishAllModulesToMavenCentralPortalRepository")?.also { dependsOn(it) }
            }

            // Remove per-project publish -> Maven Central wiring to avoid duplicate deployments
            // This applies to both subprojects and the root project itself
            val projectsToUnwire = subprojectsWithPublications + rootProject
            projectsToUnwire.forEach { project ->
                project.findPublishLifecycleTask().configure {
                    setDependsOn(dependsOn.filterNot { dep ->
                        val name = when (dep) {
                            is org.gradle.api.tasks.TaskProvider<*> -> dep.name
                            is org.gradle.api.Task -> dep.name
                            is String -> dep
                            else -> null
                        }
                        name == PUBLISH_ALL_PUBLICATIONS_TASK_NAME
                    })
                }
            }
        }
        // Mode 1 single-module: lifecycle wiring already done in configureZipDeploymentTasks
    }

    private fun emitIndependentPublishingWarningIfNeeded(rootProject: Project) {
        // Skip if root has the plugin (aggregation mode handles this)
        if (rootProject.hasMavenCentralPortalExtension()) {
            return
        }

        val subprojectsWithPlugin = rootProject.subprojects.filter { it.hasMavenCentralPortalExtension() }

        if (subprojectsWithPlugin.size > 1) {
            rootProject.logger.warn(
                "Multiple projects publish to Maven Central independently. " +
                "For atomic multi-module publishing, apply the plugin to the root project."
            )
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

        private val PUBLISH_ALL_PUBLICATIONS_TASK_NAME: String =
            "publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository"

        private const val WARN_REGISTERED_FLAG: String = "io.github.zenhelix.maven-central-publish.warnRegistered"
    }
}