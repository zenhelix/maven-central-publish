package io.github.zenhelix.gradle.plugin.extension

import java.io.Serializable
import org.gradle.api.Action
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.newInstance
import org.gradle.kotlin.dsl.property
import javax.inject.Inject

/**
 * Main POM metadata extension for Maven Central publishing.
 *
 * Example usage:
 * ```kotlin
 * pom {
 *     name.set("My Library")
 *     description.set("A useful library")
 *     url.set("https://github.com/org/repo")
 *     inceptionYear.set("2024")
 *     license { apache2() }
 *     developer {
 *         id.set("dev1")
 *         name.set("Developer One")
 *         email.set("dev1@example.com")
 *     }
 *     scm { fromGithub("org", "repo") }
 * }
 * ```
 */
public open class PomExtension @Inject constructor(private val objects: ObjectFactory) {

    public val name: Property<String> = objects.property<String>()
    public val description: Property<String> = objects.property<String>()
    public val url: Property<String> = objects.property<String>()
    public val inceptionYear: Property<String> = objects.property<String>()

    private val _licenses: MutableList<PomLicenseData> = mutableListOf()
    public val licenses: List<PomLicenseData> get() = _licenses

    private val _developers: MutableList<PomDeveloperData> = mutableListOf()
    public val developers: List<PomDeveloperData> get() = _developers

    public val scm: PomScmExtension = objects.newInstance<PomScmExtension>()

    public fun license(configure: Action<PomLicenseBuilder>) {
        val builder = objects.newInstance<PomLicenseBuilder>()
        configure.execute(builder)
        _licenses.add(builder.build())
    }

    public fun developer(configure: Action<PomDeveloperBuilder>) {
        val builder = objects.newInstance<PomDeveloperBuilder>()
        configure.execute(builder)
        _developers.add(builder.build())
    }

    public fun scm(configure: Action<PomScmExtension>) {
        configure.execute(scm)
    }
}

/**
 * Builder for POM license entries. Supports preset methods for common open source licenses.
 */
public open class PomLicenseBuilder @Inject constructor(objects: ObjectFactory) {

    public val name: Property<String> = objects.property<String>()
    public val url: Property<String> = objects.property<String>()
    public val distribution: Property<String> = objects.property<String>().convention("repo")

    /** Apply Apache License 2.0 preset values. */
    public fun apache2(): Unit = applyPreset(LicensePresets.apache2())

    /** Apply MIT License preset values. */
    public fun mit(): Unit = applyPreset(LicensePresets.mit())

    /** Apply BSD 2-Clause License preset values. */
    public fun bsd2(): Unit = applyPreset(LicensePresets.bsd2())

    /** Apply BSD 3-Clause License preset values. */
    public fun bsd3(): Unit = applyPreset(LicensePresets.bsd3())

    private fun applyPreset(preset: PomLicenseData) {
        name.set(preset.name)
        url.set(preset.url)
        distribution.set(preset.distribution)
    }

    internal fun build(): PomLicenseData {
        val licenseName = requireNotNull(name.orNull) { "License 'name' is required. Use a preset (e.g., apache2()) or set name.set(\"...\")." }
        return PomLicenseData(
            name = licenseName,
            url = url.orNull,
            distribution = distribution.orNull
        )
    }
}

/**
 * Builder for POM developer entries.
 */
public open class PomDeveloperBuilder @Inject constructor(objects: ObjectFactory) {

    public val id: Property<String> = objects.property<String>()
    public val name: Property<String> = objects.property<String>()
    public val email: Property<String> = objects.property<String>()
    public val url: Property<String> = objects.property<String>()

    internal fun build(): PomDeveloperData {
        require(id.orNull != null || name.orNull != null) {
            "Developer must have at least 'id' or 'name'. Set developer { id.set(\"...\") } or developer { name.set(\"...\") }."
        }
        return PomDeveloperData(
            id = id.orNull,
            name = name.orNull,
            email = email.orNull,
            url = url.orNull
        )
    }
}

/**
 * SCM configuration extension for POM metadata. Supports a [fromGithub] shortcut for GitHub projects.
 */
public open class PomScmExtension @Inject constructor(objects: ObjectFactory) {

    public val connection: Property<String> = objects.property<String>()
    public val developerConnection: Property<String> = objects.property<String>()
    public val url: Property<String> = objects.property<String>()

    /** Populate SCM fields from a GitHub owner and repository name. */
    public fun fromGithub(owner: String, repo: String) {
        val data = ScmDefaults.fromGithub(owner, repo)
        connection.set(data.connection)
        developerConnection.set(data.developerConnection)
        url.set(data.url)
    }
}

/** Immutable data holder for a POM license entry. */
public data class PomLicenseData(
    val name: String?,
    val url: String?,
    val distribution: String?
) : Serializable

/** Immutable data holder for a POM developer entry. */
public data class PomDeveloperData(
    val id: String?,
    val name: String?,
    val email: String?,
    val url: String?
) : Serializable

/** Immutable data holder for POM SCM metadata. */
public data class PomScmData(
    val connection: String,
    val developerConnection: String,
    val url: String
)

/** Factory object for common open source license presets. */
public object LicensePresets {

    /** Apache License, Version 2.0. */
    public fun apache2(): PomLicenseData = PomLicenseData(
        name = "The Apache License, Version 2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt",
        distribution = "repo"
    )

    /** MIT License. */
    public fun mit(): PomLicenseData = PomLicenseData(
        name = "MIT License",
        url = "https://opensource.org/licenses/MIT",
        distribution = "repo"
    )

    /** BSD 2-Clause License. */
    public fun bsd2(): PomLicenseData = PomLicenseData(
        name = "BSD 2-Clause License",
        url = "https://opensource.org/licenses/BSD-2-Clause",
        distribution = "repo"
    )

    /** BSD 3-Clause License. */
    public fun bsd3(): PomLicenseData = PomLicenseData(
        name = "BSD 3-Clause License",
        url = "https://opensource.org/licenses/BSD-3-Clause",
        distribution = "repo"
    )
}

/** Factory object for generating SCM metadata from common hosting providers. */
public object ScmDefaults {

    /** Generate [PomScmData] for a GitHub repository. */
    public fun fromGithub(owner: String, repo: String): PomScmData = PomScmData(
        connection = "scm:git:git://github.com/$owner/$repo.git",
        developerConnection = "scm:git:ssh://github.com/$owner/$repo.git",
        url = "https://github.com/$owner/$repo"
    )
}
