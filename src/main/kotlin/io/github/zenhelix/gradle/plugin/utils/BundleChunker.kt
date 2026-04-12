package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.ChunkError
import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success

public data class ModuleSize(val name: String, val sizeBytes: Long)

public data class Chunk(val moduleNames: List<String>, val totalSize: Long)

public object BundleChunker {

    public fun chunk(modules: List<ModuleSize>, maxChunkSize: Long): Outcome<List<Chunk>, ChunkError> {
        if (modules.isEmpty()) return Success(emptyList())

        modules.forEach { module ->
            if (module.sizeBytes > maxChunkSize) {
                return Failure(ChunkError.ModuleTooLarge(module.name, module.sizeBytes, maxChunkSize))
            }
        }

        val sorted = modules.sortedByDescending { it.sizeBytes }

        val chunks = mutableListOf<MutableChunk>()

        for (module in sorted) {
            chunks.firstOrNull { it.remainingCapacity(maxChunkSize) >= module.sizeBytes }
                ?.add(module)
                ?: chunks.add(MutableChunk().apply { add(module) })
        }

        return Success(chunks.map { Chunk(moduleNames = it.moduleNames, totalSize = it.totalSize) })
    }

    private class MutableChunk {
        val moduleNames = mutableListOf<String>()
        var totalSize: Long = 0L
            private set

        fun add(module: ModuleSize) {
            moduleNames.add(module.name)
            totalSize += module.sizeBytes
        }

        fun remainingCapacity(maxSize: Long): Long = maxSize - totalSize
    }
}
