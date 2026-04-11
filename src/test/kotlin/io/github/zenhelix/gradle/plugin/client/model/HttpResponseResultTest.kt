package io.github.zenhelix.gradle.plugin.client.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HttpResponseResultTest {

    @Test
    fun `foldHttp delegates to onSuccess for Success`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Success(data = 42, httpStatus = 200)
        val folded = result.foldHttp(
            onSuccess = { data, _, _ -> "ok:$data" },
            onError = { data, _, _, _ -> "err:$data" },
            onUnexpected = { cause, _, _ -> "unexpected:${cause.message}" }
        )
        assertThat(folded).isEqualTo("ok:42")
    }

    @Test
    fun `foldHttp delegates to onError for Error`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", httpStatus = 400)
        val folded = result.foldHttp(
            onSuccess = { data, _, _ -> "ok:$data" },
            onError = { data, _, httpStatus, _ -> "err:$data:$httpStatus" },
            onUnexpected = { cause, _, _ -> "unexpected:${cause.message}" }
        )
        assertThat(folded).isEqualTo("err:bad:400")
    }

    @Test
    fun `foldHttp delegates to onUnexpected for UnexpectedError`() {
        val cause = RuntimeException("boom")
        val result: HttpResponseResult<Int, String> = HttpResponseResult.UnexpectedError(cause = cause)
        val folded = result.foldHttp(
            onSuccess = { data, _, _ -> "ok:$data" },
            onError = { data, _, _, _ -> "err:$data" },
            onUnexpected = { c, _, _ -> "unexpected:${c.message}" }
        )
        assertThat(folded).isEqualTo("unexpected:boom")
    }

    @Test
    fun `fold treats Error as failure`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", httpStatus = 400)
        val folded = result.fold(onSuccess = { "ok" }, onFailure = { "fail:$it" })
        assertThat(folded).isEqualTo("fail:bad")
    }

    @Test
    fun `fold treats UnexpectedError as failure with null error`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.UnexpectedError(cause = RuntimeException("boom"))
        val folded = result.fold(onSuccess = { "ok" }, onFailure = { "fail:$it" })
        assertThat(folded).isEqualTo("fail:null")
    }

    @Test
    fun `getOrNull returns data for Success`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Success(data = 42, httpStatus = 200)
        assertThat(result.getOrNull()).isEqualTo(42)
    }

    @Test
    fun `getOrNull returns null for Error`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", httpStatus = 400)
        assertThat(result.getOrNull()).isNull()
    }

    @Test
    fun `causeOrNull returns null for Success`() {
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Success(data = 42, httpStatus = 200)
        assertThat(result.causeOrNull()).isNull()
    }

    @Test
    fun `causeOrNull returns cause for UnexpectedError`() {
        val cause = RuntimeException("boom")
        val result: HttpResponseResult<Int, String> = HttpResponseResult.UnexpectedError(cause = cause)
        assertThat(result.causeOrNull()).isSameAs(cause)
    }

    @Test
    fun `causeOrNull returns cause for Error with cause`() {
        val cause = RuntimeException("inner")
        val result: HttpResponseResult<Int, String> = HttpResponseResult.Error(data = "bad", cause = cause, httpStatus = 500)
        assertThat(result.causeOrNull()).isSameAs(cause)
    }
}
