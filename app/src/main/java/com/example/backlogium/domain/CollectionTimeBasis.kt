package com.example.backlogium.domain

/** HowLongToBeat completion length used for a deadline collection's time estimate. */
enum class CollectionTimeBasis {
    MAIN_STORY,
    MAIN_EXTRA,
    COMPLETIONIST,
    ALL_STYLES,
}

fun CollectionTimeBasis.label(): String = when (this) {
    CollectionTimeBasis.MAIN_STORY -> "Main Story"
    CollectionTimeBasis.MAIN_EXTRA -> "Main + Extra"
    CollectionTimeBasis.COMPLETIONIST -> "Completionist"
    CollectionTimeBasis.ALL_STYLES -> "All Styles"
}
