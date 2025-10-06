package test.utils

import java.util.zip.ZipFile
import org.assertj.core.api.AbstractAssert
import org.assertj.core.api.Assertions.assertThat

class ZipFileAssert(actual: ZipFile) : AbstractAssert<ZipFileAssert, ZipFile>(actual, ZipFileAssert::class.java) {

    companion object {
        fun assertThat(actual: ZipFile): ZipFileAssert = ZipFileAssert(actual)
    }

    fun containsExactlyInAnyOrderFiles(vararg file: String): ZipFileAssert = apply {
        assertThat(
            actual.entries().toList().filter { !it.isDirectory }.map { it.name }
        ).containsExactlyInAnyOrder(*file)
    }

    fun containsFiles(vararg files: String): ZipFileAssert = apply {
        val actualFiles = actual.entries().toList().filter { !it.isDirectory }.map { it.name }
        assertThat(actualFiles).contains(*files)
    }

    fun doesNotContainFiles(vararg files: String): ZipFileAssert = apply {
        val actualFiles = actual.entries().toList().filter { !it.isDirectory }.map { it.name }
        assertThat(actualFiles).doesNotContain(*files)
    }

    fun isEqualTextContentTo(entryName: String, content: String): ZipFileAssert = apply {
        val entry = actual.getEntry(entryName)
        assertThat(entry).isNotNull
        assertThat(entry.isDirectory).isFalse

        val actualText = actual.getInputStream(entry).bufferedReader().use { it.readLines() }
        assertThat(actualText.map { it.trim() }).isEqualTo(content.lines().map { it.trim() })
    }

}

data class MavenCoordinates(
    val groupId: String,
    val artifactId: String,
    val version: String
) {
    val groupPath: String
        get() = groupId.replace('.', '/')
}

data class MavenArtifactSpec(
    val coordinates: MavenCoordinates,
    val extension: String,
    val classifier: String? = null
) {
    val fileName: String
        get() = buildString {
            append(coordinates.artifactId)
            append('-')
            append(coordinates.version)
            if (classifier != null) {
                append('-')
                append(classifier)
            }
            append('.')
            append(extension)
        }

    val relativePath: String
        get() = "${coordinates.groupPath}/${coordinates.artifactId}/${coordinates.version}/$fileName"

}

interface ArtifactFileVariant {
    fun apply(basePath: String): String

    companion object {
        val ORIGINAL = object : ArtifactFileVariant {
            override fun apply(basePath: String) = basePath
        }

        fun withSuffix(suffix: String) = object : ArtifactFileVariant {
            override fun apply(basePath: String) = "$basePath.$suffix"
        }
    }
}

data class ArtifactFileSet(
    val variants: List<ArtifactFileVariant>
) {
    fun expand(artifact: MavenArtifactSpec): List<String> =
        variants.map { it.apply(artifact.relativePath) }

    companion object {
        val MAVEN_STANDARD = ArtifactFileSet(
            listOf(
                ArtifactFileVariant.ORIGINAL,
                ArtifactFileVariant.withSuffix("asc"),
                ArtifactFileVariant.withSuffix("sha1"),
                ArtifactFileVariant.withSuffix("md5"),
                ArtifactFileVariant.withSuffix("sha256"),
                ArtifactFileVariant.withSuffix("sha512")
            )
        )

        val MINIMAL = ArtifactFileSet(
            listOf(ArtifactFileVariant.ORIGINAL)
        )

        fun custom(vararg suffixes: String) = ArtifactFileSet(
            listOf(ArtifactFileVariant.ORIGINAL) + suffixes.map { ArtifactFileVariant.withSuffix(it) }
        )
    }
}

class MavenArtifactsBuilder(
    private val coordinates: MavenCoordinates,
    private val fileSet: ArtifactFileSet = ArtifactFileSet.MAVEN_STANDARD
) {
    private val specs = mutableListOf<MavenArtifactSpec>()

    fun artifact(
        artifactId: String = coordinates.artifactId,
        extension: String,
        classifier: String? = null
    ) = apply {
        specs += MavenArtifactSpec(
            coordinates.copy(artifactId = artifactId),
            extension,
            classifier
        )
    }

    fun jar(artifactId: String = coordinates.artifactId, classifier: String? = null) =
        artifact(artifactId, "jar", classifier)

    fun pom(artifactId: String = coordinates.artifactId) =
        artifact(artifactId, "pom")

    fun module(artifactId: String = coordinates.artifactId) =
        artifact(artifactId, "module")

    fun file(artifactId: String = coordinates.artifactId, extension: String, classifier: String? = null) =
        artifact(artifactId, extension, classifier)

    fun standardJavaLibrary(
        artifactId: String = coordinates.artifactId,
        withSources: Boolean = false,
        withJavadoc: Boolean = false
    ) = apply {
        jar(artifactId)
        if (withSources) jar(artifactId, "sources")
        if (withJavadoc) jar(artifactId, "javadoc")
        pom(artifactId)
        module(artifactId)
    }

    fun standardKotlinLibrary(
        artifactId: String = coordinates.artifactId,
        withSources: Boolean = true
    ) = apply {
        jar(artifactId)
        if (withSources) jar(artifactId, "sources")
        pom(artifactId)
        module(artifactId)
    }

    fun kotlinTarget(
        platform: String,
        baseArtifactId: String = coordinates.artifactId,
        withSources: Boolean = true
    ) = apply {
        val artifactId = "$baseArtifactId-$platform"
        when (platform) {
            "jvm", "android" -> standardKotlinLibrary(artifactId, withSources)
            else -> {
                file(artifactId, "klib")
                if (withSources) jar(artifactId, "sources")
                pom(artifactId)
                module(artifactId)
            }
        }
    }

    fun kotlinMultiplatform(
        baseArtifactId: String = coordinates.artifactId,
        targets: List<String>,
        withToolingMetadata: Boolean = true
    ) = apply {
        standardKotlinLibrary(baseArtifactId)
        if (withToolingMetadata) {
            file(baseArtifactId, "json", "kotlin-tooling-metadata")
        }
        targets.forEach { kotlinTarget(it, baseArtifactId) }
    }

    fun gradlePlatform(artifactId: String = coordinates.artifactId) = apply {
        pom(artifactId)
        module(artifactId)
    }

    fun versionCatalog(artifactId: String = coordinates.artifactId) = apply {
        file(artifactId, "toml")
        pom(artifactId)
        module(artifactId)
    }

    fun build(): List<String> = specs.flatMap { fileSet.expand(it) }

}

fun mavenArtifacts(
    groupId: String,
    artifactId: String,
    version: String,
    fileSet: ArtifactFileSet = ArtifactFileSet.MAVEN_STANDARD,
    block: MavenArtifactsBuilder.() -> Unit
): List<String> = MavenArtifactsBuilder(
    MavenCoordinates(groupId, artifactId, version),
    fileSet
).apply(block).build()

fun ZipFileAssert.containsMavenArtifacts(
    groupId: String,
    artifactId: String,
    version: String,
    fileSet: ArtifactFileSet = ArtifactFileSet.MAVEN_STANDARD,
    block: MavenArtifactsBuilder.() -> Unit
): ZipFileAssert = containsExactlyInAnyOrderFiles(
    *mavenArtifacts(groupId, artifactId, version, fileSet, block).toTypedArray()
)

fun ZipFileAssert.containsSomeMavenArtifacts(
    groupId: String,
    artifactId: String,
    version: String,
    fileSet: ArtifactFileSet = ArtifactFileSet.MAVEN_STANDARD,
    block: MavenArtifactsBuilder.() -> Unit
): ZipFileAssert = containsFiles(
    *mavenArtifacts(groupId, artifactId, version, fileSet, block).toTypedArray()
)