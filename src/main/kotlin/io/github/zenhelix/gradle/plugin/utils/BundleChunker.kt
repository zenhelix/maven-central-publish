package io.github.zenhelix.gradle.plugin.utils

/**
 * Represents a module and its total artifact size in bytes.
 */
public data class ModuleSize(val name: String, val sizeBytes: Long)

/**
 * A group of modules that fit within the size limit.
 */
public data class Chunk(val moduleNames: List<String>, val totalSize: Long)

/**
 * Thrown when a single module exceeds the maximum bundle size.
 */
public class BundleSizeExceededException(
    public val moduleName: String,
    public val moduleSize: Long,
    public val maxSize: Long
) : RuntimeException(
    "Module '$moduleName' artifacts size ($moduleSize bytes / ${moduleSize.toDisplayMB()} MB) exceeds maxBundleSize ($maxSize bytes / ${maxSize.toDisplayMB()} MB). " +
            "Reduce artifact size or increase maxBundleSize."
)

/**
 * Groups modules into chunks using first-fit decreasing bin-packing.
 *
 * Modules are sorted by size (largest first) and placed into the first chunk
 * that has enough remaining capacity. This minimizes the number of chunks.
 */
public object BundleChunker {

    public fun chunk(modules: List<ModuleSize>, maxChunkSize: Long): List<Chunk> {
        if (modules.isEmpty()) return emptyList()

        // Validate no single module exceeds the limit
        modules.forEach { module ->
            if (module.sizeBytes > maxChunkSize) {
                throw BundleSizeExceededException(module.name, module.sizeBytes, maxChunkSize)
            }
        }

        // First-fit decreasing: sort by size descending
        val sorted = modules.sortedByDescending { it.sizeBytes }

        val chunks = mutableListOf<MutableChunk>()

        for (module in sorted) {
            val target = chunks.firstOrNull { it.remainingCapacity(maxChunkSize) >= module.sizeBytes }
            if (target != null) {
                target.add(module)
            } else {
                chunks.add(MutableChunk().apply { add(module) })
            }
        }

        return chunks.map { Chunk(moduleNames = it.moduleNames, totalSize = it.totalSize) }
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
