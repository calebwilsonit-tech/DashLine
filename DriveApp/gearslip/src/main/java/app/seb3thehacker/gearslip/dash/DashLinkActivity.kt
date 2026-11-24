package app.seb3thehacker.gearslip.dash

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Receives map links from other apps: "Open with Dashline" on a Google Maps link or a geo: URI,
 * or Share from Messages, Chrome or Google Maps. The place goes to the car screen's Map.
 */
class DashLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> intent?.dataString
        }
        if (!raw.isNullOrBlank()) {
            DashPrefs.init(this)
            DashNav.init(this)
            DashNav.openLink(raw)
            Toast.makeText(this, "Sent to Dashline's map", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
