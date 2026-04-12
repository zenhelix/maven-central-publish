package io.github.zenhelix.gradle.plugin.client.model

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class OutcomeTest {

    @Test
    fun `fold delegates to onSuccess for Success`() {
        val result: Outcome<Int, String> = Success(42)
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { -1 })
        assertThat(folded).isEqualTo(84)
    }

    @Test
    fun `fold delegates to onFailure for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        val folded = result.fold(onSuccess = { it * 2 }, onFailure = { it.length })
        assertThat(folded).isEqualTo(5)
    }

    @Test
    fun `getOrNull returns value for Success`() {
        val result: Outcome<Int, String> = Success(42)
        assertThat(result.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `getOrNull returns null for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `errorOrNull returns null for Success`() {
        val result: Outcome<Int, String> = Success(42)
        assertThat(result.errorOrNull()).isNull()
    }

    @Test
    fun `errorOrNull returns error for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        assertThat(result.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `map transforms Success value`() {
        val result: Outcome<Int, String> = Success(42)
        val mapped = result.map { it.toString() }
        assertThat(mapped.getOrNull()).isEqualTo("42")
    }

    @Test
    fun `map preserves Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        val mapped = result.map { it.toString() }
        assertThat(mapped.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `flatMap chains Success`() {
        val result: Outcome<Int, String> = Success(42)
        val chained = result.flatMap<String> { Success(it.toString()) }
        assertThat(chained.getOrNull()).isEqualTo("42")
    }

    @Test
    fun `flatMap short-circuits on Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        val chained = result.flatMap<String> { Success(it.toString()) }
        assertThat(chained.errorOrNull()).isEqualTo("error")
    }

    @Test
    fun `getOrElse returns value for Success`() {
        val result: Outcome<Int, String> = Success(42)
        assertThat(result.getOrElse { -1 }).isEqualTo(42)
    }

    @Test
    fun `getOrElse returns default for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        assertThat(result.getOrElse { -1 }).isEqualTo(-1)
    }

    @Test
    fun `getOrThrow returns value for Success`() {
        val result: Outcome<Int, String> = Success(42)
        assertThat(result.getOrThrow { RuntimeException(it) }).isEqualTo(42)
    }

    @Test
    fun `getOrThrow throws transformed error for Failure`() {
        val result: Outcome<Int, String> = Failure("boom")
        assertThatThrownBy {
            result.getOrThrow { IllegalStateException(it) }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessage("boom")
    }

    @Test
    fun `onSuccess executes action for Success and returns self`() {
        val result: Outcome<Int, String> = Success(42)
        var captured = 0
        val returned = result.onSuccess { captured = it }
        assertThat(captured).isEqualTo(42)
        assertThat(returned).isSameAs(result)
    }

    @Test
    fun `onSuccess does not execute action for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        var called = false
        result.onSuccess { called = true }
        assertThat(called).isFalse()
    }

    @Test
    fun `onFailure executes action for Failure and returns self`() {
        val result: Outcome<Int, String> = Failure("error")
        var captured = ""
        val returned = result.onFailure { captured = it }
        assertThat(captured).isEqualTo("error")
        assertThat(returned).isSameAs(result)
    }

    @Test
    fun `onFailure does not execute action for Success`() {
        val result: Outcome<Int, String> = Success(42)
        var called = false
        result.onFailure { called = true }
        assertThat(called).isFalse()
    }

    @Test
    fun `mapError transforms error for Failure`() {
        val result: Outcome<Int, String> = Failure("error")
        val mapped = result.mapError { it.length }
        assertThat(mapped.errorOrNull()).isEqualTo(5)
    }

    @Test
    fun `mapError preserves Success`() {
        val result: Outcome<Int, String> = Success(42)
        val mapped = result.mapError { it.length }
        assertThat(mapped.getOrNull()).isEqualTo(42)
    }
}
