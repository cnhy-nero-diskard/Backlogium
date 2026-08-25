package com.example.backlogium.data.hltb

import com.example.backlogium.data.hltb.dto.HltbInitResponse
import com.example.backlogium.data.hltb.dto.HltbSearchResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client-side HowLongToBeat reader. No backend, no API key. Performs HLTB's anti-scrape
 * handshake at call time:
 *
 * 1. `GET {endpoint}/init?t=<now>` → a per-request `token` plus a dynamically-named
 *    `hpKey`/`hpVal` pair.
 * 2. `POST {endpoint}` with browser `User-Agent`/`Referer`/`Origin`, the auth headers
 *    `x-auth-token`/`x-hp-key`/`x-hp-val`, and a search body that also carries a field named
 *    `hpKey` whose value is `hpVal`.
 *
 * The endpoint is the known `/api/search/site` fast-path; if its init fails (HLTB rotated the
 * path), the current endpoint is rediscovered and validated by scanning the homepage's JS chunks
 * for the init handshake or `POST` fetch call. The resolved endpoint + token are
 * cached **in memory only** for a short window so a batch sweep reuses one handshake, and are
 * re-resolved when that window lapses or a search is rejected (HTTP 403 / rotation). They are
 * never persisted.
 */
@Singleton
class ScrapingHltbDataSource @Inject constructor(
    @HltbHttpClient private val client: OkHttpClient,
    private val json: Json,
) : HltbDataSource {

    private data class Session(
        val endpoint: String,
        val token: String?,
        val hpKey: String?,
        val hpVal: String?,
        val resolvedAt: Long,
    )

    private val sessionMutex = Mutex()

    @Volatile
    private var session: Session? = null

    override suspend fun search(name: String): List<HltbCandidate> = withContext(Dispatchers.IO) {
        val terms = name.trim().split(WHITESPACE).filter { it.isNotBlank() }
        val body = try {
            postSearch(terms, resolveSession(force = false))
        } catch (failure: HltbHttpException) {
            // Only a rejected request is evidence that the cached endpoint or token rotated. The
            // retry is deliberately outside another catch so a second rejection cannot loop.
            if (failure.statusCode != 401 && failure.statusCode != 403) throw failure
            postSearch(terms, resolveSession(force = true))
        }
        try {
            HltbBundleParser.mapCandidates(
                json.decodeFromString(HltbSearchResponse.serializer(), body),
            )
        } catch (failure: SerializationException) {
            throw HltbSearchException(
                failureClass = HltbFailureClass.PARSE,
                message = "HLTB search response could not be parsed",
                cause = failure,
            )
        }
    }

    private suspend fun resolveSession(force: Boolean): Session = sessionMutex.withLock {
        val cached = session
        if (!force && cached != null &&
            System.currentTimeMillis() - cached.resolvedAt < SESSION_TTL_MS
        ) {
            return cached
        }

        // Fast path: try the known endpoint's init first (one request, no chunk scan).
        var endpoint = HltbBundleParser.FALLBACK_ENDPOINT
        var init = tryInit(endpoint)

        // Known endpoint failed → rediscover and validate the current one from the site bundle.
        if (init == null) {
            val discovered = discoverSession() ?: throw HltbSearchException(
                failureClass = HltbFailureClass.TRANSPORT,
                message = "HLTB endpoint/init resolution failed",
            )
            endpoint = discovered.endpoint
            init = discovered.init
        }

        Session(
            endpoint = endpoint,
            token = init.token,
            hpKey = init.hpKey,
            hpVal = init.hpVal,
            resolvedAt = System.currentTimeMillis(),
        ).also { session = it }
    }

    /**
     * GET the endpoint's `/init` handshake. Transport/HTTP failures return null so endpoint
     * discovery can try the rotated path; a successful but unusable body is a parse failure and
     * must reach the repository unchanged.
     */
    private fun tryInit(endpoint: String): HltbInitResponse? = try {
        val stamp = System.currentTimeMillis()
        val body = httpGet("$BASE_URL$endpoint/init?t=$stamp")
        val response = json.decodeFromString(HltbInitResponse.serializer(), body)
        if (response.token.isNullOrEmpty()) {
            throw HltbSearchException(
                failureClass = HltbFailureClass.PARSE,
                message = "HLTB init response did not contain a token",
            )
        }
        response
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: HltbSearchException) {
        throw failure
    } catch (failure: SerializationException) {
        throw HltbSearchException(
            failureClass = HltbFailureClass.PARSE,
            message = "HLTB init response could not be parsed",
            cause = failure,
        )
    } catch (failure: HltbEmptyBodyException) {
        throw HltbSearchException(
            failureClass = HltbFailureClass.PARSE,
            message = "HLTB init response was empty",
            cause = failure,
        )
    } catch (_: Exception) {
        null
    }

    private data class DiscoveredSession(
        val endpoint: String,
        val init: HltbInitResponse,
    )

    /** Scan the homepage's JS chunks and accept only an endpoint with a working init handshake. */
    private fun discoverSession(): DiscoveredSession? = try {
        val chunks = HltbBundleParser.extractChunkPaths(httpGet("$BASE_URL/"))
        val attempted = mutableSetOf(HltbBundleParser.FALLBACK_ENDPOINT)
        for (path in chunks) {
            val js = try {
                httpGet("$BASE_URL$path")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                continue
            }
            val endpoint = HltbBundleParser.extractSearchEndpoint(js) ?: continue
            if (!attempted.add(endpoint)) continue
            tryInit(endpoint)?.let { return DiscoveredSession(endpoint, it) }
        }
        null
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: HltbSearchException) {
        throw failure
    } catch (_: Exception) {
        null
    }

    private fun postSearch(terms: List<String>, session: Session): String {
        val payload = buildJsonObject {
            put("searchType", "games")
            putJsonArray("searchTerms") { terms.forEach { add(it) } }
            put("searchPage", 1)
            put("size", 20)
            putJsonObject("searchOptions") {
                putJsonObject("games") {
                    put("userId", 0)
                    put("platform", "")
                    put("sortCategory", "popular")
                    put("rangeCategory", "main")
                    putJsonObject("rangeTime") {
                        put("min", JsonNull)
                        put("max", JsonNull)
                    }
                    putJsonObject("gameplay") {
                        put("perspective", "")
                        put("flow", "")
                        put("genre", "")
                        put("difficulty", "")
                    }
                    putJsonObject("rangeYear") {
                        put("min", "")
                        put("max", "")
                    }
                    put("modifier", "")
                }
                putJsonObject("users") { put("sortCategory", "postcount") }
                putJsonObject("lists") { put("sortCategory", "follows") }
                put("filter", "")
                put("sort", 0)
                put("randomizer", 0)
            }
            put("useCache", true)
            // The init handshake's key/val must also travel as a dynamically-named body field.
            val hpKey = session.hpKey
            val hpVal = session.hpVal
            if (!hpKey.isNullOrEmpty() && hpVal != null) put(hpKey, hpVal)
        }

        val builder = Request.Builder()
            .url("$BASE_URL${session.endpoint}")
            .headers(browserHeaders())
            .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(JSON_MEDIA_TYPE))
        session.token?.let { builder.header("x-auth-token", it) }
        session.hpKey?.let { builder.header("x-hp-key", it) }
        session.hpVal?.let { builder.header("x-hp-val", it) }

        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw HltbHttpException(
                    statusCode = response.code,
                    retryAfter = response.header("Retry-After"),
                    message = "HLTB search failed: HTTP ${response.code}",
                )
            }
            return response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw HltbEmptyBodyException()
        }
    }

    private fun httpGet(url: String): String {
        val request = Request.Builder().url(url).headers(browserHeaders()).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HltbHttpException(
                    statusCode = response.code,
                    retryAfter = response.header("Retry-After"),
                    message = "HLTB GET $url failed: HTTP ${response.code}",
                )
            }
            return response.body?.string() ?: throw HltbEmptyBodyException(
                "HLTB GET $url returned no body",
            )
        }
    }

    private fun browserHeaders(): Headers = Headers.Builder()
        .add("User-Agent", USER_AGENT)
        .add("Referer", "$BASE_URL/")
        .add("Origin", BASE_URL)
        .add("Accept", "*/*")
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
