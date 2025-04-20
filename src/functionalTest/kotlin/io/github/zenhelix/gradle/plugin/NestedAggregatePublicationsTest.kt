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

class NestedAggregatePublicationsTest {

    @BeforeEach
    fun setup() {
        settingsFile = File(testProjectDir, "settings.gradle.kts")
        rootBuildFile = File(testProjectDir, "build.gradle.kts")

        File(testProjectDir, "moduleA").mkdirs()
        File(testProjectDir, "moduleA/moduleA1").mkdirs()
        File(testProjectDir, "moduleA/moduleA2").mkdirs()
        File(testProjectDir, "moduleB").mkdirs()

        moduleABuildFile = File(testProjectDir, "moduleA/build.gradle.kts")
        moduleA1BuildFile = File(testProjectDir, "moduleA/moduleA1/build.gradle.kts")
        moduleA2BuildFile = File(testProjectDir, "moduleA/moduleA2/build.gradle.kts")
        moduleBBuildFile = File(testProjectDir, "moduleB/build.gradle.kts")
    }

    @Test
    fun `should properly aggregate publications in nested structure`() {
        val version = "0.1.0"

        //language=kotlin
        settingsFile.writeText(
            """
            rootProject.name = "nested-test"
            
            include(
                ":moduleA",
                ":moduleA:moduleA1",
                ":moduleA:moduleA2",
                ":moduleB"
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

        // moduleA включает агрегацию - его подмодули должны публиковаться вместе с ним
        //language=kotlin
        moduleABuildFile.writeText(
            """
            plugins {
                `java-library`
            }
            
            // Включаем агрегацию для moduleA
            mavenCentralPortal {
                uploader {
                    aggregatePublications = true
                }
            }
            
            publishing {
                publications {
                    create<MavenPublication>("moduleALib") {
                        from(components["java"])
                        
                        pom {
                            name.set("Module A")
                            description.set("Test Module A")
                        }
                    }
                }
            }
            
            tasks.register("createTestClass") {
                doLast {
                    file("src/main/java/test/TestA.java").apply {
                        parentFile.mkdirs()
                        writeText(""${'"'}
                        package test;
                        public class TestA {
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
        moduleA1BuildFile.writeText(
            """
            plugins {
                `java-library`
            }
            
            publishing {
                publications {
                    create<MavenPublication>("moduleA1Lib") {
                        from(components["java"])
                        
                        pom {
                            name.set("Module A1")
                            description.set("Test Module A1")
                        }
                    }
                }
            }
            
            tasks.register("createTestClass") {
                doLast {
                    file("src/main/java/test/TestA1.java").apply {
                        parentFile.mkdirs()
                        writeText(""${'"'}
                        package test;
                        public class TestA1 {
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
        moduleA2BuildFile.writeText(
            """
            plugins {
                `java-library`
            }
            
            publishing {
                publications {
                    create<MavenPublication>("moduleA2Lib") {
                        from(components["java"])
                        
                        pom {
                            name.set("Module A2")
                            description.set("Test Module A2")
                        }
                    }
                }
            }
            
            tasks.register("createTestClass") {
                doLast {
                    file("src/main/java/test/TestA2.java").apply {
                        parentFile.mkdirs()
                        writeText(""${'"'}
                        package test;
                        public class TestA2 {
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

        // moduleB не использует агрегацию - должен публиковаться отдельно
        //language=kotlin
        moduleBBuildFile.writeText(
            """
            plugins {
                `java-library`
            }
            
            publishing {
                publications {
                    create<MavenPublication>("moduleBLib") {
                        from(components["java"])
                        
                        pom {
                            name.set("Module B")
                            description.set("Test Module B")
                        }
                    }
                }
            }
            
            tasks.register("createTestClass") {
                doLast {
                    file("src/main/java/test/TestB.java").apply {
                        parentFile.mkdirs()
                        writeText(""${'"'}
                        package test;
                        public class TestB {
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

        // Проверяем создание архивов для агрегированной публикации moduleA
        gradleRunnerDebug(testProjectDir) {
            withArguments("zipAggregateDeployment")
        }.also {
            assertThat(it.output).contains("BUILD SUCCESSFUL")
        }

        // Проверяем создание агрегированного архива для moduleA
        val moduleAAggregateZip = File(testProjectDir, "moduleA/build/distributions/aggregate-moduleA-$version.zip").also {
            assertThat(it).exists()
        }

        // Проверяем содержимое архива
        assertThat(ZipFile(moduleAAggregateZip)).containsExactlyInAnyOrderFiles(
            // moduleA
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.jar",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.jar.asc",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.jar.sha1",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.jar.md5",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.jar.sha256",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.jar.sha512",

            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.pom",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.pom.asc",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.pom.sha1",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.pom.md5",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.pom.sha256",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.pom.sha512",

            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.module",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.module.asc",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.module.sha1",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.module.md5",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.module.sha256",
            "test/zenhelix/moduleA/0.1.0/moduleA-0.1.0.module.sha512",

            // moduleA1
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.jar",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.jar.asc",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.jar.sha1",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.jar.md5",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.jar.sha256",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.jar.sha512",

            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.pom",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.pom.asc",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.pom.sha1",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.pom.md5",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.pom.sha256",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.pom.sha512",

            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.module",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.module.asc",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.module.sha1",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.module.md5",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.module.sha256",
            "test/zenhelix/moduleA1/0.1.0/moduleA1-0.1.0.module.sha512",

            // moduleA2
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.jar",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.jar.asc",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.jar.sha1",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.jar.md5",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.jar.sha256",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.jar.sha512",

            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.pom",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.pom.asc",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.pom.sha1",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.pom.md5",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.pom.sha256",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.pom.sha512",

            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.module",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.module.asc",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.module.sha1",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.module.md5",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.module.sha256",
            "test/zenhelix/moduleA2/0.1.0/moduleA2-0.1.0.module.sha512"
        )

        // Проверяем что модуль B не включен в архив moduleA

        // Проверяем что модуль B можно опубликовать отдельно
        gradleRunnerDebug(testProjectDir) {
            withArguments(":moduleB:zipDeploymentAllPublications")
        }.also {
            assertThat(it.output).contains("BUILD SUCCESSFUL")
        }

        val moduleBZip = File(testProjectDir, "moduleB/build/distributions/moduleB-$version.zip").also {
            assertThat(it).exists()
        }

        // Проверяем что при выполнении publishToMavenCentralPortal будут запускаться
        // правильные задачи для публикации модуля A с его подмодулями и модуля B отдельно
        gradleRunnerDebug(testProjectDir) {
            withArguments("publishToMavenCentralPortal", "--dry-run")
        }.also {
            assertThat(it.output).contains("publishAggregateToMavenCentralPortal")
            assertThat(it.output).contains(":moduleB:publishModuleBLibPublicationToMavenCentralPortal")
            assertThat(it.output).doesNotContain(":moduleA:moduleA1:publishModuleA1LibPublicationToMavenCentralPortal")
            assertThat(it.output).doesNotContain(":moduleA:moduleA2:publishModuleA2LibPublicationToMavenCentralPortal")
            assertThat(it.output).doesNotContain(":moduleA:publishModuleALibPublicationToMavenCentralPortal")
        }
    }

    private companion object {
        @TempDir
        private lateinit var testProjectDir: File

        private lateinit var settingsFile: File
        private lateinit var rootBuildFile: File
        private lateinit var moduleABuildFile: File
        private lateinit var moduleA1BuildFile: File
        private lateinit var moduleA2BuildFile: File
        private lateinit var moduleBBuildFile: File
    }
}