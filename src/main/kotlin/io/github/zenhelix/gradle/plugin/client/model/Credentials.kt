package io.github.zenhelix.gradle.plugin.client.model

import java.io.Serializable
import java.util.Base64

public sealed class Credentials : Serializable {
    public data class UsernamePasswordCredentials(val username: String, val password: String) : Credentials()
    public data class BearerTokenCredentials(val token: String) : Credentials()

    public val bearerToken: String
        get() = when (this) {
            is BearerTokenCredentials -> token
            is UsernamePasswordCredentials -> Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        }
}
