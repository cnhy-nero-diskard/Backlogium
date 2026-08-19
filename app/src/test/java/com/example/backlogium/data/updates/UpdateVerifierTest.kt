package com.example.backlogium.data.updates

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVerifierTest {
    private val verifier = UpdateVerifier(
        object : InstalledPackageInfoProvider {
            override fun installed(): InstalledPackageInfo = error("not used")
            override fun archiveSignerDigests(apk: File): Set<String>? = null
        },
    )

    @Test
    fun acceptsKnownGoodChecksumWithSha256FileFormat() = runTest {
        val file = temporaryFile("verified update")
        try {
            val checksum = sha256(file)

            assertTrue(verifier.hasMatchingDigest(file, "$checksum  app-release.apk\n"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun rejectsCorruptedChecksum() = runTest {
        val file = temporaryFile("verified update")
        try {
            assertFalse(verifier.hasMatchingDigest(file, "0".repeat(64)))
        } finally {
            file.delete()
        }
    }

    private fun temporaryFile(contents: String): File = File.createTempFile("backlogium-update", ".apk")
        .also { it.writeText(contents) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
