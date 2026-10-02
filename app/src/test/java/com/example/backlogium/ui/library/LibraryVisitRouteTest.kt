package com.example.backlogium.ui.library

import org.junit.Assert.*
import org.junit.Test

class LibraryVisitRouteTest {
    @Test fun childrenBelongToTheirActualTopLevelOrigin() {
        assertTrue(isLibraryFlow("library", true))
        assertTrue(isLibraryFlow("game_detail/{appId}", true))
        assertTrue(isLibraryFlow("hltb_review?appId={appId}", true))
        assertFalse(isLibraryFlow("game_detail/{appId}", false))
        assertFalse(isLibraryFlow("history", true))
        assertFalse(isLibraryFlow("settings", true))
        assertFalse(isLibraryFlow(null, false))
    }
}
