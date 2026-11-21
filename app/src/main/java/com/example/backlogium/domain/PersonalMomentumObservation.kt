package com.example.backlogium.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

data class MomentumRead(val key: MomentumKey? = null, val result: PersonalMomentum? = null,
    val updating: Boolean = false)

/** Null means recomputing. Keep dates on the previous same-account snapshot; clear at account replacement. */
@OptIn(ExperimentalCoroutinesApi::class)
fun observePersonalMomentum(keys: Flow<MomentumKey>, read: (MomentumKey) -> Flow<PersonalMomentum?>): Flow<MomentumRead> {
    var previous: PersonalMomentum? = null
    return keys.distinctUntilChanged().flatMapLatest { key ->
        if (previous?.key?.accountId != key.accountId) previous = null
        fun pending() = MomentumRead(previous?.key ?: key, previous, updating = true)
        read(key).filter { it == null || it.key == key }.map { result ->
            if (result == null) pending() else {
                previous = result
                MomentumRead(key, result)
            }
        }.onStart { emit(pending()) }
    }.buffer(0)
}
