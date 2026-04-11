package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.ResultLike
import io.github.zenhelix.gradle.plugin.client.model.Success
import java.time.Duration
import org.gradle.api.logging.Logger

public class RetryHandler(
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

    public fun <T> executeWithRetry(
        operation: (attempt: Int) -> ResultLike<T, Exception>,
        shouldRetry: (Exception) -> Boolean = { true },
        onRetry: ((attempt: Int, exception: Exception) -> Unit)? = null
    ): ResultLike<T, Exception> {
        var attempt = 1
        var lastError: Exception? = null

        while (attempt <= maxRetries) {
            val result = operation(attempt)

            when (result) {
                is Success -> return result
                is Failure -> {
                    lastError = result.error

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

                    try {
                        Thread.sleep(delayMillis)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
                else -> return result
            }

            attempt++
        }

        return Failure(lastError ?: Exception("Operation failed after $maxRetries attempts"))
    }

    internal fun calculateBackoffDelay(attempt: Int): Long {
        val maxShift = 30
        val shift = (attempt - 1).coerceAtMost(maxShift)
        return (baseDelay.toMillis() * (1L shl shift)).coerceAtMost(MAX_BACKOFF_DELAY_MILLIS)
    }

    internal companion object {
        internal const val MAX_BACKOFF_DELAY_MILLIS: Long = 5 * 60 * 1000L
    }
}

public fun retryHandler(
    maxRetries: Int = 3,
    baseDelay: Duration = Duration.ofSeconds(2),
    logger: Logger
): RetryHandler = RetryHandler(maxRetries, baseDelay, logger)
