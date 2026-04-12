package io.github.zenhelix.gradle.plugin.utils

import io.github.zenhelix.gradle.plugin.client.model.Failure
import io.github.zenhelix.gradle.plugin.client.model.HttpResponseResult
import io.github.zenhelix.gradle.plugin.client.model.Outcome
import io.github.zenhelix.gradle.plugin.client.model.Success
import io.mockk.every
import io.mockk.mockk
import java.net.http.HttpResponse
import org.assertj.core.api.Assertions.assertThat

inline fun <reified T> assertSuccess(outcome: Outcome<*, *>): T {
    assertThat(outcome).isInstanceOf(Success::class.java)
    val value = (outcome as Success).value
    assertThat(value).isInstanceOf(T::class.java)
    @Suppress("UNCHECKED_CAST")
    return value as T
}

inline fun <reified T> assertHttpSuccess(result: HttpResponseResult<*, *>): T {
    assertThat(result).isInstanceOf(HttpResponseResult.Success::class.java)
    val data = (result as HttpResponseResult.Success).data
    assertThat(data).isInstanceOf(T::class.java)
    @Suppress("UNCHECKED_CAST")
    return data as T
}

inline fun <reified T> assertFailure(outcome: Outcome<*, *>): T {
    assertThat(outcome).isInstanceOf(Failure::class.java)
    val error = (outcome as Failure).error
    assertThat(error).isInstanceOf(T::class.java)
    @Suppress("UNCHECKED_CAST")
    return error as T
}

fun mockHttpResponse(
    status: Int,
    body: String,
    headers: Map<String, List<String>> = emptyMap()
): HttpResponse<String> = mockk {
    every { statusCode() } returns status
    every { body() } returns body
    every { headers() } returns mockk { every { map() } returns headers }
}
