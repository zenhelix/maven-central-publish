package io.github.zenhelix.gradle.plugin.client.model

import org.gradle.api.GradleException

public sealed class ValidationError(public val message: String) {
    public data class MissingProperty(val property: String)
        : ValidationError("Property '$property' is required but not set")

    public data class InvalidFile(val path: String, val reason: String)
        : ValidationError("$reason: $path")

    public data class InvalidValue(val property: String, val detail: String)
        : ValidationError("$property: $detail")

    public data class AmbiguousCredentials(val detail: String) : ValidationError(detail)

    public data class MissingCredential(val detail: String) : ValidationError(detail)

    public data object NoCredentials : ValidationError(
        "No credentials configured. Use: credentials { bearer { token.set(\"...\") } } " +
            "or credentials { usernamePassword { username.set(\"...\"); password.set(\"...\") } }"
    )
}

public fun ValidationError.toGradleException(): GradleException = GradleException(message)
