package com.oliver.app.currentpage

import com.oliver.app.currentpage.data.ActivityRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityRecordTest {

    @Test
    fun keyCombinesPackageAndClass() {
        val record = ActivityRecord("com.example.app", "com.example.app.MainActivity", 0L)
        assertEquals("com.example.app/com.example.app.MainActivity", record.key)
    }

    @Test
    fun displayClassKeepsLastSegment() {
        val record = ActivityRecord("com.example.app", "com.example.app.ui.MainActivity", 0L)
        assertEquals("MainActivity", record.displayClass())
    }

    @Test
    fun displayClassFallsBackToRawName() {
        val record = ActivityRecord("com.example.app", "", 0L)
        assertEquals("", record.displayClass())
    }

    @Test
    fun formatTimeReturnsClockString() {
        val formatted = ActivityRecord.formatTime(0L)
        assertTrue(formatted.matches(Regex("\\d{2}:\\d{2}:\\d{2}")))
    }

    @Test
    fun displayClassTextShowsWindowClassOnSecondLine() {
        val record = ActivityRecord(
            packageName = "com.example.app",
            className = "com.example.app.MainActivity",
            timestamp = 0L,
            viewClass = "com.example.app.ui.CustomDialog",
        )
        assertEquals("com.example.app.ui.CustomDialog\n↳ com.example.app.MainActivity",
            record.displayClassText())
    }

    @Test
    fun displayClassTextDoesNotDuplicateSameClass() {
        val record = ActivityRecord(
            packageName = "com.example.app",
            className = "com.example.app.MainActivity",
            timestamp = 0L,
            viewClass = "com.example.app.MainActivity",
        )
        assertEquals("com.example.app.MainActivity", record.displayClassText())
    }
}
