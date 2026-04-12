package io.github.zenhelix.gradle.plugin.extension

/**
 * Controls whether Maven Central automatically publishes a deployment after successful validation
 * or waits for a manual release action in the Portal UI.
 */
public enum class PublishingMode {
    /**
     * The deployment is published automatically after passing validation.
     *
     * This is the default mode. Use it when you want a fully hands-off release pipeline.
     */
    AUTOMATIC,

    /**
     * The deployment is validated but **not** published automatically.
     *
     * After a successful validation the artifact remains in a `VALIDATED` state. You must
     * visit the [Maven Central Portal](https://central.sonatype.com) and manually trigger the
     * release. Use this mode when you want a manual gate before the artifact becomes publicly
     * available.
     */
    USER_MANAGED
}
