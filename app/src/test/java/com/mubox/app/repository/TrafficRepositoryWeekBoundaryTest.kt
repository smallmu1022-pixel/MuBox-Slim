package com.mubox.app.repository

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficRepositoryWeekBoundaryTest {
    @Test
    fun weekStartsAtLocalMidnightAndIncludesTheWholeFirstDay() {
        val timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val nowMillis = Calendar.getInstance(timeZone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 25, 15, 45, 30)
        }.timeInMillis
        val expectedWeekStart = Calendar.getInstance(timeZone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 23, 0, 0, 0)
        }.timeInMillis

        val actualWeekStart = trafficWeekStartMillis(
            nowMillis = nowMillis,
            timeZone = timeZone,
            firstDayOfWeek = Calendar.MONDAY
        )

        assertEquals(expectedWeekStart, actualWeekStart)
        assertTrue(isTrafficDateOnOrAfterWeekStart("2026-11-23", actualWeekStart, timeZone))
        assertTrue(isTrafficDateOnOrAfterWeekStart("2026-11-25", actualWeekStart, timeZone))
        assertFalse(isTrafficDateOnOrAfterWeekStart("2026-11-22", actualWeekStart, timeZone))
        assertFalse(isTrafficDateOnOrAfterWeekStart("2026-11-31", actualWeekStart, timeZone))
    }

    @Test
    fun mondayWeekStartOnSundayUsesThePreviousMonday() {
        val timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        val nowMillis = Calendar.getInstance(timeZone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 22, 0, 30, 0)
        }.timeInMillis
        val expectedWeekStart = Calendar.getInstance(timeZone).apply {
            clear()
            set(2026, Calendar.NOVEMBER, 16, 0, 0, 0)
        }.timeInMillis

        val actualWeekStart = trafficWeekStartMillis(
            nowMillis = nowMillis,
            timeZone = timeZone,
            firstDayOfWeek = Calendar.MONDAY
        )

        assertEquals(expectedWeekStart, actualWeekStart)
        assertFalse(isTrafficDateOnOrAfterWeekStart("2026-11-15", actualWeekStart, timeZone))
        assertTrue(isTrafficDateOnOrAfterWeekStart("2026-11-16", actualWeekStart, timeZone))
    }
}
