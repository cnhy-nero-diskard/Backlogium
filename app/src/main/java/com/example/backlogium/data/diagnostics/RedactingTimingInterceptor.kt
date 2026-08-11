package com.example.backlogium.data.diagnostics

import android.util.Log
import com.example.backlogium.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Attributed via the request's [SyncRunRecorder.RunScope] tag (see [com.example.backlogium.data.remote.SteamApi]),
 * not an ambient "current run" — this interceptor sits on one shared [okhttp3.OkHttpClient] that
 * every caller uses, including two workers that can be mid-run at the same time.
 */
class RedactingTimingInterceptor @Inject constructor() : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val scope = request.tag(SyncRunRecorder.RunScope::class.java)
        val endpoint = DiagnosticRedaction.requestIdentifier(request.url); val started = System.nanoTime()
        try { return chain.proceed(request).also { record(scope, request.method, endpoint, it.code, elapsedMs(started)) } }
        catch (error: Exception) { record(scope, request.method, endpoint, null, elapsedMs(started)); throw error }
    }
    private fun record(scope: SyncRunRecorder.RunScope?, method: String, endpoint: String, status: Int?, durationMs: Long) {
        scope?.recordRequest(endpoint, status, durationMs)
        if (BuildConfig.DEBUG) Log.d(TAG, "$method $endpoint ${status ?: "failed"} ${durationMs}ms")
    }
    private fun elapsedMs(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    private companion object { const val TAG = "SteamHttp" }
}
