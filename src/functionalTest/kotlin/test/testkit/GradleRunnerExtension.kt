package test.testkit

import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import test.testkit.parser.GradleDryRunOutput
import test.testkit.parser.GradleOutput
import test.testkit.parser.parseGradleDryRunOutput
import test.testkit.parser.parseGradleTasksOutput

public fun gradleRunner(projectDir: File, initializer: GradleRunnerBuilder.() -> Unit = {}): BuildResult {
    val delegate = GradleRunner.create()
        .withProjectDir(projectDir)
        .withPluginClasspath()

    return GradleRunnerBuilder(delegate).apply(initializer).build()
}

public fun gradleRunnerDebug(
    projectDir: File, initializer: GradleRunnerBuilder.() -> Unit = {}
): BuildResult = gradleRunner(projectDir) {
    withDebug(true).forwardOutput()
    initializer()
}

public fun gradleTasksRunner(
    projectDir: File,
    module: String? = null,
    group: String? = null,
    showAll: Boolean = true,
    initializer: GradleRunnerBuilder.() -> Unit = {}
): GradleOutput = gradleRunner(projectDir) {
    if (showAll) {
        printAllTasks(module)
    } else {
        printTasks(module)
    }
    if (group != null) {
        withArguments("--group=$group")
    }
    forwardOutput()
    initializer()
}.let { parseGradleTasksOutput(it.output) }

public fun gradleDryRunRunner(
    projectDir: File,
    task: String,
    initializer: GradleRunnerBuilder.() -> Unit = {}
): GradleDryRunOutput = gradleRunner(projectDir) {
    withTask(task) {
        options("--dry-run")
    }
    forwardOutput()
    initializer()
}.let { parseGradleDryRunOutput(it.output) }

public class GradleRunnerBuilder(private val delegate: GradleRunner) {
    private val tasks = linkedSetOf<GradleTask>()
    private val parameters = linkedSetOf<String>()
    private val options = linkedSetOf<String>()

    public fun withGradleVersion(versionNumber: String): GradleRunnerBuilder = apply {
        delegate.withGradleVersion(versionNumber)
    }

    public fun withDebug(debug: Boolean): GradleRunnerBuilder = apply {
        delegate.withDebug(debug)
    }

    public fun forwardOutput(): GradleRunnerBuilder = apply {
        delegate.forwardOutput()
    }

    public fun withTask(task: String, configure: GradleTaskConfigurator.() -> Unit = {}): GradleRunnerBuilder = apply {
        tasks.add(GradleTaskConfigurator(task).apply(configure).build())
    }

    public fun withTask(task: GradleTask): GradleRunnerBuilder = apply {
        tasks.add(task)
    }

    public fun withArguments(vararg args: String): GradleRunnerBuilder = apply {
        args.forEach { arg ->
            when {
                arg.startsWith("-P") -> parameters.add(arg)
                arg.startsWith("--") -> options.add(arg)
                // FIXME other case
                else -> tasks.add(GradleTask(task = arg))
            }
        }
    }

    public fun withVersion(version: String): GradleRunnerBuilder = apply {
        parameters.add("-Pversion=$version")
    }

    public fun printTasks(module: String? = null): GradleRunnerBuilder = apply {
        withTask(GradleTask(task = "tasks", module = module))
    }

    public fun printAllTasks(module: String? = null): GradleRunnerBuilder = apply {
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

}

public class GradleTaskConfigurator(private val task: String) {
    private var module: String? = null
    private val options: LinkedHashSet<String> = linkedSetOf()

    public fun module(value: String): GradleTaskConfigurator = apply {
        module = value
    }

    public fun option(option: String): GradleTaskConfigurator = apply {
        options.add(option)
    }

    public fun options(vararg options: String): GradleTaskConfigurator = apply {
        this.options.addAll(options)
    }

    internal fun build(): GradleTask = GradleTask(task = task, module = module, options = options)
}

public data class GradleTask(
    val task: String,
    val module: String? = null,
    val options: LinkedHashSet<String> = linkedSetOf()
) {
    internal fun toArguments(): List<String> {
        val taskArg = if (!module.isNullOrBlank()) {
            "$module:$task"
        } else {
            task
        }
        return listOf(taskArg, *options.toTypedArray())
    }

    internal fun toArgString(): String {
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

    public companion object {
        public fun task(
            name: String, vararg options: String
        ): GradleTask = GradleTask(task = name, options = linkedSetOf(*options))

        public fun moduleTask(
            module: String, task: String, vararg options: String
        ): GradleTask = GradleTask(task = task, module = module, options = linkedSetOf(*options))
    }
}
