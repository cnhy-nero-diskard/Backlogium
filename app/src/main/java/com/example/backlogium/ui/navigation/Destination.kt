package com.example.backlogium.ui.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.TablerIcons
import compose.icons.tablericons.DeviceGamepad
import compose.icons.tablericons.History
import compose.icons.tablericons.Home

/** Top-level navigation destinations shown in the bottom navigation bar. */
enum class Destination(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", TablerIcons.Home),
    LIBRARY("library", "Library", TablerIcons.DeviceGamepad),
    HISTORY("history", "History", TablerIcons.History),
}
