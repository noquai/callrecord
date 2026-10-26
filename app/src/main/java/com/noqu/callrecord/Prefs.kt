package com.noqu.callrecord

import android.content.Context
import android.media.MediaRecorder

/** Audio sources offered in the UI; order matches R.array.audio_sources. */
object AudioSources {
    val ALL = intArrayOf(
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        MediaRecorder.AudioSource.VOICE_CALL,
    )
}

object Prefs {
    private const val FILE = "settings"
    private const val KEY_AUTO_RECORD = "auto_record"
    private const val KEY_SOURCE_INDEX = "audio_source_index"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun autoRecord(context: Context) = prefs(context).getBoolean(KEY_AUTO_RECORD, true)

    fun setAutoRecord(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_AUTO_RECORD, enabled).apply()

    fun sourceIndex(context: Context) =
        prefs(context).getInt(KEY_SOURCE_INDEX, 0).coerceIn(0, AudioSources.ALL.lastIndex)

    fun setSourceIndex(context: Context, index: Int) =
        prefs(context).edit().putInt(KEY_SOURCE_INDEX, index).apply()

    fun audioSource(context: Context) = AudioSources.ALL[sourceIndex(context)]
}
