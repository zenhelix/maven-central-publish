package io.github.zenhelix.gradle.plugin

import io.github.zenhelix.gradle.plugin.MavenCentralUploaderPlugin.Companion.MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import test.utils.BuildResultAssert.Companion.assertThat
import test.utils.PgpUtils.generatePgpKeyPair
import test.utils.ZipFileAssert.Companion.assertThat
import test.utils.gradleRunnerDebug
import java.io.File
import java.util.zip.ZipFile

class CombinedAggregationTest {

    @BeforeEach
    fun setup() {
        settingsFile = File(testProjectDir, "settings.gradle.kts")
        rootBuildFile = File(testProjectDir, "build.gradle.kts")

        File(testProjectDir, "module1").mkdirs()
        File(testProjectDir, "module2").mkdirs()

        module1BuildFile = File(testProjectDir, "module1/build.gradle.kts")
        module2BuildFile = File(testProjectDir, "module2/build.gradle.kts")
    }

    @Test
    fun `should aggregate all modules and publications when both flags are true`() {
        val version = "0.1.0"

        //language=kotlin
        settingsFile.writeText(
            """
            rootProject.name = "rootProject"
            
            include(
                ":module1",
                ":module2"
            )
            
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
            plugins {
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            allprojects {
                group = "test.zenhelix"
                version = "$version"
                
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
                            uploader { 
                                aggregate { 
                                    modules = true 
                                    modulePublications = true
                                } 
                            }           
                        }
                    }
                }
            }
            
            subprojects {
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
            """.trimIndent()
        )

        //language=kotlin
        module1BuildFile.writeText(
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                id("org.jetbrains.kotlin.multiplatform") version "2.1.0"
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }

            kotlin {
                jvm()
                linuxX64()
            }
            """.trimIndent()
        )
        File(testProjectDir, "module1/src/commonMain/kotlin/test/module1/Module1.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package test
            
            fun generate() {}
            """.trimIndent()
        )

        //language=kotlin
        module2BuildFile.writeText(
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                id("org.jetbrains.kotlin.multiplatform") version "2.1.0"
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }

            kotlin {
                jvm()
                linuxX64()
                
                sourceSets {
                    commonMain {
                        kotlin.srcDir("src/commonMain/kotlin")
                    }
                }
            }
            """.trimIndent()
        )
        File(testProjectDir, "module2/src/commonMain/kotlin/test/module2/Module2.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package test
            
            fun generate() {}
            """.trimIndent()
        )

        assertThat(
            gradleRunnerDebug(testProjectDir) { withTask("zipDeploymentAllPublications") }
        ).successfulBuild()

        val aggregateZipFile = File(testProjectDir, "build/distributions/rootProject-$version.zip")
        assertThat(aggregateZipFile).exists()

        assertThat(File(testProjectDir, "module1/build/distributions/module1-$version.zip")).doesNotExist()
        assertThat(File(testProjectDir, "module2/build/distributions/module2-$version.zip")).doesNotExist()

        assertThat(ZipFile(aggregateZipFile)).containsExactlyInAnyOrderFiles(
            // Module 1 - kotlinMultiplatform
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.asc",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha1",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.md5",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha256",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha512",

            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.asc",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.sha1",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.md5",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.sha256",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.sha512",

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
            "test/zenhelix/module1/0.1.0/module1-0.1.0.module.sha512",

            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.asc",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.sha1",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.md5",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.sha256",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.sha512",

            // Module 1 - JVM
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.jar",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.jar.asc",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.jar.sha1",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.jar.md5",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.jar.sha256",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.jar.sha512",

            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0-sources.jar",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0-sources.jar.asc",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0-sources.jar.sha1",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0-sources.jar.md5",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0-sources.jar.sha256",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0-sources.jar.sha512",

            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.pom",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.pom.asc",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.pom.sha1",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.pom.md5",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.pom.sha256",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.pom.sha512",

            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.module",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.module.asc",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.module.sha1",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.module.md5",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.module.sha256",
            "test/zenhelix/module1-jvm/0.1.0/module1-jvm-0.1.0.module.sha512",

            // Module 1 - LinuxX64
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.klib",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.klib.asc",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.klib.sha1",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.klib.md5",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.klib.sha256",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.klib.sha512",

            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0-sources.jar",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0-sources.jar.asc",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0-sources.jar.sha1",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0-sources.jar.md5",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0-sources.jar.sha256",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0-sources.jar.sha512",

            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.pom",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.pom.asc",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.pom.sha1",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.pom.md5",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.pom.sha256",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.pom.sha512",

            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.module",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.module.asc",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.module.sha1",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.module.md5",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.module.sha256",
            "test/zenhelix/module1-linuxx64/0.1.0/module1-linuxx64-0.1.0.module.sha512",

            // Module 2 - kotlinMultiplatform
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha512",

            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.sha512",

            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.sha512",

            "test/zenhelix/module2/0.1.0/module2-0.1.0.module",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.sha512",

            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.sha512",

            // Module 2 - JVM
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.jar",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.jar.asc",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.jar.sha1",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.jar.md5",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.jar.sha256",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.jar.sha512",

            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0-sources.jar",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0-sources.jar.asc",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0-sources.jar.sha1",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0-sources.jar.md5",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0-sources.jar.sha256",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0-sources.jar.sha512",

            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.pom",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.pom.asc",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.pom.sha1",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.pom.md5",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.pom.sha256",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.pom.sha512",

            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.module",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.module.asc",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.module.sha1",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.module.md5",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.module.sha256",
            "test/zenhelix/module2-jvm/0.1.0/module2-jvm-0.1.0.module.sha512",

            // Module 2 - LinuxX64
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.klib",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.klib.asc",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.klib.sha1",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.klib.md5",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.klib.sha256",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.klib.sha512",

            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0-sources.jar",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0-sources.jar.asc",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0-sources.jar.sha1",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0-sources.jar.md5",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0-sources.jar.sha256",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0-sources.jar.sha512",

            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.pom",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.pom.asc",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.pom.sha1",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.pom.md5",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.pom.sha256",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.pom.sha512",

            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.module",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.module.asc",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.module.sha1",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.module.md5",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.module.sha256",
            "test/zenhelix/module2-linuxx64/0.1.0/module2-linuxx64-0.1.0.module.sha512"
        )
    }

    @Test
    fun `should aggregate all modules when only modules flag is true and specific zipDeployment task is called`() {
        val version = "0.1.0"

        //language=kotlin
        settingsFile.writeText(
            """
            rootProject.name = "rootProject"
            
            include(
                ":module1",
                ":module2"
            )
            
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
            plugins {
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            allprojects {
                group = "test.zenhelix"
                version = "$version"
                
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
                            uploader { aggregate { modules = true } }           
                        }
                    }
                }
            }
            
            subprojects {
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
            """.trimIndent()
        )

        //language=kotlin
        module1BuildFile.writeText(
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                id("org.jetbrains.kotlin.multiplatform") version "2.1.0"
            }

            kotlin {
                jvm()
                linuxX64()
            }
            """.trimIndent()
        )
        File(testProjectDir, "module1/src/commonMain/kotlin/test/module1/Module1.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package test
            
            fun generate() {}
            """.trimIndent()
        )

        //language=kotlin
        module2BuildFile.writeText(
            """
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget

            plugins {
                id("org.jetbrains.kotlin.multiplatform") version "2.1.0"
            }

            kotlin {
                jvm()
                linuxX64()
            }
            """.trimIndent()
        )
        File(testProjectDir, "module2/src/commonMain/kotlin/test/module2/Module2.kt").also { it.parentFile.mkdirs() }.writeText(
            """
            package test
            
            fun generate() {}
            """.trimIndent()
        )

        assertThat(
            gradleRunnerDebug(testProjectDir) { withTask("zipDeploymentKotlinMultiplatformPublication") }
        ).successfulBuild()

        val aggregateZipFile = File(testProjectDir, "build/distributions/rootProject-$version.zip")
        assertThat(aggregateZipFile).exists()

        assertThat(File(testProjectDir, "module1/build/distributions/module1-$version.zip")).doesNotExist()
        assertThat(File(testProjectDir, "module2/build/distributions/module2-$version.zip")).doesNotExist()

        assertThat(ZipFile(aggregateZipFile)).containsExactlyInAnyOrderFiles(
            // Module 1 - kotlinMultiplatform
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.asc",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha1",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.md5",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha256",
            "test/zenhelix/module1/0.1.0/module1-0.1.0.jar.sha512",

            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.asc",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.sha1",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.md5",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.sha256",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-sources.jar.sha512",

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
            "test/zenhelix/module1/0.1.0/module1-0.1.0.module.sha512",

            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.asc",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.sha1",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.md5",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.sha256",
            "test/zenhelix/module1/0.1.0/module1-0.1.0-kotlin-tooling-metadata.json.sha512",

            // Module 2 - kotlinMultiplatform
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha512",

            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-sources.jar.sha512",

            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.pom.sha512",

            "test/zenhelix/module2/0.1.0/module2-0.1.0.module",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.sha512",

            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0-kotlin-tooling-metadata.json.sha512",
        )

        val zipEntries = ZipFile(aggregateZipFile).entries().toList().map { it.name }

        assertThat(zipEntries.none { it.startsWith("test/zenhelix/module1-jvm/") }).isTrue()
        assertThat(zipEntries.none { it.startsWith("test/zenhelix/module1-linuxx64/") }).isTrue()

        assertThat(zipEntries.none { it.startsWith("test/zenhelix/module2-jvm/") }).isTrue()
        assertThat(zipEntries.none { it.startsWith("test/zenhelix/module2-linuxx64/") }).isTrue()
    }

    private companion object {
        @TempDir
        private lateinit var testProjectDir: File

        private lateinit var settingsFile: File
        private lateinit var rootBuildFile: File
        private lateinit var module1BuildFile: File
        private lateinit var module2BuildFile: File
    }
}