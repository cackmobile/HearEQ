package com.cackmobile.heareq

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Foreground service that keeps the equaliser attached while music plays. */
class EqService : Service() {

    data class Status(
        val running: Boolean,
        val active: Boolean,
        val hearingAid: String?,
        val globalAttached: Boolean,
        val appSessions: Int,
    )

    private val engine = EqEngine()
    private lateinit var settings: EqSettings
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        engine.update(settings.snapshot())
        refreshActive()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshActive()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshActive()
    }

    /** Music apps announce their playback sessions with these broadcasts. */
    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> engine.attach(session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> engine.detach(session)
            }
            publish()
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = EqSettings(this)
        audioManager = getSystemService(AudioManager::class.java)

        startInForeground()

        engine.update(settings.snapshot())
        engine.attachGlobal()

        settings.prefs.registerOnSharedPreferenceChangeListener(prefListener)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)

        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
        }

        refreshActive()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A music app opened our panel and passed its session along.
        intent?.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)?.takeIf { it > 0 }?.let {
            engine.attach(it)
            refreshActive()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        settings.prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        runCatching { unregisterReceiver(sessionReceiver) }
        engine.release()
        current = Status(false, false, null, false, 0)
        listener?.invoke(current)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refreshActive() {
        val s = settings.snapshot()
        val aid = HearingDevices.connected(audioManager)
        val on = s.enabled && (!s.onlyHearingAids || aid != null)
        engine.setActive(on)
        updateNotification(on, aid?.let { HearingDevices.describe(it) })
        publish(on, aid?.let { HearingDevices.describe(it) })
    }

    private fun publish(
        active: Boolean = current.active,
        aid: String? = HearingDevices.connected(audioManager)?.let { HearingDevices.describe(it) },
    ) {
        current = Status(true, active, aid, engine.globalAttached, engine.sessionCount)
        listener?.invoke(current)
    }

    // ---- Notification ----

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Equaliser", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while HearEQ is shaping your music"
            }
        )
        val n = buildNotification(false, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(active: Boolean, aid: String?) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(active, aid))
    }

    private fun buildNotification(active: Boolean, aid: String?): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when {
            active && aid != null -> "${settings.presetName} • $aid"
            active -> "${settings.presetName} • all outputs"
            else -> "Waiting for hearing aids"
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_eq)
            .setContentTitle(if (active) "Equaliser on" else "Equaliser paused")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "eq"
        private const val NOTIF_ID = 1

        @Volatile
        var current = Status(false, false, null, false, 0)
            private set

        /** The screen subscribes here to show live status. Called on the main thread. */
        var listener: ((Status) -> Unit)? = null

        fun start(context: Context, session: Int = -1) {
            val i = Intent(context, EqService::class.java)
            if (session > 0) i.putExtra(AudioEffect.EXTRA_AUDIO_SESSION, session)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EqService::class.java))
        }
    }
}
