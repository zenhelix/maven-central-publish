package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success
import java.time.Duration
import kotlinx.coroutines.delay
import org.gradle.api.logging.Logger

internal class RetryHandler(
    private val maxRetries: Int,
    private val baseDelay: Duration,
    private val logger: Logger
) {
    init {
        require(maxRetries >= 1) { "maxRetries must be at least 1, got: $maxRetries" }
        require(!baseDelay.isNegative && !baseDelay.isZero) {
            "baseDelay must be positive, got: $baseDelay"
        }
    }

    suspend fun <T> executeWithRetry(
        operation: suspend (attempt: Int) -> Outcome<T, Exception>,
        shouldRetry: (Exception) -> Boolean = { true },
        onRetry: ((attempt: Int, exception: Exception) -> Unit)? = null
    ): Outcome<T, Exception> {
        var attempt = 1

        while (attempt <= maxRetries) {
            when (val result = operation(attempt)) {
                is Success -> return result
                is Failure -> {
                    if (result.error is kotlin.coroutines.cancellation.CancellationException) {
                        throw result.error
                    }

                    if (!shouldRetry(result.error)) {
                        logger.debug("Exception is not retriable, failing immediately: {}", result.error.message)
                        return result
                    }

                    if (attempt >= maxRetries) {
                        logger.warn("Operation failed after {} attempts", maxRetries, result.error)
                        return result
                    }

                    onRetry?.invoke(attempt, result.error)

                    val delayMillis = calculateBackoffDelay(attempt)
                    logger.debug("Retrying after {}ms (attempt {}/{}): {}", delayMillis, attempt, maxRetries, result.error.message)

                    delay(delayMillis)
                }
                else -> return result
            }

            attempt++
        }

        error("Unreachable: loop always returns on last attempt")
    }

    internal fun calculateBackoffDelay(attempt: Int): Long {
        val maxShift = 30
        val shift = (attempt - 1).coerceAtMost(maxShift)
        return (baseDelay.toMillis() * (1L shl shift)).coerceAtMost(MAX_BACKOFF_DELAY_MILLIS)
    }

    internal companion object {
        internal const val MAX_BACKOFF_DELAY_MILLIS = 5 * 60 * 1000L
    }
}
