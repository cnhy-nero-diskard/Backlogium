package com.example.backlogium.domain

/** Committed favorite state, bound to the identity that supplied the detail/picker visit. */
data class GameFavorite(val appId: Long, val isFavorite: Boolean, val steamId: String)
