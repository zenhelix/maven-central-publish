package test.utils

import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.Assertions.assertThat
import java.util.zip.ZipFile

class ZipFileAssert(actual: ZipFile) : AbstractAssert<ZipFileAssert, ZipFile>(actual, ZipFileAssert::class.java) {

    companion object {
        fun assertThat(actual: ZipFile): ZipFileAssert = ZipFileAssert(actual)
    }

    fun containsExactlyInAnyOrderFiles(vararg file: String): ZipFileAssert = apply {
        assertThat(
            actual.entries().toList().filter { !it.isDirectory }.map { it.name }
        ).containsExactlyInAnyOrder(*file)
    }

    fun isEqualTextContentTo(entryName: String, content: String): ZipFileAssert = apply {
        val entry = actual.getEntry(entryName)
        assertThat(entry).isNotNull
        assertThat(entry.isDirectory).isFalse

        val actualText = actual.getInputStream(entry).bufferedReader().use { it.readLines() }
        assertThat(actualText.map { it.trim() }).isEqualTo(content.lines().map { it.trim() })
    }

}