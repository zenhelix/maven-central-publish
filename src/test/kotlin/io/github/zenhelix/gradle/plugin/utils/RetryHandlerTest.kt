package io.github.zenhelix.gradle.plugin.utils

import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.gradle.api.logging.Logger
import io.mockk.mockk
import org.junit.jupiter.api.Test

class RetryHandlerTest {

    private val logger: Logger = mockk(relaxed = true)

    @Test
    fun `should restore interrupt status when sleep is interrupted`() {
        val handler = RetryHandler(maxRetries = 3, baseDelay = Duration.ofSeconds(10), logger = logger)
        var attempt = 0

        val thread = Thread {
            assertThatThrownBy {
                handler.executeWithRetry(
                    operation = { attemptNum ->
                        attempt = attemptNum
                        throw RuntimeException("always fails")
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
