package test.utils

import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.Assertions
import java.util.zip.ZipFile

class ZipFileAssert(actual: ZipFile) : AbstractAssert<ZipFileAssert, ZipFile>(actual, ZipFileAssert::class.java) {

    companion object {
        fun assertThat(actual: ZipFile): ZipFileAssert = ZipFileAssert(actual)
    }

    fun containsExactlyInAnyOrderFiles(vararg file: String): ZipFileAssert = apply {
        Assertions.assertThat(
            actual.entries().toList().filter { !it.isDirectory }.map { it.name }
        ).containsExactlyInAnyOrder(*file)
    }

    fun isEqualTextContentTo(entryName: String, content: String): ZipFileAssert = apply {
        val entry = actual.getEntry(entryName)
        Assertions.assertThat(entry).isNotNull
        Assertions.assertThat(entry.isDirectory).isFalse
        Assertions.assertThat(actual.getInputStream(entry).bufferedReader().use { it.readText() }).isEqualTo(content)
    }

}