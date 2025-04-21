package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.MavenCentralUploaderPlugin.Companion.MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import test.utils.PgpUtils.generatePgpKeyPair
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

    @Test
    fun `zip deployment bundles for platform and catalog`() {
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
            
                signing {
                    useInMemoryPgpKeys(""${'"'}${generatePgpKeyPair("stub-password")}""${'"'}, "stub-password")
                    sign(publishing.publications)
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

        assertThat(moduleBundleFile(tomlModuleName, tomlModuleName, version)).exists()
        assertThat(moduleBundleFile(bomModuleName, bomModuleName, version)).exists()

        assertThat(ZipFile(moduleBundleFile(tomlModuleName, tomlModuleName, version).toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.asc",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.sha1",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.md5",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.sha256",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.toml.sha512",

                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.asc",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.sha1",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.md5",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.sha256",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.module.sha512",

                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.pom",
                "test/zenhelix/platform-toml/0.1.0/platform-toml-0.1.0.pom.asc",
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
</project>"""
            )

        assertThat(ZipFile(moduleBundleFile(bomModuleName, bomModuleName, version).toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.asc",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.sha1",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.md5",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.sha256",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.module.sha512",

                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.pom",
                "test/zenhelix/platform-bom/0.1.0/platform-bom-0.1.0.pom.asc",
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
</project>"""
            )
    }

    @Test
    fun `kmm publishing`() {
        val moduleName = "test"
        val version = "0.1.0"
        //language=kotlin
        settingsFile.writeText(
            """
            rootProject.name = "$moduleName"
    
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                    google()
                    mavenLocal()
                }
            }

            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    google()
                    mavenLocal()
                }
            }
            """.trimIndent()
        )
        //language=kotlin
        rootBuildFile.writeText(
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                id("com.android.library") version "8.2.0"
                id("org.jetbrains.kotlin.multiplatform") version "2.1.0"

                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }

            allprojects {
                group = "test.zenhelix"
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

            signing {
                useInMemoryPgpKeys(""${'"'}${generatePgpKeyPair("stub-password")}""${'"'}, "stub-password")
                sign(publishing.publications)
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

            kotlin {
                jvm()
                androidTarget {
                    publishLibraryVariants("release")
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_1_8)
                    }
                }
                linuxX64()
            }

            android {
                namespace = "test"
                compileSdk = 34
                defaultConfig {
                    minSdk = 24
                }
            }
            """.trimIndent()
        )

        File(testProjectDir, "src/commonMain/kotlin/test/TestFile.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package test
            
            fun generate() {}
            """.trimIndent()
        )

        gradleRunnerDebug(testProjectDir) { withArguments("zipDeploymentAllPublications", "-Pversion=$version") }

        assertThat(moduleBundleFile(null, moduleName, version, "kotlinMultiplatform")).exists()
        assertThat(ZipFile(moduleBundleFile(null, moduleName, version, "kotlinMultiplatform").toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/test/0.1.0/test-0.1.0.jar",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.sha512",

                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.sha512",

                "test/zenhelix/test/0.1.0/test-0.1.0.pom",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.sha512",

                "test/zenhelix/test/0.1.0/test-0.1.0.module",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.sha512",

                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.sha512"
            )

        assertThat(moduleBundleFile(null, moduleName, version, "jvm")).exists()
        assertThat(ZipFile(moduleBundleFile(null, moduleName, version, "jvm").toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.asc",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.sha1",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.md5",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.sha256",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.sha512",

                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.asc",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.sha1",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.md5",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.sha256",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.sha512",

                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.asc",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.sha1",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.md5",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.sha256",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.sha512",

                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.asc",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.sha1",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.md5",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.sha256",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.sha512"
            )

        assertThat(moduleBundleFile(null, moduleName, version, "linuxX64")).exists()
        assertThat(ZipFile(moduleBundleFile(null, moduleName, version, "linuxX64").toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.asc",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.sha1",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.md5",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.sha256",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.sha512",

                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.asc",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.sha1",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.md5",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.sha256",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.sha512",

                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.asc",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.sha1",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.md5",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.sha256",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.sha512",

                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.asc",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.sha1",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.md5",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.sha256",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.sha512"
            )
    }

    @Test
    fun `kmm publishing in aggregation publications`() {
        val moduleName = "test"
        val version = "0.1.0"
        //language=kotlin
        settingsFile.writeText(
            """
            rootProject.name = "$moduleName"
    
            dependencyResolutionManagement {
                repositories {
                    mavenCentral()
                    google()
                    mavenLocal()
                }
            }

            pluginManagement {
                repositories {
                    gradlePluginPortal()
                    google()
                    mavenLocal()
                }
            }
            """.trimIndent()
        )
        //language=kotlin
        rootBuildFile.writeText(
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                id("com.android.library") version "8.2.0"
                id("org.jetbrains.kotlin.multiplatform") version "2.1.0"

                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }

            allprojects {
                group = "test.zenhelix"
            }

            publishing {
                repositories {
                    mavenLocal()
                    mavenCentralPortal {
                        credentials {
                            username = "stub"
                            password = "stub"
                        }
                        uploader { aggregate { modulePublications = true } }
                    }
                }
            }

            signing {
                useInMemoryPgpKeys(""${'"'}${generatePgpKeyPair("stub-password")}""${'"'}, "stub-password")
                sign(publishing.publications)
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

            kotlin {
                jvm()
                androidTarget {
                    publishLibraryVariants("release")
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_1_8)
                    }
                }
                linuxX64()
            }

            android {
                namespace = "test"
                compileSdk = 34
                defaultConfig {
                    minSdk = 24
                }
            }
            """.trimIndent()
        )

        File(testProjectDir, "src/commonMain/kotlin/test/TestFile.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package test
            
            fun generate() {}
            """.trimIndent()
        )

        gradleRunnerDebug(testProjectDir) { withArguments("zipDeploymentAllPublications", "-Pversion=$version") }

        assertThat(moduleBundleFile(null, moduleName, version)).exists()
        assertThat(ZipFile(moduleBundleFile(null, moduleName, version).toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/test/0.1.0/test-0.1.0.jar",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0.jar.sha512",

                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0-sources.jar.sha512",

                "test/zenhelix/test/0.1.0/test-0.1.0.pom",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0.pom.sha512",

                "test/zenhelix/test/0.1.0/test-0.1.0.module",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0.module.sha512",

                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.asc",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.sha1",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.md5",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.sha256",
                "test/zenhelix/test/0.1.0/test-0.1.0-kotlin-tooling-metadata.json.sha512",

                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.asc",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.sha1",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.md5",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.sha256",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.jar.sha512",

                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.asc",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.sha1",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.md5",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.sha256",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0-sources.jar.sha512",

                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.asc",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.sha1",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.md5",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.sha256",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.pom.sha512",

                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.asc",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.sha1",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.md5",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.sha256",
                "test/zenhelix/test-jvm/0.1.0/test-jvm-0.1.0.module.sha512",

                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.asc",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.sha1",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.md5",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.sha256",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.klib.sha512",

                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.asc",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.sha1",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.md5",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.sha256",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0-sources.jar.sha512",

                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.asc",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.sha1",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.md5",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.sha256",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.pom.sha512",

                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.asc",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.sha1",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.md5",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.sha256",
                "test/zenhelix/test-linuxx64/0.1.0/test-linuxx64-0.1.0.module.sha512"
            )
    }

    @Test
    fun `java-library publishing`() {
        val moduleName = "module1"
        val version = "0.1.0"
        //language=kotlin
        settingsFile.writeText(
            """
            rootProject.name = "$moduleName"
            """.trimIndent()
        )
        //language=kotlin
        rootBuildFile.writeText(
            """
            plugins {
                `java-library`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }

            allprojects {
                group = "test.zenhelix"
                version = "$version"
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

            signing {
                useInMemoryPgpKeys(""${'"'}${generatePgpKeyPair("stub-password")}""${'"'}, "stub-password")
                sign(publishing.publications)
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
            
            publishing {
                publications {
                    create<MavenPublication>("module1Lib") {
                        from(components["java"])
                    }
                }
            }
            
            tasks.register("createTestClass") {
                doLast {
                    file("src/main/java/test/Test1.java").apply {
                        parentFile.mkdirs()
                        writeText(""${'"'}
                        package test;
                        public class Test1 {
                            public static void test() {}
                        }
                        ""${'"'})
                    }
                }
            }
            
            tasks.compileJava {
                dependsOn("createTestClass")
            }
            """.trimIndent()
        )

        gradleRunnerDebug(testProjectDir) { withArguments("zipDeploymentAllPublications") }

        assertThat(ZipFile(moduleBundleFile(null, moduleName, version).toFile()))
            .containsExactlyInAnyOrderFiles(
                "test/zenhelix/module1/0.1.0/module1-0.1.0.jar",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.asc",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha1",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.md5",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha256",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha512",

                "test/zenhelix/module1/0.1.0/module1-0.1.0.pom",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.pom.asc",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.pom.sha1",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.pom.md5",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.pom.sha256",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.pom.sha512",

                "test/zenhelix/module1/0.1.0/module1-0.1.0.module",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.module.asc",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.module.sha1",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.module.md5",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.module.sha256",
                "test/zenhelix/module1/0.1.0/module1-0.1.0.module.sha512"
            )
    }

    private fun moduleBundleFile(gradleModuleName: String?, moduleName: String, version: String, publicationName: String? = null) =
        testProjectDir.toPath().let { path -> gradleModuleName?.let { path.resolve(it) } ?: path }.resolve("build").resolve("distributions")
            .resolve("$moduleName-${publicationName?.let { "$it-" } ?: ""}$version.zip")

    private companion object {
        @TempDir
        private lateinit var testProjectDir: File

        private lateinit var settingsFile: File
        private lateinit var rootBuildFile: File
    }
}