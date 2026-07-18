package com.example.backlogium.ui.navigation

/** Top-level navigation destinations shown in the bottom navigation bar. */
enum class Destination(val route: String, val label: String, val icon: String) {
    HOME("home", "Home", "🏠"),
    LIBRARY("library", "Library", "🎮"),
    HISTORY("history", "History", "📜"),
}
