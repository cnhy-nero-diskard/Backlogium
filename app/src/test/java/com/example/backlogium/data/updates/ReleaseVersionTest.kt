package com.example.backlogium.data.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReleaseVersionTest {
    @Test
    fun parsesValidTagAndUsesReleaseEncoding() {
        val version = ReleaseVersion.parse("v1.7.0")

        assertNotNull(version)
        assertEquals("1.7.0", version?.versionName)
        assertEquals(1_007_000L, version?.versionCode)
    }

    @Test
    fun rejectsNonReleaseTags() {
        assertNull(ReleaseVersion.parse("1.7.0"))
        assertNull(ReleaseVersion.parse("v1.7.0-beta"))
        assertNull(ReleaseVersion.parse("v1.7.0.1"))
        assertNull(ReleaseVersion.parse("vone.7.0"))
    }

    @Test
    fun rejectsComponentsThatCannotBeOrderedByTheEncoding() {
        assertNull(ReleaseVersion.parse("v1000.0.0"))
        assertNull(ReleaseVersion.parse("v1.1000.0"))
        assertNull(ReleaseVersion.parse("v1.0.1000"))
    }
}
