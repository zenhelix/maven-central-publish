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

}