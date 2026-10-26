package com.noqu.callrecord

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private lateinit var steps: LinearLayout
    private lateinit var empty: TextView
    private val recordingsAdapter = RecordingsAdapter()

    private var player: MediaPlayer? = null
    private var playing: File? = null

    private val requiredPermissions = buildList<String> {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        steps = findViewById(R.id.setup_steps)
        empty = findViewById(R.id.empty)

        findViewById<MaterialSwitch>(R.id.auto_record).apply {
            isChecked = Prefs.autoRecord(this@MainActivity)
            setOnCheckedChangeListener { _, checked -> Prefs.setAutoRecord(this@MainActivity, checked) }
        }

        findViewById<Spinner>(R.id.audio_source).apply {
            setSelection(Prefs.sourceIndex(this@MainActivity))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) =
                    Prefs.setSourceIndex(this@MainActivity, position)

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }

        findViewById<RecyclerView>(R.id.list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = recordingsAdapter
        }
    }

    override fun onResume() {
        super.onResume()
        renderSteps()
        loadRecordings()
    }

    override fun onStop() {
        super.onStop()
        stopPlayback()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderSteps()
    }

    // --- Setup checklist ---

    private fun renderSteps() {
        steps.removeAllViews()
        val accessibilityOn = isAccessibilityEnabled()
        addStep(R.string.step_permissions, allPermissionsGranted()) {
            requestPermissions(requiredPermissions, 1)
        }
        // Android 13+ blocks accessibility for sideloaded apps until this is allowed. There is
        // no API to read the flag, so treat it as done once the service is on.
        addStep(R.string.step_restricted, accessibilityOn) { showRestrictedSettingsHelp() }
        addStep(R.string.step_accessibility, accessibilityOn) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addStep(R.string.step_battery, isIgnoringBatteryOptimizations()) { requestIgnoreBatteryOptimizations() }
    }

    private fun addStep(label: Int, done: Boolean, action: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_setup_step, steps, false)
        row.findViewById<TextView>(R.id.label).setText(label)
        row.findViewById<Button>(R.id.action).apply {
            setText(if (done) R.string.done else R.string.open)
            isEnabled = !done
            setOnClickListener { action() }
        }
        steps.addView(row)
    }

    private fun allPermissionsGranted() = requiredPermissions.all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(): Boolean {
        val me = ComponentName(this, CallRecorderService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun isIgnoringBatteryOptimizations() =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife") // Sideloaded utility; reliable background recording needs this.
    private fun requestIgnoreBatteryOptimizations() {
        startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
        )
    }

    private fun showRestrictedSettingsHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.restricted_help_title)
            .setMessage(R.string.restricted_help)
            .setPositiveButton(R.string.open) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // --- Recordings ---

    private data class Row(val recording: Recording, val contactName: String?, val durationMs: Long)

    private fun loadRecordings() {
        io.execute {
            val canReadContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
            val rows = Recordings.list(this).map { rec ->
                Row(
                    recording = rec,
                    contactName = rec.number?.takeIf { canReadContacts }?.let(::lookupContact),
                    durationMs = duration(rec.file),
                )
            }
            main.post {
                if (isDestroyed) return@post
                recordingsAdapter.submit(rows)
                empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun lookupContact(number: String): String? = runCatching {
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    private fun duration(file: File): Long = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } finally {
                release()
            }
        }
    }.getOrDefault(0L)

    private fun uriFor(file: File) = FileProvider.getUriForFile(this, "$packageName.files", file)

    @SuppressLint("NotifyDataSetChanged")
    private fun togglePlayback(file: File) {
        val wasPlaying = playing == file
        stopPlayback()
        if (wasPlaying) return
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playing = file
        } catch (e: Exception) {
            stopPlayback()
            Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show()
        }
        recordingsAdapter.notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun stopPlayback() {
        player?.release()
        player = null
        if (playing != null) {
            playing = null
            recordingsAdapter.notifyDataSetChanged()
        }
    }

    private fun share(file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uriFor(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (playing == file) stopPlayback()
                file.delete()
                loadRecordings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class RecordingsAdapter : RecyclerView.Adapter<RecordingsAdapter.Holder>() {
        private var rows: List<Row> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(newRows: List<Row>) {
            rows = newRows
            notifyDataSetChanged()
        }

        override fun getItemCount() = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_recording, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(rows[position])

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.title)
            private val subtitle: TextView = view.findViewById(R.id.subtitle)
            private val play: Button = view.findViewById(R.id.play)
            private val share: Button = view.findViewById(R.id.share)
            private val delete: Button = view.findViewById(R.id.delete)

            fun bind(row: Row) {
                val rec = row.recording
                title.text = row.contactName ?: rec.number ?: getString(R.string.unknown_number)
                val direction = when (rec.incoming) {
                    true -> getString(R.string.incoming)
                    false -> getString(R.string.outgoing)
                    null -> null
                }
                val time = DateUtils.formatDateTime(
                    this@MainActivity,
                    rec.timestamp,
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
                )
                subtitle.text = listOfNotNull(direction, time, DateUtils.formatElapsedTime(row.durationMs / 1000))
                    .joinToString(" · ")
                play.setText(if (playing == rec.file) R.string.stop else R.string.play)
                play.setOnClickListener { togglePlayback(rec.file) }
                share.setOnClickListener { share(rec.file) }
                delete.setOnClickListener { confirmDelete(rec.file) }
            }
        }
    }
}
