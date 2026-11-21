package com.example.backlogium.domain

data class CollectionPickerTarget(val id: Long, val name: String, val alreadyMember: Boolean)
data class CollectionPicker(val steamId: String, val targets: List<CollectionPickerTarget>)
data class CollectionMembershipState(val appId: Long, val orderIndex: Int, val done: Boolean)

class CollectionEditConflict : IllegalStateException("Collection membership changed. Refresh the editor and retry.")
