package io.github.zenhelix.gradle.plugin.client.model

import io.github.zenhelix.gradle.plugin.extension.toDisplayMB

internal sealed class ChunkError(val message: String) {
    data class ModuleTooLarge(val moduleName: String, val moduleSize: Long, val maxSize: Long)
        : ChunkError(
            "Module '$moduleName' artifacts size ($moduleSize bytes / ${moduleSize.toDisplayMB()} MB) " +
                "exceeds maxBundleSize ($maxSize bytes / ${maxSize.toDisplayMB()} MB). " +
                "Reduce artifact size or increase maxBundleSize."
        )
}

internal fun ChunkError.toGradleException(): MavenCentralChunkException =
    MavenCentralChunkException(error = this, message = message)
