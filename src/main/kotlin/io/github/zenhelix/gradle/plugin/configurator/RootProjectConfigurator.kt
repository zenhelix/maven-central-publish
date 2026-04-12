package io.github.zenhelix.gradle.plugin.configurator

import io.github.zenhelix.gradle.plugin.MavenCentralUploaderPlugin.Companion.MAVEN_CENTRAL_PORTAL_NAME
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.task.CreateChecksumTask
import io.github.zenhelix.gradle.plugin.task.PublicationInfo
import io.github.zenhelix.gradle.plugin.utils.findMavenPublications
import io.github.zenhelix.gradle.plugin.utils.findPublishLifecycleTask
import io.github.zenhelix.gradle.plugin.utils.mapModel
import io.github.zenhelix.gradle.plugin.utils.registerPublishSplitAllModulesTask
import io.github.zenhelix.gradle.plugin.utils.registerSplitZipAllModulesTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.tasks.TaskDependency
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.extensions.stdlib.capitalized
import org.gradle.kotlin.dsl.listProperty

internal object RootProjectConfigurator {

    private val PUBLISH_ALL_PUBLICATIONS_TASK_NAME =
        "publishAllPublicationsTo${MAVEN_CENTRAL_PORTAL_NAME.capitalized()}Repository"

    fun configure(rootProject: Project, extension: MavenCentralUploaderExtension) {
        rootProject.gradle.projectsEvaluated {
            configureRootProjectLifecycle(rootProject, extension)
        }
    }

    private fun configureRootProjectLifecycle(rootProject: Project, extension: MavenCentralUploaderExtension) {
        val subprojectsWithPublications = rootProject.subprojects.filter {
            it.findMavenPublications()?.isNotEmpty() == true
        }

        if (subprojectsWithPublications.isNotEmpty()) {
            createAggregationTasks(rootProject, extension)

            rootProject.findPublishLifecycleTask().configure {
                rootProject.tasks.findByName("publishAllModulesToMavenCentralPortalRepository")?.also { dependsOn(it) }
            }

            val projectsToUnwire = subprojectsWithPublications + rootProject
            projectsToUnwire.forEach { project ->
                project.findPublishLifecycleTask().configure {
                    setDependsOn(dependsOn.filterNot { dep ->
                        dep.taskNameOrNull() == PUBLISH_ALL_PUBLICATIONS_TASK_NAME
                    })
                }
            }
        }
    }

    private fun createAggregationTasks(rootProject: Project, extension: MavenCentralUploaderExtension) {
        val (allPublicationsInfo, allChecksumsAndBuildTasks) = collectAggregationData(rootProject)

        if (allPublicationsInfo.isNotEmpty()) {
            val splitZipTask = rootProject.registerSplitZipAllModulesTask {
                dependsOn(allChecksumsAndBuildTasks)

                this.publications.addAll(allPublicationsInfo)

                maxBundleSize.set(extension.uploader.maxBundleSize)
                archiveBaseName.set(rootProject.provider {
                    "${rootProject.name}-allModules-${rootProject.version}"
                })
                outputDirectory.set(
                    rootProject.layout.buildDirectory.dir("maven-central-split-bundles")
                )
            }

            rootProject.registerPublishSplitAllModulesTask(extension) {
                dependsOn(splitZipTask)
                bundlesDirectory.set(splitZipTask.flatMap { it.outputDirectory })
            }
        }
    }

    private fun collectAggregationData(
        rootProject: Project
    ): Pair<List<PublicationInfo>, List<Any>> {
        val rootPublications = rootProject.findMavenPublications()
        val subprojectPublications = rootProject.subprojects.associateWith { it.findMavenPublications() }

        val allPublicationsInfo = buildList {
            if (!rootPublications.isNullOrEmpty()) {
                rootPublications.forEach { publication ->
                    val checksumTaskName = "checksum${publication.name.capitalized()}Publication"
                    val checksumTask = rootProject.tasks.findByName(checksumTaskName)
                    if (checksumTask is CreateChecksumTask) {
                        add(
                            publication.mapModel(
                                rootProject,
                                rootProject.tasks.named(checksumTaskName, CreateChecksumTask::class.java)
                            )
                        )
                    }
                }
            }

            subprojectPublications.forEach { (subproject, publications) ->
                publications?.forEach { publication ->
                    val checksumTaskName = "checksum${publication.name.capitalized()}Publication"
                    val checksumTask = subproject.tasks.findByName(checksumTaskName)
                    if (checksumTask is CreateChecksumTask) {
                        add(publication.mapModel(
                            subproject,
                            subproject.tasks.named(checksumTaskName, CreateChecksumTask::class.java)
                        ))
                    }
                }
            }
        }

        val allChecksumsAndBuildTasks = buildList<Any> {
            if (!rootPublications.isNullOrEmpty()) {
                rootPublications.forEach { publication ->
                    val deps = rootProject.objects.listProperty<TaskDependency>().apply {
                        publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
                    }
                    add(deps)

                    rootProject.tasks.findByName("checksum${publication.name.capitalized()}Publication")?.let {
                        add(it)
                    }
                }
            }

            subprojectPublications.forEach { (subproject, publications) ->
                subproject.tasks.findByName("checksumAllPublications")?.let { add(it) }

                publications?.forEach { publication ->
                    val deps = subproject.objects.listProperty<TaskDependency>().apply {
                        publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
                    }
                    add(deps)
                }
            }
        }

        return allPublicationsInfo to allChecksumsAndBuildTasks
    }
}

/**
 * Extracts a task name from various Gradle dependency types.
 */
private fun Any.taskNameOrNull(): String? = when (this) {
    is TaskProvider<*> -> name
    is Task -> name
    is String -> this
    else -> null
}
