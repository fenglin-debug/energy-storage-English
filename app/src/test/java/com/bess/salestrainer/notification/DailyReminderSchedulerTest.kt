package com.bess.salestrainer.notification

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

class DailyReminderSchedulerTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun schedulesLaterTodayWhenReminderTimeHasNotPassed() {
        val now = ZonedDateTime.of(2026, 8, 2, 10, 15, 0, 0, zone)
        assertEquals(Duration.ofHours(9).plusMinutes(45), nextReminderDelay(now, 20, 0))
    }

    @Test
    fun schedulesTomorrowWhenReminderTimeHasPassed() {
        val now = ZonedDateTime.of(2026, 8, 2, 21, 0, 0, 0, zone)
        assertEquals(Duration.ofHours(23), nextReminderDelay(now, 20, 0))
    }
}
