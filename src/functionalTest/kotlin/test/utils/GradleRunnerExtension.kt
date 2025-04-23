package test.utils

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

class GradleRunnerBuilder(private val delegate: GradleRunner) {
    private val tasks = linkedSetOf<String>()
    private val parameters = linkedSetOf<String>()
    private val options = linkedSetOf<String>()

    fun withDebug(debug: Boolean): GradleRunnerBuilder = apply {
        delegate.withDebug(debug)
    }

    fun forwardOutput(): GradleRunnerBuilder = apply {
        delegate.forwardOutput()
    }

    fun withTask(task: String): GradleRunnerBuilder = apply {
        tasks.add(task)
    }

    fun withVersion(version: String): GradleRunnerBuilder = apply {
        parameters.add("-Pversion=$version")
    }

    fun withArguments(vararg args: String): GradleRunnerBuilder = apply {
        args.forEach { arg ->
            when {
                arg.startsWith("-P") -> parameters.add(arg)
                arg.startsWith("--") -> options.add(arg)
                // FIXME other case
                else -> tasks.add(arg)
            }
        }
    }

    fun build(): BuildResult {
        val allArgs = buildList {
            addAll(tasks)
            addAll(parameters)
            addAll(options)
        }
        return delegate.withArguments(allArgs).build()
    }

}

fun gradleRunner(projectDir: File, initializer: GradleRunnerBuilder.() -> Unit = {}): BuildResult {
    val delegate = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()

    val wrapper = GradleRunnerBuilder(delegate)
    wrapper.initializer()

    return wrapper.build()
}

fun gradleRunnerDebug(projectDir: File, initializer: GradleRunnerBuilder.() -> Unit = {}): BuildResult = gradleRunner(projectDir) {
    withDebug(true).forwardOutput()
    initializer()
}