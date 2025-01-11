package io.github.zenhelix

import io.github.zenhelix.MavenCentralUploaderPlugin.Companion.MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import test.utils.ZipFileAssert.Companion.assertThat
import test.utils.gradleRunnerDebug
import java.io.File
import java.util.zip.ZipFile

class MavenCentralUploaderPluginFunctionalTest {

    @BeforeEach
    fun setup() {
        settingsFile = File(testProjectDir, "settings.gradle.kts")
        rootBuildFile = File(testProjectDir, "build.gradle.kts")
    }

    @Test fun `zip deployment bundles for platform and catalog`() {
        val version = "0.1.0"
        val tomlModuleName = "platform-toml"
        val bomModuleName = "platform-bom"

        //language=kotlin
        settingsFile.writeText(
            """
            rootProject.name = "test"
            
            include(
                ":$tomlModuleName",
                ":$bomModuleName"
            )
            """.trimIndent()
        )
        //language=kotlin
        rootBuildFile.writeText(
            """
            plugins {
                `java-platform`
                `version-catalog`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            allprojects {
                group = "test.zenhelix"
            }
            
            val platformComponentName: String = "javaPlatform"
            val catalogComponentName: String = "versionCatalog"
            
            configure(subprojects.filter { it.name.contains("-bom") || it.name.contains("-toml") }) {
                apply {
                    plugin("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
                }
            
                publishing {
                    repositories {
                        mavenLocal()
                        mavenCentralPortal {
                            credentials {
                                username = "stub"
                                password = "stub"
                            }
                        }
                    }
                }
            
                publishing.publications.withType<MavenPublication> {
                    pom {
                        description = "stub description"
                        url = "https://stub.stub"
                        licenses {
                            license {
                                name = "The Apache License, Version 2.0"
                                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                            }
                        }
                        scm {
                            connection = "scm:git:git://stub.stub.git"
                            developerConnection = "scm:git:ssh://stub.stub.git"
                            url = "https://stub.stub"
                        }
                        developers {
                            developer {
                                id = "stub"
                                name = "Stub Stub"
                                email = "stub@stub.stub"
                            }
                        }
                    }
                }
            }
            
            configure(subprojects.filter { it.name.contains("-bom") }) {
                apply { plugin("java-platform") }
            
                javaPlatform {
                    allowDependencies()
                }
            
                publishing {
                    publications {
                        create<MavenPublication>("javaPlatform") {
                            from(components[platformComponentName])
                        }
                    }
                }
            
            }
            
            configure(subprojects.filter { it.name.contains("-toml") }) {
                apply { plugin("version-catalog") }
            
                publishing {
                    publications {
                        create<MavenPublication>("versionCatalog") {
                            from(components[catalogComponentName])
                        }
                    }
                }
            
            }
            """.trimIndent()
        )

        gradleRunnerDebug(testProjectDir) { withArguments("zipDeploymentAllPublications", "-Pversion=$version") }

        assertThat(bundleFile(tomlModuleName, version)).exists()
        assertThat(bundleFile(bomModuleName, version)).exists()

        assertThat(ZipFile(bundleFile(tomlModuleName, version).toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.sha1",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.md5",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.sha256",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.sha512",

                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.sha1",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.md5",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.sha256",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.sha512",

                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.pom",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.pom.sha1",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.pom.md5",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.pom.sha256",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.pom.sha512"
            )
            .isEqualTextContentTo(
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.pom",
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
</project>
"""
            )

        assertThat(ZipFile(bundleFile(bomModuleName, version).toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.sha1",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.md5",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.sha256",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.sha512",

                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.pom",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.pom.sha1",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.pom.md5",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.pom.sha256",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.pom.sha512"
            )
            .isEqualTextContentTo(
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.pom",
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
</project>
"""
            )
    }

    private fun bundleFile(moduleName: String, version: String) =
        testProjectDir.toPath().resolve(moduleName).resolve("build").resolve("distributions").resolve("$moduleName-$version.zip")

    private companion object {
        @TempDir
        private lateinit var testProjectDir: File

        private lateinit var settingsFile: File
        private lateinit var rootBuildFile: File
    }
}