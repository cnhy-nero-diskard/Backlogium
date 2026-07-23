package com.example.backlogium.data.hltb

import javax.inject.Qualifier

/** Qualifies the OkHttp client configured for HowLongToBeat (distinct from the Steam one). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HltbHttpClient
