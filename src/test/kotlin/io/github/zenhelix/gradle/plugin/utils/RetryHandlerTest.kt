package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Success
import java.time.Duration
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RetryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `should return Success on first attempt when operation succeeds`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(10), logger = logger)

        val result = handler.executeWithRetry(
            operation = { Success("ok") }
        )

        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `should return Failure after all retries exhausted`() = runTest {
        val handler = RetryHandler(maxRetries = 2, baseDelay = Duration.ofMillis(1), logger = logger)

        val result = handler.executeWithRetry(
            operation = { Failure(RuntimeException("always fails")) },
            shouldRetry = { true }
        )

        assertThat(result.errorOrNull()).isInstanceOf(RuntimeException::class.java)
        assertThat(result.errorOrNull()!!.message).isEqualTo("always fails")
    }

    @Test
    fun `should return Failure immediately when shouldRetry returns false`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(1), logger = logger)
        var attempts = 0

        val result = handler.executeWithRetry(
            operation = {
                attempts++
                Failure(RuntimeException("not retriable"))
            },
            shouldRetry = { false }
        )

        assertThat(attempts).isEqualTo(1)
        assertThat(result.errorOrNull()!!.message).isEqualTo("not retriable")
    }

    @Test
    fun `should retry and eventually succeed`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(1), logger = logger)
        var attempts = 0

        val result = handler.executeWithRetry(
            operation = {
                attempts++
                if (attempts < 3) Failure(RuntimeException("retry me"))
                else Success("ok")
            },
            shouldRetry = { true }
        )

        assertThat(attempts).isEqualTo(3)
        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `should support cancellation during delay`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofSeconds(10), logger = logger)
        var attempt = 0

        val job = launch {
            handler.executeWithRetry(
                operation = { attemptNum ->
                    attempt = attemptNum
                    Failure(RuntimeException("always fails"))
                },
                shouldRetry = { true }
            )
        }

        advanceTimeBy(100)
        job.cancel()
        assertThat(attempt).isEqualTo(1)
    }
}
