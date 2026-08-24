package com.example.backlogium.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamAppIdInputTest {
    @Test fun acceptsNumericId() = assertEquals(620L, SteamAppIdInput.parse(" 620 "))
    @Test fun acceptsStoreUrl() = assertEquals(620L, SteamAppIdInput.parse("https://store.steampowered.com/app/620/Portal_2/"))
    @Test fun acceptsUrlWithoutScheme() = assertEquals(730L, SteamAppIdInput.parse("store.steampowered.com/app/730/"))

    @Test fun rejectsInvalidInput() {
        assertNull(SteamAppIdInput.parse("https://steamcommunity.com/app/620"))
        assertNull(SteamAppIdInput.parse("0"))
        assertNull(SteamAppIdInput.parse("-1"))
        assertNull(SteamAppIdInput.parse("Portal 2"))
    }
}
