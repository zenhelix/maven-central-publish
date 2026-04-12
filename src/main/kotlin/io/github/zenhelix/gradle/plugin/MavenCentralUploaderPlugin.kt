package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.configurator.RootProjectConfigurator
import io.github.zenhelix.gradle.plugin.configurator.SubprojectConfigurator
import io.github.zenhelix.gradle.plugin.configurator.ZipDeploymentConfigurator
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension
import io.github.zenhelix.gradle.plugin.extension.MavenCentralUploaderExtension.Companion.MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.create
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
 * - Plugin on root + subprojects with publications -> atomic aggregation
 * - Plugin on root only (no subproject publications) -> independent (single-module)
 * - Plugin on subprojects only -> independent per subproject (with warning)
 */
public class MavenCentralUploaderPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        with(target.pluginManager) {
            apply(MavenPublishPlugin::class)
            apply(SigningPlugin::class)
        }

        val extension = target.extensions.create<MavenCentralUploaderExtension>(
            MAVEN_CENTRAL_UPLOADER_EXTENSION_NAME
        )

        ZipDeploymentConfigurator.configure(target, extension)

        if (target == target.rootProject) {
            RootProjectConfigurator.configure(target, extension)
        } else {
            SubprojectConfigurator.configure(target)
        }
    }

    public companion object {
        public const val MAVEN_CENTRAL_PORTAL_NAME: String = "mavenCentralPortal"
        public const val MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID: String = "io.github.zenhelix.maven-central-publish"
    }
}
