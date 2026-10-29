package it.federico.g7monitor.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun versionCodeMatchesTheGradleRule() {
        assertEquals(200, AppUpdater.versionCode("0.2.0"))
        assertEquals(10203, AppUpdater.versionCode("1.2.3"))
    }

    @Test
    fun laterVersionsHaveHigherCodes() {
        assertTrue(AppUpdater.versionCode("0.10.0")!! > AppUpdater.versionCode("0.9.9")!!)
        assertTrue(AppUpdater.versionCode("1.0.0")!! > AppUpdater.versionCode("0.99.99")!!)
    }

    @Test
    fun malformedVersionsAreIgnored() {
        assertNull(AppUpdater.versionCode("0.2"))
        assertNull(AppUpdater.versionCode("0.2.0-beta"))
        assertNull(AppUpdater.versionCode("latest"))
    }
}
