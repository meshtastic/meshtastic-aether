package org.meshtastic.aether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.meshtastic.aether.log.Log

/** The process's session host: the session outlives the activity, which shows it while it is open. */
internal object Playback {
    val sessions = SessionHost(CoroutineScope(SupervisorJob() + Dispatchers.Main))
}

/**
 * Keeps the process in the foreground while a session runs, so the link and the audio carry on behind another app,
 * with the screen off and after the activity is gone, and offers the session to the system media controls. It starts
 * when a session starts and stops when the session ends.
 */
class PlaybackService : Service() {
    private val sessions get() = Playback.sessions
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var session: MediaSession
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var resumeOnGain = false

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause(resume = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener(::onFocusChange)
            .build()
        session = MediaSession(this, "Aether").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = play()
                    override fun onPause() = pause(resume = false)
                    override fun onStop() = sessions.stop()
                    override fun onCustomAction(action: String, extras: Bundle?) {
                        if (action == ACTION_STOP) sessions.stop()
                    }
                },
            )
            setSessionActivity(openApp())
            isActive = true
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.playback_channel), NotificationManager.IMPORTANCE_LOW),
        )
        registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        audio.requestAudioFocus(focus)
        scope.launch {
            snapshotFlow { sessions.isRunning to sessions.paused }.collect { (running, paused) ->
                if (!running) {
                    stopSelf()
                } else {
                    // An unpause from the app itself takes focus back too; asking again while holding it is a no-op.
                    if (!paused) audio.requestAudioFocus(focus)
                    publish(paused)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause(resume = false)
            ACTION_STOP -> sessions.stop()
        }
        val notification = notification(sessions.paused)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        unregisterReceiver(noisy)
        audio.abandonAudioFocusRequest(focus)
        session.isActive = false
        session.release()
        Log.i(TAG, "stopped")
        super.onDestroy()
    }

    private fun play() {
        resumeOnGain = false
        if (audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) sessions.pause(false)
    }

    private fun pause(resume: Boolean) {
        resumeOnGain = resume
        sessions.pause(true)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> pause(resume = false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (!sessions.paused) pause(resume = true)
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnGain) play()
        }
    }

    private fun publish(paused: Boolean) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP
        // Android 13 and later draw the controls from this state and offer no stop of their own, so stop is a custom action.
        val stop = PlaybackState.CustomAction.Builder(ACTION_STOP, getString(R.string.playback_stop), STOP_ICON).build()
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .addCustomAction(stop)
                .setState(if (paused) PlaybackState.STATE_PAUSED else PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(paused))
    }

    private fun notification(paused: Boolean): Notification {
        val toggle = if (paused) {
            action(android.R.drawable.ic_media_play, R.string.playback_play, ACTION_PLAY)
        } else {
            action(android.R.drawable.ic_media_pause, R.string.playback_pause, ACTION_PAUSE)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (paused) R.string.playback_paused else R.string.playback_playing))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(toggle)
            .addAction(action(STOP_ICON, R.string.playback_stop, ACTION_STOP))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun action(icon: Int, label: Int, action: String): Notification.Action {
        val intent = Intent(this, PlaybackService::class.java).setAction(action)
        val pending = PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), pending).build()
    }

    /** The launcher intent, so a tap brings back the running activity instead of starting a second session. */
    private fun openApp(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        const val TAG = "playback"
        const val CHANNEL = "playback"
        const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "org.meshtastic.aether.PLAY"
        const val ACTION_PAUSE = "org.meshtastic.aether.PAUSE"
        const val ACTION_STOP = "org.meshtastic.aether.STOP"
        val STOP_ICON = android.R.drawable.ic_menu_close_clear_cancel
    }
}
