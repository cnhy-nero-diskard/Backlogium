package com.example.backlogium.data.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupIdentityMismatchTest {

    @Test
    fun differentIdentityWarnsButSameOrUnknownIdentityDoesNot() {
        assertTrue(isCrossAccountBackup("current-account", "backup-account"))
        assertFalse(isCrossAccountBackup("current-account", "current-account"))
        assertFalse(isCrossAccountBackup(null, "backup-account"))
        assertFalse(isCrossAccountBackup("current-account", ""))
    }
}
