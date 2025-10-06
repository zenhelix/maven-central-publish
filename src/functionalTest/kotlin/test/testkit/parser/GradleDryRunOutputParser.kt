package test.testkit.parser

public data class DryRunTask(
    val module: String?,
    val task: String,
    val status: String
) {
    val fullTaskPath: String
        get() = if (module.isNullOrEmpty()) ":$task" else ":$module:$task"
}

public data class GradleDryRunOutput(
    val tasks: List<DryRunTask>
) {
    fun tasksByModule(module: String?): List<DryRunTask> =
        tasks.filter { it.module == module }

    fun tasksByModulePattern(pattern: Regex): List<DryRunTask> =
        tasks.filter { pattern.matches(it.module.orEmpty()) }
}

private val TASK_LINE_PATTERN = Regex("""^:(?:([^:]+):)?([^\s]+)\s+(.+)$""")

public fun parseGradleDryRunOutput(output: String): GradleDryRunOutput {
    val tasks = output.lineSequence()
        .mapNotNull { line ->
            val trimmed = line.trim()
            TASK_LINE_PATTERN.matchEntire(trimmed)?.let { match ->
                val module = match.groupValues[1].takeIf { it.isNotEmpty() }
                val task = match.groupValues[2]
                val status = match.groupValues[3]
                DryRunTask(module, task, status)
            }
        }
        .toList()

    return GradleDryRunOutput(tasks)
}