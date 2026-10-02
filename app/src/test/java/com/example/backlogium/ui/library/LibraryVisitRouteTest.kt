package com.example.backlogium.ui.library

import org.junit.Assert.*
import org.junit.Test

class LibraryVisitRouteTest {
    @Test fun childrenBelongToTheirActualTopLevelOrigin() {
        assertTrue(isLibraryFlow(listOf("home", "library")))
        assertTrue(isLibraryFlow(listOf("home", "library", "game_detail/{appId}")))
        assertTrue(isLibraryFlow(listOf("home", "library", "hltb_review?appId={appId}")))
        assertFalse(isLibraryFlow(listOf("home", "game_detail/{appId}")))
        assertFalse(isLibraryFlow(listOf("home", "history")))
        assertFalse(isLibraryFlow(listOf("home", "settings", "game_detail/{appId}")))
        assertFalse(isLibraryFlow(emptyList()))
    }
}
