package com.noqu.callrecord

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Accessibility service that records the microphone while a call is off-hook.
 *
 * Since Android 10 third-party apps cannot capture the call downlink directly, but an
 * enabled accessibility service may capture the microphone concurrently with the dialer.
 * The far side of the call is picked up clearly only when speakerphone is on.
 */
class CallRecorderService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var telephony: TelephonyManager
    private var callStateListener: Any? = null

    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var callStartedAt = 0L
    private var ringingSeen = false

    override fun onServiceConnected() {
        telephony = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        createNotificationChannel()
        listenForCalls()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Events are only used as a cheap retry hook in case READ_PHONE_STATE was granted
        // after the service connected.
        if (callStateListener == null) listenForCalls()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        stopRecording()
        stopListening()
        io.shutdown()
        super.onDestroy()
    }

    private fun listenForCalls() {
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = CallStateCallback()
                telephony.registerTelephonyCallback(mainExecutor, callback)
                callStateListener = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) =
                        onCallState(state)
                }
                @Suppress("DEPRECATION")
                telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                callStateListener = listener
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot listen for call state", e)
        }
    }

    private fun stopListening() {
        val listener = callStateListener ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && listener is TelephonyCallback) {
            telephony.unregisterTelephonyCallback(listener)
        } else if (listener is PhoneStateListener) {
            @Suppress("DEPRECATION")
            telephony.listen(listener, PhoneStateListener.LISTEN_NONE)
        }
        callStateListener = null
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private inner class CallStateCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = onCallState(state)
    }

    private fun onCallState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> ringingSeen = true
            TelephonyManager.CALL_STATE_OFFHOOK ->
                if (recorder == null && Prefs.autoRecord(this)) startRecording()
            TelephonyManager.CALL_STATE_IDLE -> {
                stopRecording()
                ringingSeen = false
            }
        }
    }

    private fun startRecording() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Log.w(TAG, "RECORD_AUDIO not granted; skipping call")
            return
        }
        goForeground()

        callStartedAt = System.currentTimeMillis()
        val file = File(Recordings.dir(this), "rec_$callStartedAt.part")
        val preferred = Prefs.audioSource(this)
        val sources = listOf(preferred, MediaRecorder.AudioSource.MIC).distinct()

        for (source in sources) {
            val r = newRecorder()
            try {
                r.setAudioSource(source)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(44_100)
                r.setAudioEncodingBitRate(96_000)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                recordingFile = file
                Log.i(TAG, "Recording with audio source $source")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Audio source $source failed", e)
                r.release()
                file.delete()
            }
        }
        leaveForeground()
    }

    private fun stopRecording() {
        val r = recorder ?: return
        recorder = null
        val file = recordingFile ?: return
        recordingFile = null
        val incoming = ringingSeen
        val startedAt = callStartedAt

        // stop() throws if no audio was captured (e.g. a call that ended instantly).
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        leaveForeground()

        if (!ok) {
            file.delete()
            return
        }
        // The dialer writes the call log entry shortly after the call ends.
        handler.postDelayed({ io.execute { finalizeRecording(file, startedAt, incoming) } }, 2_000)
    }

    private fun finalizeRecording(file: File, startedAt: Long, incomingGuess: Boolean) {
        var number: String? = null
        var incoming: Boolean? = incomingGuess
        if (hasPermission(Manifest.permission.READ_CALL_LOG)) {
            runCatching {
                contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE),
                    "${CallLog.Calls.DATE} >= ?",
                    // The call log date is when the call was placed/started ringing, so allow slack.
                    arrayOf((startedAt - 120_000).toString()),
                    "${CallLog.Calls.DATE} DESC",
                )?.use { c ->
                    if (c.moveToFirst()) {
                        number = c.getString(0)
                        incoming = when (c.getInt(1)) {
                            CallLog.Calls.OUTGOING_TYPE -> false
                            CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> true
                            else -> incomingGuess
                        }
                    }
                }
            }.onFailure { Log.w(TAG, "Call log lookup failed", it) }
        }

        val target = File(file.parentFile, Recordings.fileName(startedAt, incoming, number))
        if (!file.renameTo(target)) Log.w(TAG, "Could not rename ${file.name}")
        handler.post { notifySaved() }
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(this) else MediaRecorder()

    // A microphone foreground service keeps capture allowed while the dialer is on screen.
    // If the system refuses (background-start limits), the accessibility exemption still
    // usually lets recording proceed, so failures are only logged.
    private fun goForeground() {
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_RECORDING,
                buildNotification(getString(R.string.notif_recording), ongoing = true),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not enter foreground", e)
        }
    }

    private fun leaveForeground() {
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
    }

    private fun notifySaved() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) return
        NotificationManagerCompat.from(this).notify(
            NOTIFICATION_SAVED,
            buildNotification(getString(R.string.notif_saved), ongoing = false),
        )
    }

    private fun buildNotification(text: String, ongoing: Boolean) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "CallRecorder"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_RECORDING = 1
        private const val NOTIFICATION_SAVED = 2
    }
}
