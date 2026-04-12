package io.github.zenhelix.gradle.plugin.client.model

public sealed interface Outcome<out T, out E> {
    public fun <R> fold(onSuccess: (T) -> R, onFailure: (E) -> R): R
    public fun getOrNull(): T?
    public fun errorOrNull(): E?
    public fun <R> map(transform: (T) -> R): Outcome<R, E>
    public fun <R> flatMap(transform: (T) -> Outcome<R, @UnsafeVariance E>): Outcome<R, E>
}

public fun <T, E> Outcome<T, E>.getOrElse(default: (E) -> T): T = fold(onSuccess = { it }, onFailure = default)

public data class Success<out T>(val value: T) : Outcome<T, Nothing> {
    override fun <R> fold(onSuccess: (T) -> R, onFailure: (Nothing) -> R): R = onSuccess(value)
    override fun getOrNull(): T = value
    override fun errorOrNull(): Nothing? = null
    override fun <R> map(transform: (T) -> R): Outcome<R, Nothing> = Success(transform(value))
    override fun <R> flatMap(transform: (T) -> Outcome<R, Nothing>): Outcome<R, Nothing> = transform(value)
}

public data class Failure<out E>(val error: E) : Outcome<Nothing, E> {
    override fun <R> fold(onSuccess: (Nothing) -> R, onFailure: (E) -> R): R = onFailure(error)
    override fun getOrNull(): Nothing? = null
    override fun errorOrNull(): E = error
    override fun <R> map(transform: (Nothing) -> R): Outcome<R, E> = this
    override fun <R> flatMap(transform: (Nothing) -> Outcome<R, @UnsafeVariance E>): Outcome<R, E> = this
}
