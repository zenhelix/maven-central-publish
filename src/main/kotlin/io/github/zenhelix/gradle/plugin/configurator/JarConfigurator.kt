package io.github.zenhelix.gradle.plugin.configurator

import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.bundling.Jar
import org.gradle.kotlin.dsl.findByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

internal object JarConfigurator {

    fun configure(project: Project, autoConfigureJars: Property<Boolean>) {
        project.afterEvaluate {
            if (autoConfigureJars.get()) {
                configureJars(this)
            }
        }
    }

    private fun configureJars(project: Project) {
        // Skip for Kotlin Multiplatform — KMP manages its own per-target source/javadoc jars
        if (project.plugins.hasPlugin("org.jetbrains.kotlin.multiplatform")) return

        val javadocJarTask = registerJavadocJarIfAbsent(project)
        val sourcesJarTask = registerSourcesJarIfAbsent(project)

        if (javadocJarTask != null || sourcesJarTask != null) {
            project.extensions.findByType<PublishingExtension>()
                ?.publications
                ?.withType<MavenPublication>()
                ?.configureEach {
                    javadocJarTask?.let { artifact(it) }
                    sourcesJarTask?.let { artifact(it) }
                }
        }
    }

    private fun registerJavadocJarIfAbsent(project: Project): Any? {
        if (project.tasks.findByName("javadocJar") != null) return null

        return project.tasks.register<Jar>("javadocJar") {
            archiveClassifier.set("javadoc")

            if (project.plugins.hasPlugin("org.jetbrains.dokka")) {
                val dokkaTask = project.tasks.findByName("dokkaHtml")
                    ?: project.tasks.findByName("dokkaJavadoc")
                if (dokkaTask != null) {
                    dependsOn(dokkaTask)
                    from(dokkaTask.outputs)
                }
            }
            // Empty javadoc jar if no Dokka — this is standard practice for Kotlin libraries
        }
    }

    private fun registerSourcesJarIfAbsent(project: Project): Any? {
        if (project.tasks.findByName("sourcesJar") != null) return null

        return project.tasks.register<Jar>("sourcesJar") {
            archiveClassifier.set("sources")

            project.extensions.findByType<JavaPluginExtension>()
                ?.sourceSets
                ?.findByName("main")
                ?.allSource
                ?.let { from(it) }
        }
    }
}
