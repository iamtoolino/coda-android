package dev.coda.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * App info → Storage → "Manage space" (android:manageSpaceActivity, app/src/batteryLogOff/AndroidManifest.xml)
 * opens Coda's Music storage. Only releases without -pre have it, so debug and -pre builds keep "Clear storage".
 * Hands over to the app's own task (an existing MainActivity gets onNewIntent) and closes.
 */
class ManageSpaceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_MUSIC_STORAGE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
