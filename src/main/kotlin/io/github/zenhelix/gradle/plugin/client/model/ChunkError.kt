package io.github.zenhelix.gradle.plugin.client.model

import io.github.zenhelix.gradle.plugin.utils.toDisplayMB
import org.gradle.api.GradleException

public sealed class ChunkError(public val message: String) {
    public data class ModuleTooLarge(val moduleName: String, val moduleSize: Long, val maxSize: Long)
        : ChunkError(
            "Module '$moduleName' artifacts size ($moduleSize bytes / ${moduleSize.toDisplayMB()} MB) " +
                "exceeds maxBundleSize ($maxSize bytes / ${maxSize.toDisplayMB()} MB). " +
                "Reduce artifact size or increase maxBundleSize."
        )
}

public fun ChunkError.toGradleException(): GradleException = GradleException(message)
