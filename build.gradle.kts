@file:Suppress("UnstableApiUsage")

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.utils.extendsFrom

plugins {
    id("com.gradle.plugin-publish")
    `kotlin-dsl`
    signing
    `jacoco-report-aggregation`
    `jvm-test-suite`
}

group = "io.github.zenhelix"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

val jdkVersion = JavaVersion.VERSION_17
java {
    sourceCompatibility = jdkVersion
    targetCompatibility = jdkVersion

    withJavadocJar()
    withSourcesJar()
}

kotlin {
    explicitApi()

    compilerOptions {
        jvmTarget.set(JvmTarget.fromTarget(jdkVersion.toString()))
    }
}

tasks.check {
    dependsOn(tasks.named<JacocoReport>("testCodeCoverageReport"))
}

testing {
    suites {
        configureEach {
            if (this is JvmTestSuite) {
                useJUnitJupiter()
                dependencies {
                    implementation("org.assertj:assertj-core:3.27.2")
                }
            }
        }

        val test by getting(JvmTestSuite::class) {
            testType.set(TestSuiteType.UNIT_TEST)
        }
        val functionalTest by registering(JvmTestSuite::class) {
            testType.set(TestSuiteType.FUNCTIONAL_TEST)

            dependencies {
                implementation(project())
                implementation(gradleTestKit())
                implementation("org.bouncycastle:bcpg-jdk18on:1.79")
            }

            targets {
                all { testTask.configure { shouldRunAfter(test) } }
            }
        }
    }
}

val functionalTest by sourceSets.existing
gradlePlugin { testSourceSets(functionalTest.get()) }

configurations {
    named<Configuration>("functionalTestImplementation").extendsFrom(configurations.testImplementation)
}

tasks.named("check") {
    dependsOn(testing.suites.named("functionalTest"))
}

configurations {
    testImplementation.get().extendsFrom(compileOnly.get())
}

reporting {
    reports {
        configureEach {
            if (this is JacocoCoverageReport) {
                reportTask {
                    reports {
                        xml.required.set(true)
                        html.required.set(true)
                        csv.required.set(false)
                    }
                }
            }
        }
    }
}

tasks.jacocoTestReport {
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

gradlePlugin {
    website = "https://github.com/zenhelix/maven-central-publish"
    vcsUrl = "https://github.com/zenhelix/maven-central-publish.git"

    plugins {
        create("maven-central-publish") {
            implementationClass = "io.github.zenhelix.gradle.plugin.MavenCentralUploaderPlugin"
            id = "io.github.zenhelix.maven-central-publish"
            displayName = "A Gradle plugin for simplified publishing to Maven Central via Publisher API"
            description = "A Gradle plugin that simplifies publishing artifacts to Maven Central using the Publisher API"
            tags.set(listOf("publishing", "maven", "maven central", "maven central portal", "maven publish"))
        }
    }
}

dependencies{
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.0")
}

publishing {
    repositories {
        mavenLocal()
    }
}

signing {
    // ORG_GRADLE_PROJECT_signingKeyId
    val signingKeyId: String? by project
    // ascii-armored format
    // ORG_GRADLE_PROJECT_signingKey
    val signingKey: String? by project
    // ORG_GRADLE_PROJECT_signingPassword
    val signingPassword: String? by project

    isRequired = false

    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    sign(publishing.publications)
}