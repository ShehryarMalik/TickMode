package com.tickmode

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Android may restrict starting this app's foreground-service type from boot.
 * We therefore close an orphaned session honestly instead of pretending it resumed. */
class BootRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val session = SessionStore.current(context) ?: return
        if (session.status == "RUNNING" || session.status == "PAUSED") {
            SessionStore.finish(context, session, "INTERRUPTED")
        }
    }
}
