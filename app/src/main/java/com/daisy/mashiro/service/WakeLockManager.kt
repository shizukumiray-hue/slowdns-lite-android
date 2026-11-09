package com.daisy.mashiro.service

import android.content.Context
import android.os.PowerManager
import com.daisy.mashiro.logger.AppLogger

class WakeLockManager(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire(enabled: Boolean) {
        if (!enabled) {
            release()
            return
        }

        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SlowDnsLite:TunnelWakeLock"
            ).apply {
                setReferenceCounted(false)
            }
        }

        if (wakeLock?.isHeld == false) {
            // Timeout keamanan 30 menit per siklus
            wakeLock?.acquire(30 * 60 * 1000L)
            AppLogger.log("[Power] Mode WakeLock aktif (mencegah sleep)")
        }
    }

    fun release() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            AppLogger.log("[Power] Mode WakeLock dinonaktifkan")
        }
    }
}
