package test.utils

import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.Assertions.assertThat
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.BuildTask
import org.gradle.testkit.runner.TaskOutcome

class BuildResultAssert(actual: BuildResult) : AbstractAssert<BuildResultAssert, BuildResult>(actual, BuildResultAssert::class.java) {

    companion object {
        fun assertThat(actual: BuildResult): BuildResultAssert = BuildResultAssert(actual)

        private const val BUILD_SUCCESSFUL_TEXT = "BUILD SUCCESSFUL"
    }

    fun successfulBuild(): BuildResultAssert = apply {
        assertThat(actual.output).contains(BUILD_SUCCESSFUL_TEXT)
    }

    fun unsuccessfulBuild(): BuildResultAssert = apply {
        assertThat(actual.output).doesNotContain(BUILD_SUCCESSFUL_TEXT)
    }

    fun successTasks(module: String, vararg task: String): BuildResultAssert = apply {
        assertTasksByStatus(module = module, status = TaskOutcome.SUCCESS, task = task)
    }

    fun failedTasks(module: String, vararg task: String): BuildResultAssert = apply {
        assertTasksByStatus(module = module, status = TaskOutcome.FAILED, task = task)
    }

    fun upToDateTasks(module: String, vararg task: String): BuildResultAssert = apply {
        assertTasksByStatus(module = module, status = TaskOutcome.UP_TO_DATE, task = task)
    }

    fun skippedTasks(module: String, vararg task: String): BuildResultAssert = apply {
        assertTasksByStatus(module = module, status = TaskOutcome.SKIPPED, task = task)
    }

    fun fromCacheTasks(module: String, vararg task: String): BuildResultAssert = apply {
        assertTasksByStatus(module = module, status = TaskOutcome.FROM_CACHE, task = task)
    }

    fun noSourceTasks(module: String, vararg task: String): BuildResultAssert = apply {
        assertTasksByStatus(module = module, status = TaskOutcome.NO_SOURCE, task = task)
    }

    private fun assertTasksByStatus(module: String, status: TaskOutcome, vararg task: String) {
        assertThat(GradleModule.of(actual).module(module).allTasksByStatus(status).map { it.name }).containsExactlyInAnyOrder(*task)
    }

}

private data class GradleTask(val name: String, val result: TaskOutcome)

private data class GradleModule(
    val name: String,
    private val _submodules: MutableMap<String, GradleModule> = mutableMapOf(),
    private val _tasks: MutableList<GradleTask> = mutableListOf()
) {
    val submodules: Map<String, GradleModule> get() = _submodules
    val tasks: List<GradleTask> get() = _tasks

    fun addTask(task: GradleTask) {
        _tasks.add(task)
    }

    fun getOrCreateChild(name: String): GradleModule = _submodules.getOrPut(name) { GradleModule(name) }

    fun allModules(): List<GradleModule> = buildList {
        add(this@GradleModule)
        submodules.values.forEach { child -> addAll(child.allModules()) }
    }

    fun module(name: String): GradleModule = findModuleByNameOrNull(name) ?: throw NoSuchElementException("Module with name '$name' not found")

    fun allTasks(): List<GradleTask> = buildList {
        addAll(tasks)
        submodules.values.forEach { addAll(it.allTasks()) }
    }

    fun tasksByStatus(status: TaskOutcome): List<GradleTask> = tasks.filter { it.result == status }
    fun allTasksByStatus(status: TaskOutcome): List<GradleTask> = allTasks().filter { it.result == status }

    fun moduleTasks(moduleName: String): List<GradleTask> = module(moduleName).tasks
    fun allModuleTasks(moduleName: String): List<GradleTask> = module(moduleName).allTasks()

    private fun findModuleByNameOrNull(name: String): GradleModule? {
        if (this.name == name) return this

        submodules[name]?.let { return it }

        for (child in submodules.values) {
            val found = child.findModuleByNameOrNull(name)
            if (found != null) return found
        }

        return null
    }

    companion object {
        fun of(result: BuildResult): GradleModule = of(result.tasks)
        fun of(tasks: List<BuildTask>): GradleModule {
            val root = GradleModule("root")

            tasks.forEach { task ->
                val parts = task.path.split(":")
                val moduleParts = parts.drop(1).dropLast(1)
                val taskName = parts.last()

                if (moduleParts.isEmpty()) {
                    root.addTask(GradleTask(taskName, task.outcome))
                    return@forEach
                }

                var currentModule = root
                for (modulePart in moduleParts) {
                    currentModule = currentModule.getOrCreateChild(modulePart)
                }

                currentModule.addTask(GradleTask(taskName, task.outcome))
            }

            return root
        }
    }

    override fun toString(): String = HierarchyPrinter().printHierarchy(this)

    private class HierarchyPrinter {
        private val builder = StringBuilder()

        fun printHierarchy(module: GradleModule, indent: String = ""): String {
            builder.clear()
            appendHierarchy(module, indent)
            return builder.toString()
        }

        private fun appendHierarchy(module: GradleModule, indent: String = "") {
            builder.appendLine("$indent${module.name}")

            module.tasks.forEach { task ->
                builder.appendLine("$indent  ├── ${task.name} (${task.result})")
            }

            val iterator = module.submodules.values.iterator()
            while (iterator.hasNext()) {
                val childModule = iterator.next()
                val isLast = !iterator.hasNext()
                val childIndent = if (isLast) "$indent  └──" else "$indent  ├──"
                builder.appendLine("$childIndent ${childModule.name}")

                val newIndent = if (isLast) "$indent      " else "$indent  │   "
                childModule.tasks.forEach { task ->
                    builder.appendLine("$newIndent├── ${task.name} (${task.result})")
                }

                val childIterator = childModule.submodules.values.iterator()
                while (childIterator.hasNext()) {
                    val grandChildModule = childIterator.next()
                    val isChildLast = !childIterator.hasNext()
                    appendHierarchy(grandChildModule, if (isChildLast) "$newIndent└── " else "$newIndent├── ")
                }
            }
        }
    }
}

