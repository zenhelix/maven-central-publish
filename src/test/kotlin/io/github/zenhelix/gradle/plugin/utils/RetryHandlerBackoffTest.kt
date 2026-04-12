package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RetryHandlerBackoffTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `calculateBackoffDelay returns positive values for all attempts up to large maxRetries`() {
        val handler = RetryHandler(maxRetries = 100, baseDelay = Duration.ofMillis(1), logger = logger)

        for (attempt in 1..100) {
            val delay = handler.calculateBackoffDelay(attempt)
            assertThat(delay)
                .describedAs("Delay for attempt $attempt must be positive")
                .isPositive()
        }
    }

    @Test
    fun `calculateBackoffDelay caps at MAX_BACKOFF_DELAY_MILLIS for large attempt values`() {
        val handler = RetryHandler(maxRetries = 50, baseDelay = Duration.ofMillis(100), logger = logger)

        val delay = handler.calculateBackoffDelay(50)

        assertThat(delay).isEqualTo(RetryHandler.MAX_BACKOFF_DELAY_MILLIS)
    }

    @Test
    fun `calculateBackoffDelay follows exponential growth for small attempts`() {
        val handler = RetryHandler(maxRetries = 5, baseDelay = Duration.ofMillis(100), logger = logger)

        assertThat(handler.calculateBackoffDelay(1)).isEqualTo(100L)   // 100 * 2^0
        assertThat(handler.calculateBackoffDelay(2)).isEqualTo(200L)   // 100 * 2^1
        assertThat(handler.calculateBackoffDelay(3)).isEqualTo(400L)   // 100 * 2^2
        assertThat(handler.calculateBackoffDelay(4)).isEqualTo(800L)   // 100 * 2^3
    }

    @Test
    fun `executeWithRetry retries correct number of times`() = runTest {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofMillis(100), logger = logger)
        var attempts = 0

        val result = handler.executeWithRetry(
            operation = {
                attempts++
                Failure(RuntimeException("always fails"))
            },
            shouldRetry = { true }
        )

        assertThat(result.errorOrNull()).isNotNull()
        assertThat(attempts).isEqualTo(3)
    }
}
