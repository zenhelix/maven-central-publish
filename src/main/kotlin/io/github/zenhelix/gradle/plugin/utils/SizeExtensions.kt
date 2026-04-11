package io.github.zenhelix.gradle.plugin.utils

internal const val BYTES_PER_KB: Long = 1024L
internal const val BYTES_PER_MB: Long = 1024L * 1024L
internal const val BYTES_PER_GB: Long = 1024L * 1024L * 1024L

public val Int.megabytes: Long get() = this.toLong() * BYTES_PER_MB
public val Int.gigabytes: Long get() = this.toLong() * BYTES_PER_GB
public val Long.megabytes: Long get() = this * BYTES_PER_MB
public val Long.gigabytes: Long get() = this * BYTES_PER_GB

internal fun Long.toDisplayMB(): Long = this / BYTES_PER_MB
internal fun Long.toDisplayKB(): Long = this / BYTES_PER_KB
