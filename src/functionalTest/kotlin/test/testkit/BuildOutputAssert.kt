package test.testkit

import org.assertj.core.api.AbstractStringAssert
import org.assertj.core.api.Assertions.assertThat

class BuildOutputAssert(actual: String) : AbstractStringAssert<BuildOutputAssert>(
    actual, BuildOutputAssert::class.java
) {

    companion object {
        fun assertThat(actual: String): BuildOutputAssert = BuildOutputAssert(actual)
    }

    fun containsPublishingLog(
        bundleFileName: String,
        publishingType: String? = null,
        deploymentName: String? = null
    ): BuildOutputAssert = apply {
        val typeStr = publishingType ?: "AUTOMATIC"
        val nameStr = deploymentName ?: "null"
        val expectedLog =
            "Publishing deployment bundle: $bundleFileName. Publishing type: $typeStr. Deployment name: $nameStr"

        assertThat(actual)
            .`as`("Build output should contain publishing log")
            .contains(expectedLog)
    }

    fun containsPublishingLogCount(count: Int): BuildOutputAssert = apply {
        val regex = Regex.escape("Publishing deployment bundle").toRegex()
        val actualCount = regex.findAll(actual).count()

        assertThat(actualCount)
            .`as`("Build output should contain publishing log exactly $count times")
            .isEqualTo(count)
    }

    fun containsUploadingChunkLog(chunkFileName: String): BuildOutputAssert = apply {
        assertThat(actual)
            .`as`("Build output should contain uploading chunk log for $chunkFileName")
            .contains("Uploading chunk")
            .contains(chunkFileName)
    }

    fun containsUploadingChunkLogCount(count: Int): BuildOutputAssert = apply {
        val regex = Regex.escape("Uploading chunk").toRegex()
        // Each chunk upload produces 2 lines: "Uploading chunk N/T: file..." and "Uploading chunk N/T... OK ..."
        // Count only lines starting with "Uploading chunk" followed by digits (e.g. "Uploading chunk 1/")
        val actualCount = "Uploading chunk \\d+/\\d+:".toRegex().findAll(actual).count()

        assertThat(actualCount)
            .`as`("Build output should contain uploading chunk log exactly $count times")
            .isEqualTo(count)
    }

}