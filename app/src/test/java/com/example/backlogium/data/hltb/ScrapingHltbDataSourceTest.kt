package com.example.backlogium.data.hltb

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrapingHltbDataSourceTest {

    @Test
    fun serverFailureDoesNotReResolveSession() = runTest {
        val script = ScriptedInterceptor(
            listOf(
                { response(it, 200, "{\"token\":\"token\"}") },
                { response(it, 500, "server error") },
            ),
        )

        val failure = runCatching { source(script).search("Portal") }.exceptionOrNull()

        assertTrue(failure is HltbHttpException)
        assertEquals(2, script.callCount)
    }

    @Test
    fun transportFailureDoesNotReResolveSession() = runTest {
        val script = ScriptedInterceptor(
            listOf(
                { response(it, 200, "{\"token\":\"token\"}") },
                { throw SocketTimeoutException("timed out") },
            ),
        )

        val failure = runCatching { source(script).search("Portal") }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(2, script.callCount)
    }

    @Test
    fun rejectedSearchReResolvesOnceAndRetriesOnce() = runTest {
        val script = ScriptedInterceptor(
            listOf(
                { response(it, 200, "{\"token\":\"first\"}") },
                { response(it, 403, "rejected") },
                { response(it, 200, "{\"token\":\"second\"}") },
                { response(it, 200, "{\"data\":[]}") },
            ),
        )

        val result = source(script).search("Portal")

        assertTrue(result.isEmpty())
        assertEquals(4, script.callCount)
    }

    @Test
    fun secondRejectionDoesNotTriggerAnotherResolution() = runTest {
        val script = ScriptedInterceptor(
            listOf(
                { response(it, 200, "{\"token\":\"first\"}") },
                { response(it, 403, "rejected") },
                { response(it, 200, "{\"token\":\"second\"}") },
                { response(it, 403, "still rejected") },
            ),
        )

        val failure = runCatching { source(script).search("Portal") }.exceptionOrNull()

        assertTrue(failure is HltbHttpException)
        assertEquals(4, script.callCount)
    }

    @Test
    fun malformedInitResponseIsParseFailureWithoutRediscovery() = runTest {
        val script = ScriptedInterceptor(
            listOf { response(it, 200, "{}") },
        )

        val failure = runCatching { source(script).search("Portal") }.exceptionOrNull()

        assertTrue(failure is HltbSearchException)
        assertEquals(HltbFailureClass.PARSE, (failure as HltbSearchException).failureClass)
        assertEquals(1, script.callCount)
    }

    private fun source(script: ScriptedInterceptor) = ScrapingHltbDataSource(
        client = OkHttpClient.Builder().addInterceptor(script).build(),
        json = Json { ignoreUnknownKeys = true },
    )

    private class ScriptedInterceptor(
        private val actions: List<(Request) -> Response>,
    ) : Interceptor {
        private val calls = AtomicInteger()

        val callCount: Int get() = calls.get()

        override fun intercept(chain: Interceptor.Chain): Response {
            val index = calls.getAndIncrement()
            return actions[index](chain.request())
        }
    }

    private companion object {
        fun response(request: Request, code: Int, body: String): Response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("scripted")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }
}
