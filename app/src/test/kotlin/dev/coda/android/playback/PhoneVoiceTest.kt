package dev.coda.android.playback

import androidx.media3.session.SessionError
import dev.coda.android.playback.PhoneVoice.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneVoiceTest {
    @Test fun onlyTheSearchActionIsHandled() {
        assertEquals(true, PhoneVoice.handles("android.media.action.MEDIA_PLAY_FROM_SEARCH"))
        assertEquals(false, PhoneVoice.handles("android.intent.action.MAIN"))
        assertEquals(false, PhoneVoice.handles(null))
    }

    @Test fun signInErrorsOpenTheAppOthersShowTheResolversMessage() {
        assertEquals(Outcome.OpenApp, PhoneVoice.outcome(SessionError.ERROR_SESSION_AUTHENTICATION_EXPIRED, "Sign in on your phone"))
        assertEquals(Outcome.Toast("Couldn't find X"), PhoneVoice.outcome(SessionError.ERROR_NOT_SUPPORTED, "Couldn't find X"))
        assertEquals(Outcome.Toast("Nothing to play offline"), PhoneVoice.outcome(SessionError.ERROR_NOT_SUPPORTED, "Nothing to play offline"))
        assertEquals(Outcome.Toast("Nothing to play right now"), PhoneVoice.outcome(SessionError.ERROR_IO, ""))
    }
}
