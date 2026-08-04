package com.example.backlogium.data.diagnostics

import android.util.Log
import com.example.backlogium.BuildConfig
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class RedactingTimingInterceptor @Inject constructor(private val recorder: SyncRunRecorder) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request(); val endpoint = DiagnosticRedaction.requestIdentifier(request.url); val started = System.nanoTime()
        try { return chain.proceed(request).also { record(request.method, endpoint, it.code, elapsedMs(started)) } }
        catch (error: Exception) { record(request.method, endpoint, null, elapsedMs(started)); throw error }
    }
    private fun record(method: String, endpoint: String, status: Int?, durationMs: Long) { recorder.recordRequest(endpoint, status, durationMs); if (BuildConfig.DEBUG) Log.d(TAG, "$method $endpoint ${status ?: "failed"} ${durationMs}ms") }
    private fun elapsedMs(started: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
    private companion object { const val TAG = "SteamHttp" }
}
