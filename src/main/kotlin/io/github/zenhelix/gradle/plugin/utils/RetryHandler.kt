package io.github.zenhelix.gradle.plugin.utils

import java.time.Duration
import org.gradle.api.logging.Logger

/**
 * Retry handler with exponential backoff support.
 *
 * @param maxRetries Maximum number of retry attempts
 * @param baseDelay Base delay between retries (will be exponentially increased)
 * @param logger Optional logger for retry events
 */
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

    /**
     * Executes the given operation with retry logic and exponential backoff.
     *
     * @param T Result type
     * @param operation Operation to execute (receives current attempt number 1-based)
     * @param shouldRetry Predicate to determine if retry should happen based on exception
     * @param onRetry Optional callback before each retry (receives attempt number and exception)
     * @return Result of successful operation
     * @throws Exception Last exception if all retries failed
     */
    public fun <T> executeWithRetry(
        operation: (attempt: Int) -> T,
        shouldRetry: (Exception) -> Boolean = { true },
        onRetry: ((attempt: Int, exception: Exception) -> Unit)? = null
    ): T {
        var attempt = 1
        var lastException: Exception? = null

        while (attempt <= maxRetries) {
            try {
                return operation(attempt)
            } catch (e: Exception) {
                lastException = e

                if (!shouldRetry(e)) {
                    logger.debug("Exception is not retriable, failing immediately: ${e.message}")
                    throw e
                }

                if (attempt >= maxRetries) {
                    logger.warn("Operation failed after $maxRetries attempts", e)
                    throw e
                }

                onRetry?.invoke(attempt, e)

                val delayMillis = calculateBackoffDelay(attempt)
                logger.debug("Retrying after ${delayMillis}ms (attempt $attempt/$maxRetries): ${e.message}")

                Thread.sleep(delayMillis)
            }

            attempt++
        }

        throw lastException ?: Exception("Operation failed after $maxRetries attempts")
    }

    /**
     * Calculates exponential backoff delay: baseDelay * 2^(attempt-1)
     */
    private fun calculateBackoffDelay(attempt: Int): Long {
        return baseDelay.toMillis() * (1L shl (attempt - 1))
    }
}

/**
 * Creates a RetryHandler with common defaults.
 */
public fun retryHandler(
    maxRetries: Int = 3,
    baseDelay: Duration = Duration.ofSeconds(2),
    logger: Logger
): RetryHandler = RetryHandler(maxRetries, baseDelay, logger)