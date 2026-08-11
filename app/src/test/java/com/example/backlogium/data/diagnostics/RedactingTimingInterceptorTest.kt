package com.example.backlogium.data.diagnostics

import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [SteamSyncWorker][com.example.backlogium.work.SteamSyncWorker] and
 * [ReconciliationWorker][com.example.backlogium.work.ReconciliationWorker] share one
 * [okhttp3.OkHttpClient] and can genuinely run at the same time, so this interceptor cannot rely on
 * "whichever run is currently active" — it has to read which run a request belongs to off the
 * request itself. These tests exercise exactly that: two scopes' requests interleaved through the
 * same interceptor instance must each land only in their own scope, and a request nobody tagged
 * (presence polling, HLTB, Store) must land in neither.
 */
@RunWith(RobolectricTestRunner::class)
class RedactingTimingInterceptorTest {

    private val interceptor = RedactingTimingInterceptor()

    @Test
    fun `a request is credited only to the scope it was tagged with`() {
        val scopeA = SyncRunRecorder.RunScope("a", 0L)
        val scopeB = SyncRunRecorder.RunScope("b", 0L)

        interceptor.intercept(fakeChain(tag = scopeA))
        interceptor.intercept(fakeChain(tag = scopeB))
        interceptor.intercept(fakeChain(tag = scopeA))

        assertEquals(2, scopeA.metrics.values.sumOf { it.count })
        assertEquals(1, scopeB.metrics.values.sumOf { it.count })
    }

    @Test
    fun `an untagged request is not recorded against any scope`() {
        val scope = SyncRunRecorder.RunScope("only", 0L)

        interceptor.intercept(fakeChain(tag = null))

        assertEquals(0, scope.metrics.values.sumOf { it.count })
    }

    @Test
    fun `a failed request is still credited to its scope`() {
        val scope = SyncRunRecorder.RunScope("a", 0L)

        try {
            interceptor.intercept(fakeChain(tag = scope, throws = true))
        } catch (_: java.io.IOException) {
            // Expected — the interceptor rethrows after recording.
        }

        assertEquals(1, scope.metrics.values.sumOf { it.count })
    }

    private fun fakeChain(tag: SyncRunRecorder.RunScope?, throws: Boolean = false): Interceptor.Chain {
        val builder = Request.Builder().url("https://api.steampowered.com/test")
        if (tag != null) builder.tag(SyncRunRecorder.RunScope::class.java, tag)
        val request = builder.build()

        return object : Interceptor.Chain {
            override fun request(): Request = request
            override fun proceed(request: Request): Response {
                if (throws) throw java.io.IOException("simulated transport failure")
                return Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("".toResponseBody(null))
                    .build()
            }
            override fun connection() = error("not used")
            override fun call() = error("not used")
            override fun connectTimeoutMillis() = error("not used")
            override fun withConnectTimeout(timeout: Int, unit: TimeUnit) = error("not used")
            override fun readTimeoutMillis() = error("not used")
            override fun withReadTimeout(timeout: Int, unit: TimeUnit) = error("not used")
            override fun writeTimeoutMillis() = error("not used")
            override fun withWriteTimeout(timeout: Int, unit: TimeUnit) = error("not used")
        }
    }
}
