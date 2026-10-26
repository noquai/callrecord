package com.noqu.callrecord

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Recording(
    val file: File,
    val timestamp: Long,
    val incoming: Boolean?,
    val number: String?,
)

/**
 * Recordings are plain .m4a files whose name carries the metadata:
 * `20261026_143005_IN_+919876543210.m4a`.
 */
object Recordings {
    private const val DIR = "Recordings"
    private val NAME = Regex("""(\d{8}_\d{6})_(IN|OUT|UNK)_([+\d]+|unknown)\.m4a""")

    private fun stampFormat() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    fun dir(context: Context): File {
        val dir = context.getExternalFilesDir(DIR) ?: File(context.filesDir, DIR)
        dir.mkdirs()
        return dir
    }

    fun fileName(timestamp: Long, incoming: Boolean?, number: String?): String {
        val direction = when (incoming) {
            true -> "IN"
            false -> "OUT"
            null -> "UNK"
        }
        val cleanNumber = number?.filter { it.isDigit() || it == '+' }?.ifEmpty { null } ?: "unknown"
        return "${stampFormat().format(Date(timestamp))}_${direction}_$cleanNumber.m4a"
    }

    fun list(context: Context): List<Recording> =
        dir(context).listFiles().orEmpty()
            .mapNotNull(::parse)
            .sortedByDescending { it.timestamp }

    private fun parse(file: File): Recording? {
        val match = NAME.matchEntire(file.name) ?: return null
        val (stamp, direction, number) = match.destructured
        val timestamp = runCatching { stampFormat().parse(stamp)?.time }.getOrNull() ?: file.lastModified()
        return Recording(
            file = file,
            timestamp = timestamp,
            incoming = when (direction) {
                "IN" -> true
                "OUT" -> false
                else -> null
            },
            number = number.takeIf { it != "unknown" },
        )
    }
}
