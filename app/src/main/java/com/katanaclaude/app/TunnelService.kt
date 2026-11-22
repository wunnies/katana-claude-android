package com.katanaclaude.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground service. It keeps the process, its network and the CPU alive while the SSH
 * session runs, so leaving the app or swiping it from recents doesn't drop the connection.
 * The PBS job and tmux keep claude alive on katana even if the phone loses the connection.
 */
class TunnelService : Service(), TunnelManager.Listener {

    companion object {
        private const val CHANNEL = "tunnel"
        private const val NOTIF_ID = 1
        private const val ACTION_STOP = "com.katanaclaude.app.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, TunnelService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) {
                TunnelManager.log("⚠ Couldn't start the background service (${e.message}). The connection may drop if you leave the app.")
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TunnelService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TunnelManager.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "SSH session", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the katana SSH session is open"
                setShowBadge(false)
            }
        )
        if (!goForeground(TunnelManager.detail.ifBlank { "Starting…" })) return
        acquireLocks()
        TunnelManager.addListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TunnelManager.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        // intent == null means Android killed and restarted us. Resume if the user still wants the session.
        if (intent == null && Prefs(this).wantConnected && !TunnelManager.isRunning()) {
            TunnelManager.log("Service was restarted by Android, so I'm reconnecting…")
            TunnelManager.start(this)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        TunnelManager.removeListener(this)
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onState(state: TunnelManager.State, detail: String) {
        if (state.active) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, build(detail))
        }
    }

    private fun goForeground(text: String): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, build(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, build(text))
        }
        true
    } catch (e: Exception) {
        TunnelManager.log("⚠ Couldn't enter the foreground (${e.message}).")
        stopSelf()
        false
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TunnelService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_terminal)
            .setContentTitle("Katana Claude · ${TunnelManager.state.label}")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Disconnect", stop).build())
            .build()
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KatanaClaude:ssh").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "KatanaClaude:ssh").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
    }
}
