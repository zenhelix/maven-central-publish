package test.testkit

import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.Assertions.assertThat
import test.testkit.parser.GradleDryRunOutput

public class GradleDryRunOutputAssert(actual: GradleDryRunOutput) :
    AbstractAssert<GradleDryRunOutputAssert, GradleDryRunOutput>(actual, GradleDryRunOutputAssert::class.java) {

    public companion object {
        public fun assertThat(actual: GradleDryRunOutput): GradleDryRunOutputAssert = GradleDryRunOutputAssert(actual)
    }

    public fun containsTask(taskPath: String): GradleDryRunOutputAssert = apply {
        assertThat(actual.tasks.map { it.fullTaskPath }).contains(taskPath)
    }

    public fun containsExactlyTasksInOrder(vararg taskPaths: String): GradleDryRunOutputAssert = apply {
        assertThat(actual.tasks.map { it.fullTaskPath }).containsExactly(*taskPaths)
    }

    public fun containsTasksInOrder(vararg taskPaths: String): GradleDryRunOutputAssert = apply {
        val actualPaths = actual.tasks.map { it.fullTaskPath }
        val indices = taskPaths.map { path ->
            actualPaths.indexOf(path).also { index ->
                assertThat(index).`as`("Task $path not found").isGreaterThanOrEqualTo(0)
            }
        }
        assertThat(indices).`as`("Tasks not in expected order: $taskPaths").isSorted
    }

    public fun doesNotContainTask(taskPath: String): GradleDryRunOutputAssert = apply {
        assertThat(actual.tasks.map { it.fullTaskPath }).doesNotContain(taskPath)
    }

    public fun hasTaskCount(expectedCount: Int): GradleDryRunOutputAssert = apply {
        assertThat(actual.tasks).hasSize(expectedCount)
    }

    public fun containsModuleTasks(module: String?, vararg taskNames: String): GradleDryRunOutputAssert = apply {
        val moduleTasks = actual.tasksByModule(module).map { it.task }
        assertThat(moduleTasks).contains(*taskNames)
    }

    public fun containsExactlyModuleTasksInOrder(module: String?, vararg taskNames: String): GradleDryRunOutputAssert =
        apply {
            val moduleTasks = actual.tasksByModule(module).map { it.task }
            assertThat(moduleTasks).containsExactly(*taskNames)
        }

    public fun moduleTasksAreInOrder(module: String?, vararg taskNames: String): GradleDryRunOutputAssert = apply {
        val moduleTasks = actual.tasksByModule(module)
        val indices = taskNames.map { taskName ->
            moduleTasks.indexOfFirst { it.task == taskName }.also { index ->
                assertThat(index).`as`("Task $taskName not found in module $module").isGreaterThanOrEqualTo(0)
            }
        }
        assertThat(indices).`as`("Tasks not in expected order in module $module: $taskNames").isSorted
    }

    public fun taskIsBeforeTask(firstTaskPath: String, secondTaskPath: String): GradleDryRunOutputAssert = apply {
        val paths = actual.tasks.map { it.fullTaskPath }
        val firstIndex = paths.indexOf(firstTaskPath)
        val secondIndex = paths.indexOf(secondTaskPath)

        assertThat(firstIndex).`as`("Task $firstTaskPath not found").isGreaterThanOrEqualTo(0)
        assertThat(secondIndex).`as`("Task $secondTaskPath not found").isGreaterThanOrEqualTo(0)
        assertThat(firstIndex).`as`("$firstTaskPath should be before $secondTaskPath").isLessThan(secondIndex)
    }

    public fun allTasksHaveStatus(status: String): GradleDryRunOutputAssert = apply {
        assertThat(actual.tasks.map { it.status }).allMatch { it == status }
    }

    public fun taskHasStatus(taskPath: String, status: String): GradleDryRunOutputAssert = apply {
        val task = actual.tasks.find { it.fullTaskPath == taskPath }
        assertThat(task).`as`("Task $taskPath not found").isNotNull()
        assertThat(task!!.status).isEqualTo(status)
    }

    public fun containsTasksFromModules(vararg modules: String): GradleDryRunOutputAssert = apply {
        val actualModules = actual.tasks.mapNotNull { it.module }.distinct()
        assertThat(actualModules).contains(*modules)
    }

    public fun moduleHasTasksBefore(module: String, otherModule: String): GradleDryRunOutputAssert = apply {
        val moduleIndices = actual.tasks
            .mapIndexedNotNull { index, task -> if (task.module == module) index else null }
        val otherModuleIndices = actual.tasks
            .mapIndexedNotNull { index, task -> if (task.module == otherModule) index else null }

        assertThat(moduleIndices).`as`("Module $module has no tasks").isNotEmpty
        assertThat(otherModuleIndices).`as`("Module $otherModule has no tasks").isNotEmpty

        val lastModuleIndex = moduleIndices.maxOrNull()!!
        val firstOtherModuleIndex = otherModuleIndices.minOrNull()!!

        assertThat(lastModuleIndex)
            .`as`("Last task of $module should be before first task of $otherModule")
            .isLessThan(firstOtherModuleIndex)
    }

    public fun rootProjectTasksAreInOrder(vararg taskNames: String): GradleDryRunOutputAssert =
        moduleTasksAreInOrder(null, *taskNames)

    public fun containsRootProjectTasks(vararg taskNames: String): GradleDryRunOutputAssert =
        containsModuleTasks(null, *taskNames)

    public fun containsExactlyRootTasksInOrder(vararg taskNames: String) =
        containsExactlyModuleTasksInOrder(null, *taskNames)

}