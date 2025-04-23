package test.utils

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import java.io.File

class GradleRunnerBuilder(private val delegate: GradleRunner) {
    private val tasks = linkedSetOf<GradleTask>()
    private val parameters = linkedSetOf<String>()
    private val options = linkedSetOf<String>()

    fun withGradleVersion(versionNumber: String): GradleRunnerBuilder = apply {
        delegate.withGradleVersion(versionNumber)
    }

    fun withDebug(debug: Boolean): GradleRunnerBuilder = apply {
        delegate.withDebug(debug)
    }

    fun forwardOutput(): GradleRunnerBuilder = apply {
        delegate.forwardOutput()
    }

    fun withTask(task: String, configure: GradleTaskConfigurator.() -> Unit = {}): GradleRunnerBuilder = apply {
        tasks.add(GradleTaskConfigurator(task).apply(configure).build())
    }

    fun withTask(task: GradleTask): GradleRunnerBuilder = apply {
        tasks.add(task)
    }

    fun withArguments(vararg args: String): GradleRunnerBuilder = apply {
        args.forEach { arg ->
            when {
                arg.startsWith("-P") -> parameters.add(arg)
                arg.startsWith("--") -> options.add(arg)
                // FIXME other case
                else -> tasks.add(GradleTask(task = arg))
            }
        }
    }

    fun withVersion(version: String): GradleRunnerBuilder = apply {
        parameters.add("-Pversion=$version")
    }

    fun printTasks(module: String? = null): GradleRunnerBuilder = apply {
        withTask(GradleTask(task = "tasks", module = module))
    }

    fun printAllTasks(module: String? = null): GradleRunnerBuilder = apply {
        withTask(GradleTask(task = "tasks", module = module, options = linkedSetOf("--all")))
    }

    internal fun build(): BuildResult {
        val allArgs = buildList {
            addAll(tasks.flatMap { it.toArguments() })
            addAll(options)
            addAll(parameters)
        }
        return delegate.withArguments(allArgs).build()
    }

    class GradleTaskConfigurator(private val task: String) {
        private var module: String? = null
        private val options: LinkedHashSet<String> = linkedSetOf()

        fun module(value: String): GradleTaskConfigurator = apply {
            module = value
        }

        fun option(option: String): GradleTaskConfigurator = apply {
            options.add(option)
        }

        fun options(vararg options: String): GradleTaskConfigurator = apply {
            this.options.addAll(options)
        }

        internal fun build(): GradleTask = GradleTask(task = task, module = module, options = options)
    }

    data class GradleTask(
        val task: String,
        val module: String? = null,
        val options: LinkedHashSet<String> = linkedSetOf()
    ) {
        fun toArguments(): List<String> {
            val taskArg = if (!module.isNullOrBlank()) {
                "$module:$task"
            } else {
                task
            }
            return listOf(taskArg, *options.toTypedArray())
        }

        fun toArgString(): String {
            val optionsString = if (options.isNotEmpty()) {
                options.joinToString(" ", prefix = " ")
            } else {
                ""
            }
            return if (!module.isNullOrBlank()) {
                "$module:$task$optionsString"
            } else {
                "$task$optionsString"
            }
        }
    }

    companion object {
        fun task(name: String, vararg options: String): GradleTask = GradleTask(task = name, options = linkedSetOf(*options))
        fun moduleTask(
            module: String, task: String, vararg options: String
        ): GradleTask = GradleTask(task = task, module = module, options = linkedSetOf(*options))
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