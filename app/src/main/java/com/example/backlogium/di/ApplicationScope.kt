package com.example.backlogium.di

import javax.inject.Qualifier

/**
 * A process-lifetime [kotlinx.coroutines.CoroutineScope], for repository-owned flows that must
 * be shared across observers rather than re-run per collector (e.g. the Steam live poll).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
