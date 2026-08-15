package com.example.backlogium.data.hltb

/** Structured classes for failures returned by the HLTB search boundary. */
enum class HltbFailureClass {
    ROTATION_OR_EXPIRY,
    THROTTLED,
    SERVER,
    TRANSPORT,
    PARSE,
}

/** A search failure whose class can be handled without parsing its message. */
class HltbSearchException(
    val failureClass: HltbFailureClass,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** HTTP status failure retained as structured data at the scraping boundary. */
class HltbHttpException(
    val statusCode: Int,
    val retryAfter: String? = null,
    message: String = "HLTB request failed with HTTP $statusCode",
) : RuntimeException(message)

/** A successful HTTP response that contains no usable search body. */
class HltbEmptyBodyException(
    message: String = "HLTB search returned an empty body",
) : RuntimeException(message)

/** Classify a typed HLTB failure or an underlying transport/parse exception. */
internal fun classifyHltbFailure(error: Throwable): HltbFailureClass = when (error) {
    is HltbSearchException -> error.failureClass
    is HltbHttpException -> when {
        error.statusCode == 401 || error.statusCode == 403 ->
            HltbFailureClass.ROTATION_OR_EXPIRY
        error.statusCode == 429 ||
            (error.statusCode == 503 && error.retryAfter != null) -> HltbFailureClass.THROTTLED
        error.statusCode in 500..599 -> HltbFailureClass.SERVER
        else -> HltbFailureClass.TRANSPORT
    }
    is HltbEmptyBodyException -> HltbFailureClass.PARSE
    is kotlinx.serialization.SerializationException -> HltbFailureClass.PARSE
    is java.io.IOException -> HltbFailureClass.TRANSPORT
    else -> HltbFailureClass.TRANSPORT
}
