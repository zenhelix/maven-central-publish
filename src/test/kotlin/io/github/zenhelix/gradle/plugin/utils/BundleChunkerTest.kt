package io.github.zenhelix.gradle.plugin.utils

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class BundleChunkerTest {

    @Test
    fun `single module within limit produces one chunk`() {
        val modules = listOf(ModuleSize("core", 100))
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].moduleNames).containsExactly("core")
        assertThat(chunks[0].totalSize).isEqualTo(100)
    }

    @Test
    fun `all modules fit in one chunk`() {
        val modules = listOf(
            ModuleSize("core", 50),
            ModuleSize("api", 80),
            ModuleSize("utils", 30)
        )
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].moduleNames).containsExactlyInAnyOrder("core", "api", "utils")
        assertThat(chunks[0].totalSize).isEqualTo(160)
    }

    @Test
    fun `modules split into two chunks when exceeding limit`() {
        val modules = listOf(
            ModuleSize("large", 200),
            ModuleSize("medium", 150),
            ModuleSize("small", 50)
        )
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(2)
        // FFD: sorted desc -> large(200), medium(150), small(50)
        // large goes to chunk1, medium goes to chunk2 (200+150>256), small goes to chunk1 (200+50=250 <= 256)
        assertThat(chunks[0].moduleNames).containsExactlyInAnyOrder("large", "small")
        assertThat(chunks[0].totalSize).isEqualTo(250)
        assertThat(chunks[1].moduleNames).containsExactly("medium")
        assertThat(chunks[1].totalSize).isEqualTo(150)
    }

    @Test
    fun `module exactly at limit produces one chunk`() {
        val modules = listOf(ModuleSize("exact", 256))
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 256)

        assertThat(chunks).hasSize(1)
        assertThat(chunks[0].moduleNames).containsExactly("exact")
    }

    @Test
    fun `module exceeding limit throws exception`() {
        val modules = listOf(ModuleSize(":big-module", 300))

        assertThatThrownBy {
            BundleChunker.chunk(modules, maxChunkSize = 256)
        }.isInstanceOf(BundleSizeExceededException::class.java)
            .hasMessageContaining(":big-module")
            .hasMessageContaining("300")
            .hasMessageContaining("256")
    }

    @Test
    fun `empty module list produces empty chunk list`() {
        val chunks = BundleChunker.chunk(emptyList(), maxChunkSize = 256)
        assertThat(chunks).isEmpty()
    }

    @Test
    fun `many small modules packed efficiently`() {
        val modules = (1..10).map { ModuleSize("mod-$it", 30) }
        val chunks = BundleChunker.chunk(modules, maxChunkSize = 100)

        // 10 modules * 30 = 300 total. Each chunk fits 3 modules (90 <= 100).
        // FFD: all same size, so 4 chunks: 3+3+3+1
        assertThat(chunks).hasSize(4)
        chunks.forEach { chunk ->
            assertThat(chunk.totalSize).isLessThanOrEqualTo(100)
        }
    }
}
