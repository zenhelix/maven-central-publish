package io.github.zenhelix.client.model

public sealed class ResponseResult<out S : Any, out E : Any> {

    public data class Success<out D : Any>(val data: D) : ResponseResult<D, Nothing>()

    public data class Error<out E : Any>(val data: E? = null, val cause: Exception? = null) : ResponseResult<Nothing, E>()

    public data class UnexpectedError(val cause: Exception) : ResponseResult<Nothing, Nothing>()

}

public sealed class HttpResponseResult<out S : Any, out E : Any>(
    public open val httpStatus: Int?,
    public open val httpHeaders: Map<String, List<String>>?
) : ResponseResult<S, E>() {

    private val defaultException: (data: E?, cause: Exception?, httpStatus: Int?, httpHeaders: Map<String, List<String>>?) -> RuntimeException =
        { data, cause, httpStatus, httpHeaders ->
            IllegalStateException("Error request $data $httpStatus, $httpHeaders", cause)
        }

    public fun result(exception: (data: E?, cause: Exception?, httpStatus: Int?, httpHeaders: Map<String, List<String>>?) -> RuntimeException = defaultException): S =
        when (this) {
            is Success         -> data
            is Error           -> throw exception(data, cause, httpStatus, httpHeaders)
            is UnexpectedError -> throw IllegalStateException("Error request $httpStatus, $httpHeaders", cause)
        }

    public companion object {
        public fun <S : Any, E : Any> of(result: ResponseResult<S, E>): HttpResponseResult<S, E> = when (result) {
            is Error                          -> result
            is Success                        -> result
            is UnexpectedError                -> result

            is ResponseResult.Error           -> Error(data = result.data, cause = result.cause, httpStatus = 0)
            is ResponseResult.Success         -> Success(data = result.data, httpStatus = 0)
            is ResponseResult.UnexpectedError -> UnexpectedError(cause = result.cause)
        }

        public fun <S : Any, E : Any> of(
            result: ResponseResult<S, E>,
            httpStatus: Int, httpHeaders: Map<String, List<String>> = emptyMap()
        ): HttpResponseResult<S, E> = when (result) {
            is Error                          -> result
            is Success                        -> result
            is UnexpectedError                -> result

            is ResponseResult.Error           -> Error(data = result.data, cause = result.cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
            is ResponseResult.Success         -> Success(data = result.data, httpStatus = httpStatus, httpHeaders = httpHeaders)
            is ResponseResult.UnexpectedError -> UnexpectedError(cause = result.cause, httpStatus = httpStatus, httpHeaders = httpHeaders)
        }

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

    @Suppress("UNCHECKED_CAST")
    public fun <OS : Any, OE : Any> copy(
        data: (S) -> OS = { it as OS }, error: (E?) -> OE? = { it as OE }
    ): HttpResponseResult<OS, OE> = when (val current = this) {
        is Success         -> Success(data = data(current.data), httpStatus = current.httpStatus, httpHeaders = current.httpHeaders)
        is Error           -> Error(data = error(current.data), cause = current.cause, httpStatus = current.httpStatus, httpHeaders = current.httpHeaders)
        is UnexpectedError -> current
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpResponseResult<*, *>) return false

        if (httpStatus != other.httpStatus) return false
        if (httpHeaders != other.httpHeaders) return false

        return true
    }

    override fun hashCode(): Int {
        var result = httpStatus ?: 0
        result = 31 * result + (httpHeaders?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "HttpResponseResult(httpStatus=$httpStatus, httpHeaders=$httpHeaders)"

}
