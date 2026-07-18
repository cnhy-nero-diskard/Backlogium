package com.example.backlogium.domain

import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the current time/date/timezone. The gamification engine has no clock, so the
 * app injects "now" and "today" through this abstraction — which also lets tests drive
 * deterministic dates.
 */
interface TimeProvider {
    fun nowMillis(): Long
    fun zone(): ZoneId
    fun today(): LocalDate
}

@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun today(): LocalDate = LocalDate.now(zone())
}
