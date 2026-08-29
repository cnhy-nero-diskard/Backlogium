package com.example.backlogium.domain

/** Persisted per-list visibility for the read-only derived collections. */
data class SmartCollectionVisibility(
    val hidden: Set<SmartCollectionId> = emptySet(),
) {
    fun isVisible(id: SmartCollectionId): Boolean = id !in hidden

    fun setVisible(id: SmartCollectionId, visible: Boolean): SmartCollectionVisibility = copy(
        hidden = if (visible) hidden - id else hidden + id,
    )
}
