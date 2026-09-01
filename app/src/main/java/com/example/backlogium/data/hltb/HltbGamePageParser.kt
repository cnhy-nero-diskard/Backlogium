package com.example.backlogium.data.hltb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Pure parser for a direct HowLongToBeat game page (`/game/{id}`).
 *
 * Research (task 1.1): captured fixtures live in
 * `app/src/test/resources/com/example/backlogium/data/hltb/page/`:
 *  - `hltb-game-page-sample.html` — a current public game page (Portal 2) with
 *    `__NEXT_DATA__` type `application/json` containing structured `game` data under
 *    `props.pageProps.game` (and duplicate `gameInside` variants depending on Next
 *    version). The `game_image` reference is a bare filename; `comp_*` are seconds ints.
 *  - `hltb-game-page-not-found.html` — the server's 404 response (200 with
 *    `"Not Found"` body or 404 + no structured game payload).
 *
 * Least volatile source identified: the embedded `__NEXT_DATA__` JSON payload under
 * `props.pageProps`. It carries `game_id`, `game_name`, `game_image`, and all four
 * `comp_*` seconds fields in one stable object, avoiding any CSS selector or visible-prose
 * coupling. The parser targets that structured payload and never reads CSS classes.
 *
 * Fallback: when `__NEXT_DATA__` is absent (older bundle), the parser scans the raw
 * HTML for a JSON snippet containing `"game_id":` as a recovery path, ensuring rotated
 * payload failure is distinguished from a missing page.
 */
object HltbGamePageParser {

    private val NEXT_DATA_REGEX = Regex(
        """<script[^>]*id=["']__NEXT_DATA__["'][^>]*type=["']application/json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
    )

    // Loose fallback when __NEXT_DATA__ is missing: find a JSON object with game_id
    private val GAME_ID_REGEX = Regex(""""game_id"\s*:\s*(\d+)""")
    private val GAME_NAME_REGEX = Regex(""""game_name"\s*:\s*"((?:\\"|[^"])*)"""")
    private val GAME_IMAGE_REGEX = Regex(""""game_image"\s*:\s*"((?:\\"|[^"])*)"""")
    private val GAME_IMAGE_NULL_REGEX = Regex(""""game_image"\s*:\s*null""")
    private val COMP_MAIN_REGEX = Regex(""""comp_main"\s*:\s*(-?\d+)""")
    private val COMP_PLUS_REGEX = Regex(""""comp_plus"\s*:\s*(-?\d+)""")
    private val COMP_100_REGEX = Regex(""""comp_100"\s*:\s*(-?\d+)""")
    private val COMP_ALL_REGEX = Regex(""""comp_all"\s*:\s*(-?\d+)""")

    private val NOT_FOUND_HINT = Regex("(?i)(not\\s*found|404|page\\s*not\\s*found|no\\s*results)")

    private val jsonLenient = Json { ignoreUnknownKeys = true; isLenient = true }

    sealed class ParseResult {
        data class Success(val candidate: HltbCandidate) : ParseResult()
        data object NotFound : ParseResult()
        data class ParseFailure(val reason: String) : ParseResult()
    }

    fun parse(html: String, requestedId: Long): ParseResult {
        if (html.isBlank()) return ParseResult.ParseFailure("empty response")
        // Try structured __NEXT_DATA__ first
        val nextDataMatch = NEXT_DATA_REGEX.find(html)
        if (nextDataMatch != null) {
            val payload = nextDataMatch.groupValues[1].trim()
            try {
                val root = jsonLenient.parseToJsonElement(payload)
                val gameElement = findGameObject(root)
                if (gameElement != null) {
                    return extractFromJsonObject(gameElement.jsonObject, requestedId)
                }
                // No game object but page exists — check if explicit not-found inside JSON
                if (isNotFoundJson(root)) return ParseResult.NotFound
                // Fall through to fallback regex over the whole html if JSON found but no game
            } catch (_: SerializationException) {
                return ParseResult.ParseFailure("NEXT_DATA JSON invalid")
            } catch (_: Exception) {
                return ParseResult.ParseFailure("NEXT_DATA parse failure")
            }
        }

        // If HTML hints at not-found and no structured data found
        if (NOT_FOUND_HINT.containsMatchIn(html) && !GAME_ID_REGEX.containsMatchIn(html)) {
            return ParseResult.NotFound
        }

        // Fallback regex scan over entire HTML (older payloads or rotated structure)
        if (GAME_ID_REGEX.containsMatchIn(html)) {
            return extractViaRegex(html, requestedId)
        }

        // No game data and no explicit not-found hint -> parse failure (rotated payload)
        if (html.length < 500 && NOT_FOUND_HINT.containsMatchIn(html)) return ParseResult.NotFound
        return ParseResult.ParseFailure("no structured game data found")
    }

    private fun findGameObject(element: JsonElement): JsonElement? {
        // BFS over JSON tree looking for an object with game_id + game_name
        val queue = ArrayDeque<JsonElement>()
        queue.add(element)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            when (current) {
                is JsonObject -> {
                    if (current.containsKey("game_id") && current.containsKey("game_name")) {
                        return current
                    }
                    // Prioritize common containers: props.pageProps.game etc.
                    // But search all values
                    current.values.forEach { queue.add(it) }
                }
                is JsonArray -> current.forEach { queue.add(it) }
                else -> {}
            }
        }
        return null
    }

    private fun isNotFoundJson(root: JsonElement): Boolean {
        val text = root.toString()
        return text.contains("notFound", ignoreCase = true) && !text.contains("game_id")
    }

    private fun extractFromJsonObject(obj: JsonObject, requestedId: Long): ParseResult {
        val gameId = obj["game_id"]?.jsonPrimitive?.intOrNull?.toLong()
            ?: obj["game_id"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return ParseResult.ParseFailure("missing game_id")
        if (gameId <= 0) return ParseResult.ParseFailure("non-positive game_id")
        // Reject a mismatch: the transport follows redirects by default, so the fetched page can
        // describe a different entry than the requested one. The requested id must win.
        if (gameId != requestedId) {
            return ParseResult.ParseFailure("game id mismatch: requested $requestedId, page contains $gameId")
        }
        val name = obj["game_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: obj["gameName"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: return ParseResult.ParseFailure("missing game_name")
        val imageRaw = when (val el = obj["game_image"] ?: obj["gameImage"]) {
            is JsonPrimitive -> if (el.isString) el.content.takeIf { it.isNotBlank() } else null
            else -> null
        }
        val imageUrl = imageUrl(imageRaw)
        fun comp(key: String): Int? {
            val v = obj[key]?.jsonPrimitive?.intOrNull ?: return null
            return if (v <= 0) null else (v + 30) / 60
        }
        val main = comp("comp_main") ?: comp("compMain")
        val plus = comp("comp_plus") ?: comp("compPlus")
        val comp100 = comp("comp_100") ?: comp("comp100")
        val compAll = comp("comp_all") ?: comp("compAll")
        return ParseResult.Success(
            HltbCandidate(
                hltbId = gameId,
                name = name,
                mainStoryMinutes = main,
                mainExtraMinutes = plus,
                completionistMinutes = comp100,
                allStylesMinutes = compAll,
                imageUrl = imageUrl,
            ),
        )
    }

    private fun extractViaRegex(html: String, requestedId: Long): ParseResult {
        val id = GAME_ID_REGEX.find(html)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: return ParseResult.ParseFailure("game_id not found via regex")
        if (id <= 0) return ParseResult.ParseFailure("non-positive game_id regex")
        // Same redirect/mismatch guard as the structured path: a page describing another
        // entry must never preview or resolve as the requested id.
        if (id != requestedId) {
            return ParseResult.ParseFailure("game id mismatch via regex: requested $requestedId, page contains $id")
        }
        val nameRaw = GAME_NAME_REGEX.find(html)?.groupValues?.getOrNull(1) ?: return ParseResult.ParseFailure("game_name missing")
        val name = unescapeJsonString(nameRaw).trim()
        if (name.isEmpty()) return ParseResult.ParseFailure("empty game_name")
        val imageRaw = if (GAME_IMAGE_NULL_REGEX.containsMatchIn(html)) null else GAME_IMAGE_REGEX.find(html)?.groupValues?.getOrNull(1)?.let { unescapeJsonString(it) }
        val imageUrl = imageUrl(imageRaw)
        fun comp(regex: Regex): Int? {
            val v = regex.find(html)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
            return if (v <= 0) null else (v + 30) / 60
        }
        return ParseResult.Success(
            HltbCandidate(
                hltbId = id,
                name = name,
                mainStoryMinutes = comp(COMP_MAIN_REGEX),
                mainExtraMinutes = comp(COMP_PLUS_REGEX),
                completionistMinutes = comp(COMP_100_REGEX),
                allStylesMinutes = comp(COMP_ALL_REGEX),
                imageUrl = imageUrl,
            ),
        )
    }

    private fun imageUrl(reference: String?): String? = reference
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { value ->
            if (value.startsWith("http://") || value.startsWith("https://")) value
            else "https://howlongtobeat.com/games/${value.trimStart('/')}"
        }

    private fun unescapeJsonString(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\/", "/")
        .replace("\\n", "\n")
        .replace("\\t", "\t")
}

@Serializable
private data class GamePageEnvelope(
    val props: GamePageProps? = null,
)

@Serializable
private data class GamePageProps(
    val pageProps: GamePagePageProps? = null,
)

@Serializable
private data class GamePagePageProps(
    val game: GamePageGame? = null,
    @SerialName("gameData") val gameData: GamePageGame? = null,
)

@Serializable
private data class GamePageGame(
    @SerialName("game_id") val gameId: Long = 0L,
    @SerialName("game_name") val gameName: String = "",
    @SerialName("game_image") val gameImage: String? = null,
    @SerialName("comp_main") val compMain: Int = 0,
    @SerialName("comp_plus") val compPlus: Int = 0,
    @SerialName("comp_100") val comp100: Int = 0,
    @SerialName("comp_all") val compAll: Int = 0,
)
