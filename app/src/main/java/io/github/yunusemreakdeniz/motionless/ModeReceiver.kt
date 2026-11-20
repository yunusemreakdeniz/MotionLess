package io.github.yunusemreakdeniz.motionless

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the Off / Auto / On buttons of the notification and switches the running service. */
class ModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SET_MODE) return
        val mode = intent.getStringExtra(MotionCueService.EXTRA_MODE)
            ?.let { runCatching { CueMode.valueOf(it) }.getOrNull() } ?: return
        MotionCueService.switchTo(context, mode)
    }

    companion object {
        const val ACTION_SET_MODE = "io.github.yunusemreakdeniz.motionless.SET_MODE"
    }
}
