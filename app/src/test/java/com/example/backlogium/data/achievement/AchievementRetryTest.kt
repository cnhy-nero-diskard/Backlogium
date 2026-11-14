package com.example.backlogium.data.achievement

import okhttp3.Headers
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class AchievementRetryTest {
    @Test fun retryBackoffIsBoundedAndLongerServerDeadlineIsRetained() {
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 480_000L, 900_000L, 900_000L),
            (0..5).map(::achievementRetryDelay))
        val raw = okhttp3.Response.Builder().request(Request.Builder().url("https://example.test").build())
            .protocol(Protocol.HTTP_1_1).code(429).message("fixture")
            .headers(Headers.headersOf("Retry-After", "1800")).build()
        val error = HttpException(Response.error<Any>("fixture".toResponseBody(), raw))
        assertEquals(1_801_000L, achievementRetryAfter(error, 1_000L))
    }
}
