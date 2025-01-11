package test.utils

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

fun gradleRunner(projectDir: File, initializer: GradleRunner.() -> Unit = {}): BuildResult {
    val builder = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()
    builder.initializer()
    return builder.build()
}

fun gradleRunnerDebug(projectDir: File, initializer: GradleRunner.() -> Unit = {}): BuildResult = gradleRunner(projectDir) {
    withDebug(true).forwardOutput().initializer()
}
