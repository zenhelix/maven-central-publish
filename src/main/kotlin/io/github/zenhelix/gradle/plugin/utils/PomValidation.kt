package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success

internal data class PomValidationError(
    val publicationName: String,
    val missingFields: List<String>
) {
    fun toMessage(): String =
        "Publication '$publicationName' is missing required POM fields for Maven Central: ${missingFields.joinToString(", ")}. " +
            "Configure them via mavenCentralPortal { pom { ... } } or the standard publishing { publications { ... { pom { ... } } } } block."
}

internal fun validatePomFields(
    name: String?,
    description: String?,
    url: String?,
    hasLicenses: Boolean,
    hasDevelopers: Boolean,
    hasScm: Boolean
): Outcome<Unit, PomValidationError> {
    val missing = buildList {
        if (name.isNullOrBlank()) add("name")
        if (description.isNullOrBlank()) add("description")
        if (url.isNullOrBlank()) add("url")
        if (!hasLicenses) add("licenses")
        if (!hasDevelopers) add("developers")
        if (!hasScm) add("scm")
    }

    return if (missing.isEmpty()) {
        Success(Unit)
    } else {
        Failure(PomValidationError(publicationName = "", missingFields = missing))
    }
}
