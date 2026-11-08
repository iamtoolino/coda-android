package dev.coda.android.cache

import dev.coda.core.cache.DownloadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the job ends, the notification stays detached only for rescheduled audio, never for covers alone. */
class DownloadNotificationsTest {
    @Test fun onlyRescheduledAudioKeepsTheNotification() {
        val audio = DownloadState(done = 2, pending = 3, waitingForWifi = true)
        val coversOnly = DownloadState(coversDue = true)
        assertTrue(DownloadNotifications.keepsNotification(audio, reschedule = true))
        assertFalse(DownloadNotifications.keepsNotification(audio, reschedule = false))
        assertFalse("covers only: removed, still rescheduled", DownloadNotifications.keepsNotification(coversOnly, reschedule = true))
        assertFalse(DownloadNotifications.keepsNotification(coversOnly.copy(coversPending = true), reschedule = true))
    }
}
