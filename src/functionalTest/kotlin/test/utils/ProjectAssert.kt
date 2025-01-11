package test.utils

import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.Project
import org.gradle.api.artifacts.DependencySet

class ProjectAssert(actual: Project) : AbstractAssert<ProjectAssert, Project>(actual, ProjectAssert::class.java) {

    companion object {
        fun assertThat(actual: Project): ProjectAssert = ProjectAssert(actual)
    }

    fun hasDependency(configuration: String, group: String?, module: String, version: String?): ProjectAssert {
        assertThat(actual.configurations.findByName(configuration)?.dependencies?.exist(group, module, version)).isNotNull().isTrue()
        return this
    }

    fun hasInImplementation(group: String?, module: String, version: String? = null): ProjectAssert {
        hasDependency("implementation", group = group, module = module, version = version)
        return this
    }

    fun hasInRuntimeOnly(group: String?, module: String, version: String? = null): ProjectAssert {
        hasDependency("runtimeOnly", group = group, module = module, version = version)
        return this
    }

    fun hasInTestImplementation(group: String?, module: String, version: String? = null): ProjectAssert {
        hasDependency("testImplementation", group = group, module = module, version = version)
        return this
    }

    fun hasInAnnotationProcessor(group: String?, module: String, version: String? = null): ProjectAssert {
        hasDependency("annotationProcessor", group = group, module = module, version = version)
        return this
    }

    fun isEmptyConfiguration(configuration: String): ProjectAssert {
        assertThat(actual.configurations.findByName(configuration)?.dependencies?.isEmpty()).describedAs("$configuration is not empty").isNotNull().isTrue()
        return this
    }

    fun isEmptyImplementation(): ProjectAssert {
        isEmptyConfiguration("implementation")
        return this
    }

    fun isEmptyRuntimeOnly(): ProjectAssert {
        isEmptyConfiguration("runtimeOnly")
        return this
    }

    fun isEmptyTestImplementation(): ProjectAssert {
        isEmptyConfiguration("testImplementation")
        return this
    }

    fun isEmptyAnnotationProcessor(): ProjectAssert {
        isEmptyConfiguration("annotationProcessor")
        return this
    }

    fun isAllConfigurationsEmpty(): ProjectAssert {
        assertThat(actual.configurations.flatMap { it.allDependencies }).isEmpty()
        return this
    }

    fun containsOnlyInConfiguration(configuration: String, vararg notions: String): ProjectAssert {
        assertThat(actual.configurations.getByName(configuration).dependencies.map {
            listOfNotNull(it.group, it.name, it.version).joinToString(":")
        }).containsOnly(*notions)
        return this
    }

    fun containsOnlyInImplementation(vararg notions: String): ProjectAssert {
        containsOnlyInConfiguration("implementation", *notions)
        return this
    }

    fun containsOnlyInRuntimeOnly(vararg notions: String): ProjectAssert {
        containsOnlyInConfiguration("runtimeOnly", *notions)
        return this
    }

    fun containsOnlyInTestRuntimeOnly(vararg notions: String): ProjectAssert {
        containsOnlyInConfiguration("testRuntimeOnly", *notions)
        return this
    }

    fun containsOnlyInTestImplementation(vararg notions: String): ProjectAssert {
        containsOnlyInConfiguration("testImplementation", *notions)
        return this
    }

    fun containsOnlyInAnnotationProcessor(vararg notions: String): ProjectAssert {
        containsOnlyInConfiguration("annotationProcessor", *notions)
        return this
    }

    private fun DependencySet.find(group: String?, module: String, version: String? = null) =
        this.find { it.group == group && it.name == module && it.version == version }

    private fun DependencySet.exist(group: String?, module: String, version: String? = null) = this.find(group, module, version) != null

}