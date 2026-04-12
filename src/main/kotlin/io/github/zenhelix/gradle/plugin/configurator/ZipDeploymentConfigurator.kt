package io.github.zenhelix.gradle.plugin.configurator

import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.PomExtension
import io.github.zenhelix.gradle.plugin.utils.findMavenPublications
import io.github.zenhelix.gradle.plugin.utils.findPublishLifecycleTask
import io.github.zenhelix.gradle.plugin.utils.mapModel
import io.github.zenhelix.gradle.plugin.utils.registerChecksumTask
import io.github.zenhelix.gradle.plugin.utils.registerChecksumsAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerPublishPublicationTask
import io.github.zenhelix.gradle.plugin.utils.registerZipAllPublicationsTask
import io.github.zenhelix.gradle.plugin.utils.registerZipPublicationTask
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectCollection
import org.gradle.api.Project
import org.gradle.api.publish.maven.MavenPom
import org.gradle.api.publish.maven.internal.publication.MavenPublicationInternal
import org.gradle.api.tasks.TaskDependency
import org.gradle.kotlin.dsl.listProperty

internal object ZipDeploymentConfigurator {

    fun configure(project: Project, extension: MavenCentralUploaderExtension) {
        project.afterEvaluate {
            configureZipDeploymentTasks(this, extension)
        }
    }

    private fun configureZipDeploymentTasks(project: Project, extension: MavenCentralUploaderExtension) {
        val publications = project.findMavenPublications() ?: return

        applyPomDefaults(extension.pom, publications)

        val checksumsAllPublicationsTask = project.registerChecksumsAllPublicationsTask()

        val zipAllPublicationsTask = project.registerZipAllPublicationsTask {
            archiveFileName.set(project.provider { "${project.name}-allPublications-${project.version}.zip" })
        }

        val publishAllPublicationsTask = project.registerPublishAllPublicationsTask(extension) {
            dependsOn(zipAllPublicationsTask)
            zipFile.set(zipAllPublicationsTask.flatMap { it.archiveFile })
        }

        project.findPublishLifecycleTask().configure {
            dependsOn(publishAllPublicationsTask)
        }

        publications.configureEach {
            val publication = this as MavenPublicationInternal
            val publicationName = publication.name

            val taskDependencies = project.objects.listProperty<TaskDependency>().apply {
                publication.allPublishableArtifacts { this@apply.addAll(buildDependencies) }
            }

            val checksumTask = project.registerChecksumTask(publicationName) {
                dependsOn(taskDependencies)
            }

            val publicationInfo = publication.mapModel(project, checksumTask)

            val zipTask = project.registerZipPublicationTask(publicationName) {
                dependsOn(taskDependencies)
                dependsOn(checksumTask)

                this.publications.add(publicationInfo)

                archiveFileName.set(project.provider { "${project.name}-${publicationName}-${project.version}.zip" })

                configureContentFor(publicationInfo)
            }

            project.registerPublishPublicationTask(publicationName, extension) {
                dependsOn(zipTask)
                zipFile.set(zipTask.flatMap { it.archiveFile })
            }

            checksumsAllPublicationsTask.configure {
                dependsOn(checksumTask)
            }

            zipAllPublicationsTask.configure {
                dependsOn(taskDependencies)
                dependsOn(checksumTask)

                this.publications.add(publicationInfo)

                configureContentFor(publicationInfo)
            }
        }
    }

    private fun applyPomDefaults(
        pomDefaults: PomExtension,
        publications: NamedDomainObjectCollection<MavenPublicationInternal>
    ) {
        publications.configureEach {
            pom {
                pomDefaults.name.orNull?.let { name.convention(it) }
                pomDefaults.description.orNull?.let { description.convention(it) }
                pomDefaults.url.orNull?.let { url.convention(it) }
                pomDefaults.inceptionYear.orNull?.let { inceptionYear.convention(it) }

                val licenseList = pomDefaults.licenses
                if (licenseList.isNotEmpty()) {
                    licenses {
                        licenseList.forEach { licenseData ->
                            license {
                                licenseData.name?.let { n -> name.set(n) }
                                licenseData.url?.let { u -> url.set(u) }
                                licenseData.distribution?.let { d -> distribution.set(d) }
                            }
                        }
                    }
                }

                val developerList = pomDefaults.developers
                if (developerList.isNotEmpty()) {
                    developers {
                        developerList.forEach { devData ->
                            developer {
                                devData.id?.let { v -> id.set(v) }
                                devData.name?.let { v -> name.set(v) }
                                devData.email?.let { v -> email.set(v) }
                                devData.url?.let { v -> url.set(v) }
                            }
                        }
                    }
                }

                val scmDefaults = pomDefaults.scm
                if (scmDefaults.connection.isPresent || scmDefaults.developerConnection.isPresent || scmDefaults.url.isPresent) {
                    scm {
                        scmDefaults.connection.orNull?.let { v -> connection.set(v) }
                        scmDefaults.developerConnection.orNull?.let { v -> developerConnection.set(v) }
                        scmDefaults.url.orNull?.let { v -> url.set(v) }
                    }
                }
            }
        }
    }
}
