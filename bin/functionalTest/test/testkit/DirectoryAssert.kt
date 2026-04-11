package test.testkit

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.Assertions.assertThat
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.walk

@OptIn(ExperimentalPathApi::class)
public class DirectoryAssert(actual: Path) : AbstractAssert<DirectoryAssert, Path>(
    actual, DirectoryAssert::class.java
) {

    public companion object {
        public fun assertThat(actual: Path): DirectoryAssert = DirectoryAssert(actual)
    }

    init {
        isNotNull
    }

    // Existence assertions
    public fun exists(): DirectoryAssert = apply {
        assertThat(actual.exists()).`as`("Path should exist: $actual").isTrue()
    }

    public fun doesNotExist(): DirectoryAssert = apply {
        assertThat(actual.exists()).`as`("Path should not exist: $actual").isFalse()
    }

    public fun isDirectory(): DirectoryAssert = apply {
        exists()
        assertThat(actual.isDirectory()).`as`("Path should be a directory: $actual").isTrue()
    }

    public fun isRegularFile(): DirectoryAssert = apply {
        exists()
        assertThat(actual.isRegularFile()).`as`("Path should be a regular file: $actual").isTrue()
    }

    // File content assertions
    public fun containsFile(vararg relativePaths: String): DirectoryAssert = apply {
        isDirectory()
        val missing = relativePaths.filter { !actual.resolve(it).exists() }
        assertThat(missing).`as`("Directory should contain files: $missing").isEmpty()
    }

    public fun doesNotContainFile(vararg relativePaths: String): DirectoryAssert = apply {
        isDirectory()
        val existing = relativePaths.filter { actual.resolve(it).exists() }
        assertThat(existing).`as`("Directory should not contain files: $existing").isEmpty()
    }

    public fun containsOnlyFiles(vararg relativePaths: String): DirectoryAssert = apply {
        isDirectory()
        val actualFiles = actual.walk()
            .filter { it.isRegularFile() }
            .map { actual.relativize(it).toString() }
            .toSet()
        assertThat(actualFiles).containsExactlyInAnyOrder(*relativePaths)
    }

    public fun containsExactlyFiles(vararg relativePaths: String): DirectoryAssert = apply {
        return containsOnlyFiles(*relativePaths)
    }

    // Directory content assertions
    public fun containsDirectory(vararg relativePaths: String): DirectoryAssert = apply {
        isDirectory()
        val missing = relativePaths.filter { !actual.resolve(it).isDirectory() }
        assertThat(missing).`as`("Directory should contain subdirectories: $missing").isEmpty()
    }

    public fun doesNotContainDirectory(vararg relativePaths: String): DirectoryAssert = apply {
        isDirectory()
        val existing = relativePaths.filter { actual.resolve(it).isDirectory() }
        assertThat(existing).`as`("Directory should not contain subdirectories: $existing").isEmpty()
    }

    // Empty assertions
    public fun isEmpty(): DirectoryAssert = apply {
        isDirectory()
        assertThat(Files.list(actual).count()).`as`("Directory should be empty: $actual").isZero()
    }

    public fun isNotEmpty(): DirectoryAssert = apply {
        isDirectory()
        assertThat(Files.list(actual).count()).`as`("Directory should not be empty: $actual").isNotZero()
    }

    // Count assertions
    public fun hasFileCount(expectedCount: Int): DirectoryAssert = apply {
        isDirectory()
        val actualCount = actual.walk().filter { it.isRegularFile() }.count()
        assertThat(actualCount).`as`("Directory should contain $expectedCount files").isEqualTo(expectedCount)
    }

    public fun hasDirectoryCount(expectedCount: Int): DirectoryAssert = apply {
        isDirectory()
        val actualCount = Files.list(actual).filter { it.isDirectory() }.count()
        assertThat(actualCount).`as`("Directory should contain $expectedCount subdirectories").isEqualTo(expectedCount)
    }

    public fun hasItemCount(expectedCount: Int): DirectoryAssert = apply {
        isDirectory()
        val actualCount = Files.list(actual).count()
        assertThat(actualCount).`as`("Directory should contain $expectedCount items").isEqualTo(expectedCount)
    }

    // File content assertions
    public fun fileHasContent(relativePath: String, expectedContent: String): DirectoryAssert = apply {
        val file = actual.resolve(relativePath)
        assertThat(file.exists()).`as`("File should exist: $file").isTrue()
        assertThat(file.readText()).isEqualTo(expectedContent)
    }

    public fun fileContains(relativePath: String, expectedSubstring: String): DirectoryAssert = apply {
        val file = actual.resolve(relativePath)
        assertThat(file.exists()).`as`("File should exist: $file").isTrue()
        assertThat(file.readText()).contains(expectedSubstring)
    }

    public fun fileDoesNotContain(relativePath: String, unexpectedSubstring: String): DirectoryAssert = apply {
        val file = actual.resolve(relativePath)
        assertThat(file.exists()).`as`("File should exist: $file").isTrue()
        assertThat(file.readText()).doesNotContain(unexpectedSubstring)
    }

    // Pattern matching assertions
    public fun containsFileMatching(pattern: String): DirectoryAssert = apply {
        isDirectory()
        val regex = Regex(pattern)
        val hasMatch = actual.walk()
            .filter { it.isRegularFile() }
            .any { regex.matches(actual.relativize(it).toString()) }
        assertThat(hasMatch).`as`("Directory should contain file matching pattern: $pattern").isTrue()
    }

    public fun doesNotContainFileMatching(pattern: String): DirectoryAssert = apply {
        isDirectory()
        val regex = Regex(pattern)
        val hasMatch = actual.walk()
            .filter { it.isRegularFile() }
            .any { regex.matches(actual.relativize(it).toString()) }
        assertThat(hasMatch).`as`("Directory should not contain file matching pattern: $pattern").isFalse()
    }
}