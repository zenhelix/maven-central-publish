package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.MavenCentralUploaderPlugin.Companion.MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID
import java.io.File
import java.nio.file.Path
import java.util.zip.ZipFile
import org.gradle.api.publish.plugins.PublishingPlugin.PUBLISH_TASK_GROUP
import org.gradle.internal.extensions.stdlib.capitalized
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import test.buildGradleFile
import test.createJavaMainClass
import test.createKotlinCommonMainClass
import test.distributionsDirectory
import test.group
import test.mavenCentralPortal
import test.moduleBundleFile
import test.moduleBundlePath
import test.pom
import test.settings
import test.settingsGradleFile
import test.signing
import test.testkit.BuildOutputAssert
import test.testkit.DirectoryAssert
import test.testkit.GradleDryRunOutputAssert
import test.testkit.GradleTasksOutputAssert
import test.testkit.gradleDryRunRunner
import test.testkit.gradleRunnerDebug
import test.testkit.gradleTasksRunner
import test.utils.ZipFileAssert.Companion.assertThat
import test.utils.containsMavenArtifacts
import test.utils.containsSomeMavenArtifacts

class MavenCentralUploaderPluginFunctionalTest {

    @TempDir
    private lateinit var testProjectDir: File

    @Test
    fun `zip deployment bundles for platform and catalog`() {
        val version = "0.1.0"
        val tomlModuleName = "platform-toml"
        val bomModuleName = "platform-bom"

        testProjectDir.settingsGradleFile().writeText(settings("test", tomlModuleName, bomModuleName))
        //language=kotlin
        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                `java-platform`
                `version-catalog`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            configure(subprojects.filter { it.name.contains("-bom") || it.name.contains("-toml") }) {
                apply {
                    plugin("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
                }
                
                publishing {
                    repositories {
                        mavenLocal()
                        ${mavenCentralPortal()}
                    }
                }
            
                ${signing()}
                $pom
            }
            
            configure(subprojects.filter { it.name.contains("-bom") }) {
                apply { plugin("java-platform") }
            
                javaPlatform {
                    allowDependencies()
                }
            
                publishing {
                    publications {
                        create<MavenPublication>("javaPlatform") {
                            from(components["javaPlatform"])
                        }
                    }
                }
            
            }
            
            configure(subprojects.filter { it.name.contains("-toml") }) {
                apply { plugin("version-catalog") }
            
                publishing {
                    publications {
                        create<MavenPublication>("versionCatalog") {
                            from(components["versionCatalog"])
                        }
                    }
                }
            
            }
            """.trimIndent()
        )

        gradleRunnerDebug(testProjectDir) {
            withVersion(version)
            withTask("zipDeploymentAllPublications")
        }

        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory(tomlModuleName)).containsExactlyFiles(
            Path.of("").moduleBundlePath(tomlModuleName, version, "allPublications").toString()
        )
        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory(bomModuleName)).containsExactlyFiles(
            Path.of("").moduleBundlePath(bomModuleName, version, "allPublications").toString()
        )

        assertThat(
            ZipFile(
                testProjectDir.moduleBundleFile(tomlModuleName, tomlModuleName, version, "allPublications").toFile()
            )
        ).containsMavenArtifacts("test.zenhelix", tomlModuleName, version) {
            versionCatalog()
        }.isEqualTextContentTo(
            "test/zenhelix/platform-toml/$version/platform-toml-$version.pom",
            //language=XML
            """<?xml version="1.0" encoding="UTF-8"?>
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <!-- This module was also published with a richer model, Gradle metadata,  -->
  <!-- which should be used instead. Do not delete the following line which  -->
  <!-- is to indicate to Gradle or any Gradle module metadata file consumer  -->
  <!-- that they should prefer consuming it instead. -->
  <!-- do_not_remove: published-with-gradle-metadata -->
  <modelVersion>4.0.0</modelVersion>
  <groupId>test.zenhelix</groupId>
  <artifactId>platform-toml</artifactId>
  <version>0.1.0</version>
  <packaging>toml</packaging>
  <description>stub description</description>
  <url>https://stub.stub</url>
  <licenses>
    <license>
      <name>The Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>stub</id>
      <name>Stub Stub</name>
      <email>stub@stub.stub</email>
    </developer>
  </developers>
  <scm>
    <connection>scm:git:git://stub.stub.git</connection>
    <developerConnection>scm:git:ssh://stub.stub.git</developerConnection>
    <url>https://stub.stub</url>
  </scm>
</project>"""
        )

        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(bomModuleName, bomModuleName, version, "allPublications").toFile())
        ).containsMavenArtifacts("test.zenhelix", bomModuleName, version) {
            gradlePlatform()
        }.isEqualTextContentTo(
            "test/zenhelix/$bomModuleName/$version/$bomModuleName-$version.pom",
            //language=XML
            """<?xml version="1.0" encoding="UTF-8"?>
<project xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <!-- This module was also published with a richer model, Gradle metadata,  -->
  <!-- which should be used instead. Do not delete the following line which  -->
  <!-- is to indicate to Gradle or any Gradle module metadata file consumer  -->
  <!-- that they should prefer consuming it instead. -->
  <!-- do_not_remove: published-with-gradle-metadata -->
  <modelVersion>4.0.0</modelVersion>
  <groupId>test.zenhelix</groupId>
  <artifactId>platform-bom</artifactId>
  <version>0.1.0</version>
  <packaging>pom</packaging>
  <description>stub description</description>
  <url>https://stub.stub</url>
  <licenses>
    <license>
      <name>The Apache License, Version 2.0</name>
      <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>stub</id>
      <name>Stub Stub</name>
      <email>stub@stub.stub</email>
    </developer>
  </developers>
  <scm>
    <connection>scm:git:git://stub.stub.git</connection>
    <developerConnection>scm:git:ssh://stub.stub.git</developerConnection>
    <url>https://stub.stub</url>
  </scm>
</project>"""
        )
    }

    @Test
    fun `kmm publishing`() {
        val moduleName = "test"
        val version = "0.1.0"

        testProjectDir.settingsGradleFile().writeText(settings(moduleName))
        //language=kotlin
        testProjectDir.buildGradleFile().writeText(
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget
            
            plugins {
                id("org.jetbrains.kotlin.multiplatform") version "2.1.0"
            
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            publishing {
                repositories {
                    mavenLocal()
                    ${mavenCentralPortal()}
                }
            }
            
            ${signing()}
            $pom
            
            kotlin {
                jvm()
                linuxX64()
            }
            """.trimIndent()
        )

        testProjectDir.createKotlinCommonMainClass()

        gradleRunnerDebug(testProjectDir) {
            withVersion(version)
            withTask("zipDeploymentAllPublications")
        }

        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory()).containsExactlyFiles(
            Path.of("").moduleBundlePath(moduleName, version, "allPublications").toString()
        )
        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(null, moduleName, version, "allPublications").toFile())
        ).containsMavenArtifacts("test.zenhelix", moduleName, version) {
            kotlinMultiplatform(targets = listOf("jvm", "linuxx64"))
        }

        val result = gradleRunnerDebug(testProjectDir) {
            withTask("publish")
        }

        BuildOutputAssert.assertThat(result.output)
            .containsPublishingLogCount(1)
    }

    @Test
    fun `java-library publishing`() {
        val moduleName = "module1"
        val version = "0.1.0"

        testProjectDir.settingsGradleFile().writeText(settings(moduleName))
        //language=kotlin
        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                `java-library`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            publishing {
                repositories {
                    mavenLocal()
                    ${mavenCentralPortal()}
                }
            }
            
            ${signing()}        
            $pom
            
            publishing {
                publications {
                    create<MavenPublication>("module1Lib") {
                        from(components["java"])
                    }
                }
            }
            """.trimIndent()
        )
        testProjectDir.createJavaMainClass()

        gradleRunnerDebug(testProjectDir) {
            withVersion(version)
            withTask("zipDeploymentAllPublications")
        }

        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(null, moduleName, version, "allPublications").toFile())
        ).containsMavenArtifacts("test.zenhelix", moduleName, version) {
            standardJavaLibrary()
        }
    }

    @Test
    fun `should create aggregated zip with all modules artifacts signatures and checksums`() {
        val version = "2.0.0"
        val module1 = "lib-core"
        val module2 = "lib-api"

        testProjectDir.settingsGradleFile().writeText(settings("test-library", module1, module2))

        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            subprojects {
                apply(plugin = "java-library")
                apply(plugin = "$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
                
                publishing {
                    repositories {
                        mavenLocal()
                        ${mavenCentralPortal()}
                    }
                    publications {
                        create<MavenPublication>("mavenJava") {
                            from(components["java"])
                        }
                    }
                }
                
                ${signing()}
                $pom
            }
            """.trimIndent()
        )

        testProjectDir.createJavaMainClass(module1)
        testProjectDir.createJavaMainClass(module2)

        GradleTasksOutputAssert
            .assertThat(gradleTasksRunner(testProjectDir, group = PUBLISH_TASK_GROUP))
            .containsTaskInCategory(
                PUBLISH_TASK_GROUP,
                "publishAllModulesToMavenCentralPortalRepository",
                "zipDeploymentAllModules"
            )

        gradleRunnerDebug(testProjectDir) {
            withVersion(version)
            withTask("zipDeploymentAllModules")
        }

        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory()).containsExactlyFiles(
            Path.of("").moduleBundlePath("test-library", version, "allModules").toString()
        )
        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(null, "test-library", version, "allModules").toFile())
        ).containsMavenArtifacts("test.zenhelix", "test-library", version) {
            standardJavaLibrary(module1)
            standardJavaLibrary(module2)
        }
    }

    @Test
    fun `individual module publishing should still work independently`() {
        val version = "1.0.0"
        val module1 = "module1"
        val module2 = "module2"

        testProjectDir.settingsGradleFile().writeText(settings("test-project", module1, module2))

        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            subprojects {
                apply(plugin = "java-library")
                apply(plugin = "$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
                
                publishing {
                    repositories {
                        mavenLocal()
                        ${mavenCentralPortal()}
                    }
                    publications {
                        create<MavenPublication>("mavenJava") {
                            from(components["java"])
                        }
                    }
                }
                
                ${signing()}
                $pom
            }
            """.trimIndent()
        )

        testProjectDir.createJavaMainClass(module1)
        testProjectDir.createJavaMainClass(module2)

        gradleRunnerDebug(testProjectDir) {
            withVersion(version)
            withTask(":$module1:zipDeploymentAllPublications")
        }

        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory(module1)).containsExactlyFiles(
            Path.of("").moduleBundlePath(module1, version, "allPublications").toString()
        )
        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory(module2)).doesNotExist()

        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(module1, module1, version, "allPublications").toFile())
        ).containsMavenArtifacts("test.zenhelix", module1, version) {
            standardJavaLibrary()
        }
    }

    @Test
    fun `should handle modules with multiple publications`() {
        val version = "1.0.0"
        val module1 = "module1"
        val module2 = "module2"

        testProjectDir.settingsGradleFile().writeText(settings("test-project", module1, module2))

        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            subprojects {
                apply(plugin = "java-library")
                apply(plugin = "$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
                
                publishing {
                    repositories {
                        mavenLocal()
                        ${mavenCentralPortal()}
                    }
                    publications {
                        create<MavenPublication>("mainLib") {
                            from(components["java"])
                        }
                        create<MavenPublication>("testFixtures") {
                            artifactId = "${'$'}{project.name}-test-fixtures"
                            from(components["java"])
                        }
                    }
                }
                
                ${signing()}
                $pom
            }
            """.trimIndent()
        )

        testProjectDir.createJavaMainClass(module1)
        testProjectDir.createJavaMainClass(module2)

        gradleRunnerDebug(testProjectDir) {
            withVersion(version)
            withTask("zipDeploymentAllModules")
        }

        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory()).containsExactlyFiles(
            Path.of("").moduleBundlePath("test-project", version, "allModules").toString()
        )

        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(null, "test-project", version, "allModules").toFile())
        ).containsSomeMavenArtifacts("test.zenhelix", module1, version) {
            standardJavaLibrary()
        }.containsSomeMavenArtifacts("test.zenhelix", "$module1-test-fixtures", version) {
            standardJavaLibrary()
        }.containsSomeMavenArtifacts("test.zenhelix", module2, version) {
            standardJavaLibrary()
        }.containsSomeMavenArtifacts("test.zenhelix", "$module2-test-fixtures", version) {
            standardJavaLibrary()
        }
    }

    @Test
    fun `should not create aggregation tasks when no subprojects exist`() {
        val version = "1.0.0"

        testProjectDir.settingsGradleFile().writeText(settings("single-module"))

        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                `java-library`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            publishing {
                repositories {
                    mavenLocal()
                    ${mavenCentralPortal()}
                }
                publications {
                    create<MavenPublication>("mavenJava") {
                        from(components["java"])
                    }
                }
            }
            
            ${signing()}
            $pom
            """.trimIndent()
        )

        testProjectDir.createJavaMainClass()

        GradleTasksOutputAssert.assertThat(gradleTasksRunner(testProjectDir, group = PUBLISH_TASK_GROUP))
            .doesNotContainTask("zipDeploymentAllModules", "publishAllModulesToMavenCentralPortalRepository")
            .containsTask("publishAllPublicationsToMavenCentralPortalRepository")
    }

    @Test
    fun `should create all necessary tasks for multi-module publishing`() {
        val version = "1.5.0"
        val module1 = "core"
        val module2 = "api"
        val module3 = "utils"

        testProjectDir.settingsGradleFile().writeText(settings("multi-project", module1, module2, module3))

        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            subprojects {
                apply(plugin = "java-library")
                apply(plugin = "$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
                
                publishing {
                    repositories {
                        mavenLocal()
                        ${mavenCentralPortal()}
                    }
                    publications {
                        create<MavenPublication>("mavenJava") {
                            from(components["java"])
                        }
                    }
                }
                
                ${signing()}
                $pom
            }
            """.trimIndent()
        )

        testProjectDir.createJavaMainClass(module1)
        testProjectDir.createJavaMainClass(module2)
        testProjectDir.createJavaMainClass(module3)

        GradleTasksOutputAssert.assertThat(gradleTasksRunner(testProjectDir, group = PUBLISH_TASK_GROUP))
            .containsTaskInCategory(
                PUBLISH_TASK_GROUP,
                "publishAllModulesToMavenCentralPortalRepository",
                "zipDeploymentAllModules"
            )
            .containsTaskInCategory(
                PUBLISH_TASK_GROUP,
                "$module1:publishAllPublicationsToMavenCentralPortalRepository",
                "$module1:zipDeploymentAllPublications",
                "$module1:zipDeploymentMavenJavaPublication",

                "$module2:publishAllPublicationsToMavenCentralPortalRepository",
                "$module2:zipDeploymentAllPublications",
                "$module2:zipDeploymentMavenJavaPublication",

                "$module3:publishAllPublicationsToMavenCentralPortalRepository",
                "$module3:zipDeploymentAllPublications",
                "$module3:zipDeploymentMavenJavaPublication"
            )
    }

    @Test
    fun `publishAllModulesToMavenCentralPortalRepository should create only aggregated archive`() {
        val version = "3.0.0"
        val module1 = "lib-a"
        val module2 = "lib-b"

        testProjectDir.settingsGradleFile().writeText(settings("my-library", module1, module2))

        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            subprojects {
                apply(plugin = "java-library")
                apply(plugin = "$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
                
                publishing {
                    repositories {
                        mavenLocal()
                        ${mavenCentralPortal()}
                    }
                    publications {
                        create<MavenPublication>("mavenJava") {
                            from(components["java"])
                        }
                    }
                }
                
                ${signing()}
                $pom
            }
            """.trimIndent()
        )

        testProjectDir.createJavaMainClass(module1)
        testProjectDir.createJavaMainClass(module2)

        GradleDryRunOutputAssert
            .assertThat(gradleDryRunRunner(testProjectDir, "publishAllModulesToMavenCentralPortalRepository"))
            .containsExactlyRootTasksInOrder(
                "zipDeploymentAllModules",
                "publishAllModulesToMavenCentralPortalRepository"
            )
            .doesNotContainTask(":$module1:publishAllPublicationsToMavenCentralPortalRepository")
            .doesNotContainTask(":$module2:publishAllPublicationsToMavenCentralPortalRepository")

        gradleRunnerDebug(testProjectDir) {
            withVersion(version)
            withTask("zipDeploymentAllModules")
        }

        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory()).containsExactlyFiles(
            Path.of("").moduleBundlePath("my-library", version, "allModules").toString()
        )
        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory(module1)).doesNotExist()
        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory(module2)).doesNotExist()
    }

    @Test
    fun `should create per-publication zip archives without aggregation`() {
        val rootModuleName = "test"
        val appModuleName = "app"
        val version = "1.0.0"

        val publicationName = "libJava"

        testProjectDir.settingsGradleFile().writeText(settings(rootModuleName, appModuleName))
        testProjectDir.buildGradleFile().writeText(
            """
            ${group(version = version)}            
            """.trimIndent()
        )
        testProjectDir.buildGradleFile(appModuleName).writeText(
            """
            plugins {
                java
                application
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            application {
                mainClass = "test.Test"
            }

            java {
                withSourcesJar()
            }

            publishing {
                repositories {
                    mavenLocal()
                    ${mavenCentralPortal()}
                }

                publications {
                    create<MavenPublication>("$publicationName") {
                        from(components["java"])
                    }
                }
            }
            
            ${signing()}
            $pom
            """.trimIndent()
        )
        testProjectDir.createJavaMainClass(appModuleName)

        GradleTasksOutputAssert.assertThat(gradleTasksRunner(testProjectDir, group = PUBLISH_TASK_GROUP))
            .containsExactlyTaskInCategoryInAnyOrder(
                PUBLISH_TASK_GROUP,
                "app:generateMetadataFileFor${publicationName.capitalized()}Publication",
                "app:generatePomFileFor${publicationName.capitalized()}Publication",

                "app:publish${publicationName.capitalized()}PublicationToMavenLocalRepository",
                "app:publish${publicationName.capitalized()}PublicationToMavenLocal",
                "app:publishAllPublicationsToMavenLocalRepository",
                "app:publishToMavenLocal",
                "app:publish",

                "app:checksum${publicationName.capitalized()}Publication",
                "app:checksumAllPublications",
                "app:zipDeployment${publicationName.capitalized()}Publication",
                "app:zipDeploymentAllPublications",
                "app:publish${publicationName.capitalized()}PublicationToMavenCentralPortalRepository",
                "app:publishAllPublicationsToMavenCentralPortalRepository"
            )

        val result = gradleRunnerDebug(testProjectDir) {
            withTask("publish${publicationName.capitalized()}PublicationToMavenCentralPortalRepository")
        }

        BuildOutputAssert.assertThat(result.output)
            .containsPublishingLog("$appModuleName-$publicationName-$version.zip", "AUTOMATIC", null)
            .containsPublishingLogCount(1)

        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(appModuleName, appModuleName, version, publicationName).toFile())
        ).containsMavenArtifacts("test.zenhelix", appModuleName, version) {
            standardJavaLibrary(withSources = true)
        }
    }

    @Test
    fun `should create aggregated zip with all publications when aggregatePublications is true`() {
        val rootModuleName = "test"
        val appModuleName = "app"
        val version = "1.0.0"

        val publicationName = "libJava"
        val secondPublicationName = "libJavaSecond"

        testProjectDir.settingsGradleFile().writeText(settings(rootModuleName, appModuleName))
        testProjectDir.buildGradleFile().writeText(
            """
            ${group(version = version)}            
            """.trimIndent()
        )
        testProjectDir.buildGradleFile(appModuleName).writeText(
            """
            plugins {
                java
                application
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            application {
                mainClass = "test.Test"
            }

            java {
                withSourcesJar()
            }

            publishing {
                repositories {
                    mavenLocal()
                    ${mavenCentralPortal()}
                }

                publications {
                    create<MavenPublication>("$publicationName") {
                        from(components["java"])
                    }
                    create<MavenPublication>("$secondPublicationName") {
                        from(components["java"])
                    }
                }
            }
            
            ${signing()}
            $pom
            """.trimIndent()
        )
        testProjectDir.createJavaMainClass(appModuleName)

        GradleTasksOutputAssert.assertThat(gradleTasksRunner(testProjectDir, group = PUBLISH_TASK_GROUP))
            .containsExactlyTaskInCategoryInAnyOrder(
                PUBLISH_TASK_GROUP,
                "app:generateMetadataFileFor${publicationName.capitalized()}Publication",
                "app:generateMetadataFileFor${secondPublicationName.capitalized()}Publication",
                "app:generatePomFileFor${publicationName.capitalized()}Publication",
                "app:generatePomFileFor${secondPublicationName.capitalized()}Publication",

                "app:publish${publicationName.capitalized()}PublicationToMavenLocalRepository",
                "app:publish${secondPublicationName.capitalized()}PublicationToMavenLocalRepository",
                "app:publish${publicationName.capitalized()}PublicationToMavenLocal",
                "app:publish${secondPublicationName.capitalized()}PublicationToMavenLocal",
                "app:publishAllPublicationsToMavenLocalRepository",
                "app:publishToMavenLocal",
                "app:publish",

                "app:checksum${publicationName.capitalized()}Publication",
                "app:checksum${secondPublicationName.capitalized()}Publication",
                "app:checksumAllPublications",
                "app:zipDeployment${publicationName.capitalized()}Publication",
                "app:zipDeployment${secondPublicationName.capitalized()}Publication",
                "app:zipDeploymentAllPublications",
                "app:publish${publicationName.capitalized()}PublicationToMavenCentralPortalRepository",
                "app:publish${secondPublicationName.capitalized()}PublicationToMavenCentralPortalRepository",
                "app:publishAllPublicationsToMavenCentralPortalRepository"
            )

        val result = gradleRunnerDebug(testProjectDir) {
            withTask("publishAllPublicationsToMavenCentralPortalRepository")
        }

        BuildOutputAssert.assertThat(result.output)
            .containsPublishingLog("$appModuleName-allPublications-$version.zip", "AUTOMATIC", null)
            .containsPublishingLogCount(1)

        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(appModuleName, appModuleName, version, "allPublications").toFile())
        ).containsMavenArtifacts("test.zenhelix", appModuleName, version) {
            standardJavaLibrary(withSources = true)
        }
    }

    @Test
    fun `root project with publications and subprojects should publish only aggregated archive`() {
        val version = "2.5.0"
        val module1 = "module-a"
        val module2 = "module-b"

        testProjectDir.settingsGradleFile().writeText(settings("bom-library", module1, module2))

        // Root project has BOM publication AND subprojects
        testProjectDir.buildGradleFile().writeText(
            """
            plugins {
                `java-platform`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            ${group(version = version)}
            
            javaPlatform {
                allowDependencies()
            }
            
            publishing {
                repositories {
                    mavenLocal()
                    ${mavenCentralPortal()}
                }
                publications {
                    create<MavenPublication>("bomPublication") {
                        from(components["javaPlatform"])
                    }
                }
            }
            
            ${signing()}
            $pom
            
            subprojects {
                apply(plugin = "java-library")
                apply(plugin = "$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
                
                publishing {
                    repositories {
                        mavenLocal()
                        ${mavenCentralPortal()}
                    }
                    publications {
                        create<MavenPublication>("mavenJava") {
                            from(components["java"])
                        }
                    }
                }
                
                ${signing()}
                $pom
            }
            """.trimIndent()
        )

        testProjectDir.createJavaMainClass(module1)
        testProjectDir.createJavaMainClass(module2)

        GradleDryRunOutputAssert
            .assertThat(gradleDryRunRunner(testProjectDir, "publish"))
            .containsExactlyRootTasksInOrder(
                "generateMetadataFileForBomPublicationPublication",
                "generatePomFileForBomPublicationPublication",
                "signBomPublicationPublication",
                "checksumBomPublicationPublication",
                "zipDeploymentAllModules",
                "publishAllModulesToMavenCentralPortalRepository",
                "publishBomPublicationPublicationToMavenLocalRepository",
                "publish"
            )

        val result = gradleRunnerDebug(testProjectDir) {
            withVersion(version)
            withTask("publish")
        }

        BuildOutputAssert.assertThat(result.output)
            .containsPublishingLogCount(1)
            .containsPublishingLog("bom-library-allModules-$version.zip", "AUTOMATIC", null)

        DirectoryAssert.assertThat(testProjectDir.distributionsDirectory()).containsExactlyFiles(
            Path.of("").moduleBundlePath("bom-library", version, "allModules").toString()
        )

        assertThat(
            ZipFile(testProjectDir.moduleBundleFile(null, "bom-library", version, "allModules").toFile())
        ).containsSomeMavenArtifacts("test.zenhelix", "bom-library", version) {
            gradlePlatform()
        }.containsSomeMavenArtifacts("test.zenhelix", module1, version) {
            standardJavaLibrary()
        }.containsSomeMavenArtifacts("test.zenhelix", module2, version) {
            standardJavaLibrary()
        }
    }

}