package io.github.zenhelix.gradle.plugin.client.model

import java.io.Serializable
import java.util.Base64

public sealed class Credentials : Serializable {
    public data class UsernamePasswordCredentials(val username: String, val password: String) : Credentials()
    public data class BearerTokenCredentials(val token: String) : Credentials()

    public val bearerToken: String by lazy {
        when (this) {
            is BearerTokenCredentials      -> this.token
            is UsernamePasswordCredentials -> Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Credentials

        return bearerToken == other.bearerToken
    }

    override fun hashCode(): Int {
        return bearerToken.hashCode()
    }

}
