package io.github.zenhelix.gradle.plugin.client.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ResultLikeTest {

    @Test
    fun `fold delegates to onSuccess for Success`() {
        val result: ResultLike<Int, String> = Success(42)
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { -1 })
        assertThat(folded).isEqualTo(84)
    }

    @Test
    fun `fold delegates to onFailure for Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { it.length })
        assertThat(folded).isEqualTo(5)
    }

    @Test
    fun `getOrNull returns value for Success`() {
        val result: ResultLike<Int, String> = Success(42)
        assertThat(result.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `getOrNull returns null for Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `errorOrNull returns null for Success`() {
        val result: ResultLike<Int, String> = Success(42)
        assertThat(result.errorOrNull()).isNull()
    }

    @Test
    fun `errorOrNull returns error for Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        assertThat(result.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `map transforms Success value`() {
        val result: ResultLike<Int, String> = Success(42)
        val mapped = result.map { it.toString() }
        assertThat(mapped.getOrNull()).isEqualTo("42")
    }

    @Test
    fun `map preserves Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        val mapped = result.map { it.toString() }
        assertThat(mapped.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `flatMap chains Success`() {
        val result: ResultLike<Int, String> = Success(42)
        val chained = result.flatMap<String> { Success(it.toString()) }
        assertThat(chained.getOrNull()).isEqualTo("42")
    }

    @Test
    fun `flatMap short-circuits on Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        val chained = result.flatMap<String> { Success(it.toString()) }
        assertThat(chained.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `getOrElse returns value for Success`() {
        val result: ResultLike<Int, String> = Success(42)
        assertThat(result.getOrElse { -1 }).isEqualTo(42)
    }

    @Test
    fun `getOrElse returns default for Failure`() {
        val result: ResultLike<Int, String> = Failure("error")
        assertThat(result.getOrElse { -1 }).isEqualTo(-1)
    }
}
