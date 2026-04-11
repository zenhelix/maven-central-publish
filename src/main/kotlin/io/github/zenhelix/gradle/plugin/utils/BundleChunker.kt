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
 * Groups modules into chunks that each fit within a size limit.
 *
 * Uses the **first-fit decreasing (FFD) bin-packing algorithm**: modules are sorted
 * by size (largest first) and each is placed into the first chunk with enough remaining
 * capacity. FFD is chosen because it produces near-optimal results (at most 11/9 * OPT + 6/9
 * bins) while being simple to implement and fast (O(n log n) sort + O(n*m) placement).
 *
 * Guarantees:
 * - Every module appears in exactly one chunk.
 * - No chunk exceeds [maxChunkSize].
 * - Throws [BundleSizeExceededException] if any single module exceeds the limit.
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
