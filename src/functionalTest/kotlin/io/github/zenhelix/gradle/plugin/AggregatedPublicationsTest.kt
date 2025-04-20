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

class AggregatedPublicationsTest {

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
    fun `should create single aggregate zip when aggregatePublications is true`() {
        val version = "0.1.0"

        //language=kotlin
        settingsFile.writeText(
            """
            rootProject.name = "rootModule"
            
            include(
                ":module1",
                ":module2"
            )
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
                                aggregatePublications = true
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
            plugins {
                `java-library`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            publishing {
                publications {
                    create<MavenPublication>("module1Lib") {
                        from(components["java"])
                        
                        pom {
                            name.set("Module 1")
                            description.set("Test Module 1")
                        }
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

        //language=kotlin
        module2BuildFile.writeText(
            """
            plugins {
                `java-library`
                id("$MAVEN_CENTRAL_PORTAL_PUBLISH_PLUGIN_ID")
            }
            
            publishing {
                publications {
                    create<MavenPublication>("module2Lib") {
                        from(components["java"])
                        
                        pom {
                            name.set("Module 2")
                            description.set("Test Module 2")
                        }
                    }
                }
            }
            
            tasks.register("createTestClass") {
                doLast {
                    file("src/main/java/test/Test2.java").apply {
                        parentFile.mkdirs()
                        writeText(""${'"'}
                        package test;
                        public class Test2 {
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

        gradleRunnerDebug(testProjectDir) {
            withArguments("zipDeploymentAllPublications")
        }.also {
            assertThat(it.output).contains("BUILD SUCCESSFUL")
        }

        val aggregateZipFile = File(testProjectDir, "build/distributions/rootModule-$version.zip").also {
            assertThat(it).exists()
        }

        assertThat(ZipFile(aggregateZipFile)).containsExactlyInAnyOrderFiles(
            // module1
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
            "test/zenhelix/module1/0.1.0/module1-0.1.0.module.sha512",

            // module2
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.asc",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha1",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.md5",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha256",
            "test/zenhelix/module2/0.1.0/module2-0.1.0.jar.sha512",

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
            "test/zenhelix/module2/0.1.0/module2-0.1.0.module.sha512"
        )

        assertThat(File(testProjectDir, "module1/build/distributions/module1-$version.zip")).exists()
        assertThat(File(testProjectDir, "module2/build/distributions/module2-$version.zip")).exists()

        gradleRunnerDebug(testProjectDir) {
            withArguments("publishAggregateToMavenCentralPortal", "--dry-run")
        }.also {
            assertThat(it.output).contains("publishAggregateToMavenCentralPortal")
            assertThat(it.output).doesNotContain("publishModule1LibPublicationToMavenCentralPortal")
            assertThat(it.output).doesNotContain("publishModule2LibPublicationToMavenCentralPortal")
        }
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