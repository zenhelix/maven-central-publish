package io.github.zenhelix.gradle.plugin.client.model

public sealed class HttpResponseResult<out S : Any, out E : Any>(
    public open val httpStatus: Int?,
    public open val httpHeaders: Map<String, List<String>>?
) : Outcome<S, E?> {

    override fun <R> fold(onSuccess: (S) -> R, onFailure: (E?) -> R): R = when (this) {
        is Success         -> onSuccess(data)
        is Error           -> onFailure(data)
        is UnexpectedError -> onFailure(null)
    }

    public fun <R> foldHttp(
        onSuccess: (data: S, httpStatus: Int, httpHeaders: Map<String, List<String>>) -> R,
        onError: (data: E?, cause: Exception?, httpStatus: Int, httpHeaders: Map<String, List<String>>) -> R,
        onUnexpected: (cause: Exception, httpStatus: Int?, httpHeaders: Map<String, List<String>>?) -> R
    ): R = when (this) {
        is Success         -> onSuccess(data, httpStatus, httpHeaders)
        is Error           -> onError(data, cause, httpStatus, httpHeaders)
        is UnexpectedError -> onUnexpected(cause, httpStatus, httpHeaders)
    }

    override fun getOrNull(): S? = when (this) {
        is Success -> data
        else       -> null
    }

    override fun errorOrNull(): E? = when (this) {
        is Error -> data
        else     -> null
    }

    public fun causeOrNull(): Exception? = when (this) {
        is Error           -> cause
        is UnexpectedError -> cause
        is Success         -> null
    }

    @Suppress("UNCHECKED_CAST")
    override fun <R> map(transform: (S) -> R): Outcome<R, E?> = when (this) {
        is Success         -> Success(data = transform(data) as Any, httpStatus = httpStatus, httpHeaders = httpHeaders) as Outcome<R, E?>
        is Error           -> this
        is UnexpectedError -> this
    }

    override fun <R> flatMap(transform: (S) -> Outcome<R, @UnsafeVariance E?>): Outcome<R, E?> = when (this) {
        is Success         -> transform(data)
        is Error           -> this
        is UnexpectedError -> this
    }

    public data class Success<out D : Any>(
        val data: D,
        override val httpStatus: Int = 200,
        override val httpHeaders: Map<String, List<String>> = emptyMap()
    ) : HttpResponseResult<D, Nothing>(httpStatus = httpStatus, httpHeaders = httpHeaders)

    public data class Error<out E : Any>(
        val data: E? = null,
        val cause: Exception? = null,
        override val httpStatus: Int,
        override val httpHeaders: Map<String, List<String>> = emptyMap()
    ) : HttpResponseResult<Nothing, E>(httpStatus = httpStatus, httpHeaders = httpHeaders)

    public data class UnexpectedError(
        val cause: Exception,
        override val httpStatus: Int? = null,
        override val httpHeaders: Map<String, List<String>>? = null
    ) : HttpResponseResult<Nothing, Nothing>(httpStatus = httpStatus, httpHeaders = httpHeaders)

    public fun <OS : Any> copySuccess(
        data: (S) -> OS
    ): HttpResponseResult<OS, E> = when (val current = this) {
        is Success         -> Success(data = data(current.data), httpStatus = current.httpStatus, httpHeaders = current.httpHeaders)
        is Error           -> current
        is UnexpectedError -> current
    }

    public fun <OE : Any> copyError(
        error: (E?) -> OE?
    ): HttpResponseResult<S, OE> = when (val current = this) {
        is Success         -> current
        is Error           -> Error(data = error(current.data), cause = current.cause, httpStatus = current.httpStatus, httpHeaders = current.httpHeaders)
        is UnexpectedError -> current
    }
}
