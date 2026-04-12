package io.github.zenhelix.gradle.plugin.configurator

import io.github.zenhelix.gradle.plugin.utils.hasMavenCentralPortalExtension
import org.gradle.api.Project

internal object SubprojectConfigurator {

    private const val WARN_REGISTERED_FLAG = "io.github.zenhelix.maven-central-publish.warnRegistered"

    fun configure(target: Project) {
        val rootProject = target.rootProject
        if (!rootProject.extensions.extraProperties.has(WARN_REGISTERED_FLAG)) {
            rootProject.extensions.extraProperties[WARN_REGISTERED_FLAG] = true
            target.gradle.projectsEvaluated {
                emitIndependentPublishingWarningIfNeeded(rootProject)
            }
        }
    }

    private fun emitIndependentPublishingWarningIfNeeded(rootProject: Project) {
        if (rootProject.hasMavenCentralPortalExtension()) {
            return
        }

        val subprojectsWithPlugin = rootProject.subprojects.filter { it.hasMavenCentralPortalExtension() }

        if (subprojectsWithPlugin.size > 1) {
            rootProject.logger.warn(
                "Multiple projects publish to Maven Central independently. For atomic multi-module publishing, apply the plugin to the root project."
            )
        }
    }
}
