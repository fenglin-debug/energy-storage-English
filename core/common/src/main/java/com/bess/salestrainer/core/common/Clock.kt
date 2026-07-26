package com.bess.salestrainer.core.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Injectable time source so tests can control now/today (used for FSRS + daily task). */
interface TimeProvider {
    fun nowInstant(): Instant
    fun today(): LocalDate
    fun zoneId(): ZoneId
}

class SystemTimeProvider(
    private val zone: ZoneId = ZoneId.systemDefault(),
) : TimeProvider {
    override fun nowInstant(): Instant = Instant.now()
    override fun today(): LocalDate = LocalDate.now(zone)
    override fun zoneId(): ZoneId = zone
}
