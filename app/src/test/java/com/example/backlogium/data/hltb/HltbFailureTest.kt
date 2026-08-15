package com.example.backlogium.data.hltb

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class HltbFailureTest {

    @Test
    fun classifyHttpFailuresUsesStatusAndRetryHeaders_notMessageText() {
        assertEquals(
            HltbFailureClass.ROTATION_OR_EXPIRY,
            classifyHltbFailure(HltbHttpException(403, message = "HTTP 500")),
        )
        assertEquals(
            HltbFailureClass.ROTATION_OR_EXPIRY,
            classifyHltbFailure(HltbHttpException(401)),
        )
        assertEquals(
            HltbFailureClass.THROTTLED,
            classifyHltbFailure(HltbHttpException(429)),
        )
        assertEquals(
            HltbFailureClass.THROTTLED,
            classifyHltbFailure(HltbHttpException(503, retryAfter = "10")),
        )
        assertEquals(
            HltbFailureClass.SERVER,
            classifyHltbFailure(HltbHttpException(503)),
        )
        assertEquals(
            HltbFailureClass.SERVER,
            classifyHltbFailure(HltbHttpException(500)),
        )
    }

    @Test
    fun classifyTransportAndParseFailures() {
        assertEquals(
            HltbFailureClass.TRANSPORT,
            classifyHltbFailure(IOException("timed out")),
        )
        assertEquals(
            HltbFailureClass.PARSE,
            classifyHltbFailure(HltbEmptyBodyException()),
        )
        assertEquals(
            HltbFailureClass.PARSE,
            classifyHltbFailure(
                HltbSearchException(HltbFailureClass.PARSE, "invalid response"),
            ),
        )
    }
}
