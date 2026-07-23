package com.example.backlogium.data.hltb

import com.example.backlogium.data.hltb.dto.HltbInitResponse
import com.example.backlogium.data.hltb.dto.HltbSearchRequest
import com.example.backlogium.data.hltb.dto.HltbSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side HowLongToBeat reader. No backend, no API key. Performs HLTB's anti-scrape
 * dance at call time: GET the homepage → locate the `_app-*.js` bundle → extract the current
 * POST search endpoint (falling back to a hard-coded path) → GET `{endpoint}/init` for the
 * per-session token → POST the search with browser `User-Agent`/`Referer`/`Origin` and the
 * auth token.
 *
 * The resolved endpoint + token are cached **in memory only** for a short window so a batch
 * sweep reuses one handshake, and are re-resolved when that window lapses or the search is
 * rejected (endpoint/token rotated). They are never persisted.
 */
@Singleton
class ScrapingHltbDataSource @Inject constructor(
    @HltbHttpClient private val client: OkHttpClient,
    private val json: Json,
) : HltbDataSource {

    private data class Session(
        val endpoint: String,
        val token: String?,
        val key: String?,
        val value: String?,
        val resolvedAt: Long,
    )

    private val sessionMutex = Mutex()

    @Volatile
    private var session: Session? = null

    override suspend fun search(name: String): List<HltbCandidate> = withContext(Dispatchers.IO) {
        val body = runCatching { postSearch(name, resolveSession(force = false)) }
            .getOrElse {
                // Likely a rotated endpoint/token: re-resolve once and retry before giving up.
                postSearch(name, resolveSession(force = true))
            }
        HltbBundleParser.mapCandidates(json.decodeFromString(HltbSearchResponse.serializer(), body))
    }

    private suspend fun resolveSession(force: Boolean): Session = sessionMutex.withLock {
        val cached = session
        if (!force && cached != null &&
            System.currentTimeMillis() - cached.resolvedAt < SESSION_TTL_MS
        ) {
            return cached
        }

        val homepage = httpGet("$BASE_URL/")
        val endpoint = HltbBundleParser.extractAppBundlePath(homepage)
            ?.let { bundlePath -> HltbBundleParser.extractEndpoint(httpGet("$BASE_URL$bundlePath")) }
            ?: HltbBundleParser.FALLBACK_ENDPOINT

        // The token handshake is best-effort; the search still goes out if it is unavailable.
        val init = runCatching {
            json.decodeFromString(HltbInitResponse.serializer(), httpGet("$BASE_URL$endpoint/init"))
        }.getOrNull()

        Session(
            endpoint = endpoint,
            token = init?.token,
            key = init?.key,
            value = init?.value,
            resolvedAt = System.currentTimeMillis(),
        ).also { session = it }
    }

    private fun postSearch(name: String, session: Session): String {
        val terms = name.split(WHITESPACE).filter { it.isNotBlank() }
        val payload = json.encodeToString(
            HltbSearchRequest.serializer(),
            HltbSearchRequest(searchTerms = terms),
        )
        val builder = Request.Builder()
            .url("$BASE_URL${session.endpoint}")
            .headers(browserHeaders())
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
        session.token?.let { builder.header("Authorization", "Bearer $it") }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("HLTB search failed: HTTP ${response.code}")
            return response.body?.string()?.takeIf { it.isNotBlank() }
                ?: error("HLTB search returned an empty body")
        }
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url).headers(browserHeaders()).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HLTB GET $url failed: HTTP ${response.code}")
            return response.body?.string() ?: error("HLTB GET $url returned no body")
        }
    }

    private fun browserHeaders(): Headers = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$BASE_URL/")
        .add("Origin", BASE_URL)
        .add("Accept", "application/json, text/plain, */*")
        .build()

    companion object {
        const val BASE_URL = "https://howlongtobeat.com"

        // Reuse one resolved endpoint/token across a batch run; short enough to stay current.
        private const val SESSION_TTL_MS = 10 * 60 * 1000L

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val WHITESPACE = Regex("""\s+""")
    }
}
