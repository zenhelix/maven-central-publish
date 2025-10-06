package test.testkit

import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.Assertions.assertThat
import test.testkit.parser.GradleOutput

public class GradleTasksOutputAssert(actual: GradleOutput) :
    AbstractAssert<GradleTasksOutputAssert, GradleOutput>(actual, GradleTasksOutputAssert::class.java) {

    public companion object {
        public fun assertThat(actual: GradleOutput): GradleTasksOutputAssert = GradleTasksOutputAssert(actual)
    }

    // Category assertions
    public fun containsCategory(vararg categoryName: String): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.map { it.name }).contains(*categoryName)
    }

    public fun containsExactlyCategoryInAnyOrder(vararg categoryName: String): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.map { it.name }).containsExactlyInAnyOrder(*categoryName)
    }

    public fun doesNotContainCategory(vararg categoryName: String): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.map { it.name }).doesNotContain(*categoryName)
    }

    // Task assertions
    public fun containsTask(vararg taskName: String): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.flatMap { it.tasks }.map { it.name }).contains(*taskName)
    }

    public fun containsExactlyTaskInAnyOrder(vararg taskName: String): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.flatMap { it.tasks }.map { it.name }).containsExactlyInAnyOrder(*taskName)
    }

    public fun doesNotContainTask(vararg taskName: String): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.flatMap { it.tasks }.map { it.name }).doesNotContain(*taskName)
    }

    // Task in Category assertions
    public fun containsTaskInCategory(categoryName: String, vararg taskNames: String): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.map { it.name }).contains(categoryName)
        assertThat(actual.categories.first { it.name == categoryName }.tasks.map { it.name }).contains(*taskNames)
    }

    public fun containsExactlyTaskInCategoryInAnyOrder(
        categoryName: String,
        vararg taskNames: String
    ): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.map { it.name }).contains(categoryName)
        assertThat(actual.categories.first { it.name == categoryName }.tasks.map { it.name }).containsExactlyInAnyOrder(
            *taskNames
        )
    }

    public fun doesNotContainTaskInCategory(categoryName: String, vararg taskNames: String): GradleTasksOutputAssert =
        apply {
            if (actual.categories.any { it.name == categoryName }) {
                assertThat(actual.categories.first { it.name == categoryName }.tasks.map { it.name }).doesNotContain(*taskNames)
            }
        }

    public fun categoryIsEmpty(categoryName: String): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.map { it.name }).contains(categoryName)
        assertThat(actual.categories.first { it.name == categoryName }.tasks).isEmpty()
    }

    // Rule assertions
    public fun containsRule(vararg pattern: String): GradleTasksOutputAssert = apply {
        assertThat(actual.rules.map { it.pattern }).contains(*pattern)
    }

    public fun containsExactlyRuleInAnyOrder(vararg pattern: String): GradleTasksOutputAssert = apply {
        assertThat(actual.rules.map { it.pattern }).containsExactlyInAnyOrder(*pattern)
    }

    public fun doesNotContainRule(vararg pattern: String): GradleTasksOutputAssert = apply {
        assertThat(actual.rules.map { it.pattern }).doesNotContain(*pattern)
    }

    // Description assertions
    public fun containsRuleWithDescription(pattern: String, description: String): GradleTasksOutputAssert = apply {
        assertThat(actual.rules.map { it.pattern }).contains(pattern)
        assertThat(actual.rules.first { it.pattern == pattern }.description).isEqualTo(description)
    }

    public fun containsTaskWithDescription(taskName: String, description: String): GradleTasksOutputAssert = apply {
        val task = actual.categories.flatMap { it.tasks }.find { it.name == taskName }
        assertThat(task).isNotNull()
        assertThat(task!!.description).isEqualTo(description)
    }

    // Empty assertions
    public fun rulesIsEmpty(): GradleTasksOutputAssert = apply {
        assertThat(actual.rules).isEmpty()
    }

    public fun categoriesIsEmpty(): GradleTasksOutputAssert = apply {
        assertThat(actual.categories).isEmpty()
    }

    // Count assertions
    public fun categoryHasTaskCount(categoryName: String, taskCount: Int): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.map { it.name }).contains(categoryName)
        assertThat(actual.categories.first { it.name == categoryName }.tasks).hasSize(taskCount)
    }

    public fun tasksHasSize(expectedCount: Int): GradleTasksOutputAssert = apply {
        assertThat(actual.categories.flatMap { it.tasks }).hasSize(expectedCount)
    }

    public fun categoriesHasSize(expectedCount: Int): GradleTasksOutputAssert = apply {
        assertThat(actual.categories).hasSize(expectedCount)
    }

    public fun rulesHasSize(expectedCount: Int): GradleTasksOutputAssert = apply {
        assertThat(actual.rules).hasSize(expectedCount)
    }

}