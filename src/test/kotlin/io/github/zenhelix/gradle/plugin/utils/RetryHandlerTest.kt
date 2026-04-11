package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Success
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RetryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `should return Success on first attempt when operation succeeds`() {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(10), logger = logger)

        val result = handler.executeWithRetry(
            operation = { Success("ok") }
        )

        assertThat(result.getOrNull()).isEqualTo("ok")
    }

    @Test
    fun `should return Failure after all retries exhausted`() {
        val handler = RetryHandler(maxRetries = 2, baseDelay = Duration.ofMillis(1), logger = logger)

        val result = handler.executeWithRetry(
            operation = { Failure(RuntimeException("always fails")) },
            shouldRetry = { true }
        )

        assertThat(result.errorOrNull()).isInstanceOf(RuntimeException::class.java)
        assertThat(result.errorOrNull()!!.message).isEqualTo("always fails")
    }

    @Test
    fun `should return Failure immediately when shouldRetry returns false`() {
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
    fun `should retry and eventually succeed`() {
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
    fun `should restore interrupt status when sleep is interrupted`() {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofSeconds(10), logger = logger)
        var attempt = 0

        val thread = Thread {
            assertThatThrownBy {
                handler.executeWithRetry(
                    operation = { attemptNum ->
                        attempt = attemptNum
                        Failure(RuntimeException("always fails"))
                    },
                    shouldRetry = { true }
                )
            }.isInstanceOf(InterruptedException::class.java)

            assertThat(Thread.currentThread().isInterrupted).isTrue()
        }

        thread.start()
        Thread.sleep(200)
        thread.interrupt()
        thread.join(5000)

        assertThat(attempt).isEqualTo(1)
        assertThat(thread.isAlive).isFalse()
    }
}
