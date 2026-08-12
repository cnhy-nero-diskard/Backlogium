package com.example.backlogium

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class MainActivityConfigurationTest {

    @Test
    fun mainActivity_usesAdjustResizeForIme() {
        val workingDirectory = File(System.getProperty("user.dir"))
        val manifest = listOf(
            workingDirectory.resolve("app/src/main/AndroidManifest.xml"),
            workingDirectory.resolve("src/main/AndroidManifest.xml"),
        ).firstOrNull(File::isFile) ?: error("Could not locate the app manifest")
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val activities = document.getElementsByTagName("activity")
        val mainActivity = (0 until activities.length)
            .map(activities::item)
            .first { activity ->
                activity.attributes
                    .getNamedItemNS(ANDROID_NAMESPACE, "name")
                    ?.nodeValue == ".MainActivity"
            }

        assertEquals(
            "adjustResize",
            mainActivity.attributes
                .getNamedItemNS(ANDROID_NAMESPACE, "windowSoftInputMode")
                ?.nodeValue,
        )
    }

    private companion object {
        const val ANDROID_NAMESPACE = "http://schemas.android.com/apk/res/android"
    }
}
