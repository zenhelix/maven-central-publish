package io.github.zenhelix.gradle.plugin.client.model

import java.util.UUID

@JvmInline
public value class DeploymentId(public val value: UUID) {
    public override fun toString(): String = value.toString()

    public companion object {
        public fun fromString(value: String): DeploymentId = DeploymentId(UUID.fromString(value))
        public fun random(): DeploymentId = DeploymentId(UUID.randomUUID())
    }
}
