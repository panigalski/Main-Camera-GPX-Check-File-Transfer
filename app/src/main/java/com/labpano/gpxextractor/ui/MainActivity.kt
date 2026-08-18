package com.labpano.gpxextractor.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.PathMigrationPolicy
import com.labpano.gpxextractor.BuildConfig
import com.labpano.gpxextractor.monitor.RecordingMonitorService
import com.labpano.gpxextractor.report.GlobalOutputReportStore
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID
import com.labpano.gpxextractor.wifi.WifiFileServerService

class MainActivity : Activity() {
    private lateinit var recordingPathView: TextView
    private lateinit var outputPathView: TextView
    private lateinit var statusView: TextView
    private lateinit var startButton: Button
    private lateinit var advancedSection: LinearLayout
    private lateinit var wifiButton: Button
    private lateinit var wifiUrlView: TextView
    private lateinit var wifiPortButton: Button
    private lateinit var advancedButton: Button
    private lateinit var preferences: SharedPreferences
    private var selectedOutputTreeUri: Uri? = null
    private var selectedRecordingPath: String = ""
    private var selectedOutputPath: String = ""
    private var advancedVisible = false
    private var defaultStatusColor: Int = Color.DKGRAY
    private var monitoringStartPending = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != RecordingMonitorService.ACTION_STATUS_CHANGED) return
            updateStatus(
                intent.getStringExtra(RecordingMonitorService.EXTRA_STATUS)
                    ?: RecordingMonitorService.STATUS_IDLE
            )
        }
    }

    private val wifiStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiFileServerService.ACTION_SERVER_STATUS) return
            val active = intent.getBooleanExtra(WifiFileServerService.EXTRA_ACTIVE, false)
            updateWifiServerUi(active)
            intent.getStringExtra(WifiFileServerService.EXTRA_ERROR)?.takeIf { it.isNotBlank() }?.let {
                Toast.makeText(this@MainActivity, "Wi-Fi server: $it", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(com.labpano.gpxextractor.R.string.app_name)
        preferences = getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (savedInstanceState == null) forceStoppedStartupState()
        selectedOutputTreeUri = preferences.getString(KEY_OUTPUT_TREE_URI, null)?.let(Uri::parse)
        val storedRecordingPath = preferences.getString(KEY_RECORDING_DIRECTORY, null)
        selectedRecordingPath = PathMigrationPolicy.recordingPath(storedRecordingPath)
        if (selectedRecordingPath != storedRecordingPath) {
            preferences.edit().putString(KEY_RECORDING_DIRECTORY, selectedRecordingPath).apply()
        }
        val storedOutputPath = preferences.getString(KEY_OUTPUT_DIRECTORY, null)
        selectedOutputPath = if (selectedOutputTreeUri == null) {
            PathMigrationPolicy.outputPath(storedOutputPath).also { migrated ->
                if (migrated != storedOutputPath) {
                    preferences.edit().putString(KEY_OUTPUT_DIRECTORY, migrated).apply()
                }
            }
        } else {
            storedOutputPath ?: AppConfig.defaultOutputDirectory.absolutePath
        }
        setContentView(buildContentView())
        requestStoragePermissionIfNeeded()
    }

    private fun forceStoppedStartupState() {
        preferences.edit()
            .putBoolean(KEY_MONITORING_ENABLED, false)
            .putBoolean(KEY_WIFI_SERVER_ENABLED, false)
            .putString(RecordingMonitorService.KEY_LAST_STATUS, RecordingMonitorService.STATUS_IDLE)
            .apply()
        runCatching { stopService(Intent(this, RecordingMonitorService::class.java)) }
        runCatching { stopService(Intent(this, WifiFileServerService::class.java)) }
    }

    override fun onStart() {
        super.onStart()
        @Suppress("DEPRECATION")
        registerReceiver(statusReceiver, IntentFilter(RecordingMonitorService.ACTION_STATUS_CHANGED))
        registerReceiver(wifiStatusReceiver, IntentFilter(WifiFileServerService.ACTION_SERVER_STATUS))
        updateStatus(
            preferences.getString(
                RecordingMonitorService.KEY_LAST_STATUS,
                RecordingMonitorService.STATUS_IDLE
            ) ?: RecordingMonitorService.STATUS_IDLE
        )
        updateWifiServerUi(preferences.getBoolean(KEY_WIFI_SERVER_ENABLED, false))
    }

    override fun onStop() {
        runCatching { unregisterReceiver(statusReceiver) }
        runCatching { unregisterReceiver(wifiStatusReceiver) }
        super.onStop()
    }

    private fun buildContentView(): View {
        val density = resources.displayMetrics.density
        val padding = (7 * density).toInt()
        val gap = (4 * density).toInt()

        statusView = TextView(this).apply {
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, gap, 0, gap)
            defaultStatusColor = currentTextColor
        }

        startButton = compactButton(
            label = "START MONITORING",
            violet = true,
            textSizeSp = 15f,
            action = { toggleMonitoring() }
        )

        recordingPathView = pathView(selectedRecordingPath)
        outputPathView = pathView(selectedOutputPath)

        val recordingRow = folderRow(
            title = "Recording",
            path = recordingPathView,
            buttonText = "BROWSE"
        ) { openDirectoryPicker(INPUT_DIRECTORY_PICKER_REQUEST) }

        val outputRow = folderRow(
            title = "Output",
            path = outputPathView,
            buttonText = "BROWSE"
        ) { openDirectoryPicker(OUTPUT_DIRECTORY_PICKER_REQUEST) }

        wifiButton = compactButton(
            label = "START WI-FI FILE ACCESS",
            violet = true,
            action = { toggleWifiServer() }
        )
        wifiUrlView = TextView(this).apply {
            // 11sp × 1.77 = 19.47sp. Rounded to 19.5sp for the requested 77% increase.
            textSize = 19.5f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(2, gap, 2, gap)
            visibility = View.GONE
        }
        val wifiSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, gap, 0, gap)
            addView(TextView(this@MainActivity).apply {
                text = "Wi-Fi file access"
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWrap())
            addView(wifiButton, mainButtonLayoutParams())
            addView(wifiUrlView, matchWrap())
        }

        advancedButton = compactButton("ADVANCED ▼", lightBlue = true) { toggleAdvanced() }
        wifiPortButton = compactButton(wifiPortButtonLabel(), lightBlue = true) { showWifiPortDialog() }
        advancedSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(wifiPortButton, mainButtonLayoutParams())
            addView(compactButton("RESET RECORDING FOLDER", lightBlue = true) {
                selectedRecordingPath = AppConfig.defaultRecordingDirectory.absolutePath
                preferences.edit()
                    .putString(KEY_RECORDING_DIRECTORY, selectedRecordingPath)
                    .apply()
                recordingPathView.text = compactPath(selectedRecordingPath)
            }, mainButtonLayoutParams())
            addView(compactButton("RESET OUTPUT FOLDER", lightBlue = true) {
                commitLocalOutputSelection(AppConfig.defaultOutputDirectory)
            }, mainButtonLayoutParams())
            addView(TextView(this@MainActivity).apply {
                text = "Wi-Fi file access uses its own TCP port (default 1100). A second camera app can use another port such as 1200 at the same time. Stop Wi-Fi file access before changing this app's port."
                textSize = 11f
                setPadding(gap, gap, gap, gap)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Completed recordings are classified into OUTPUT/dd-mm-yyyy/GOOD/, FAILED/ or ERROR/. OUTPUT also keeps cumulative GOOD.TXT, FAILED.TXT and ERROR.TXT reports, while each status folder contains its own dd-mm-yyyy_<STATUS>.txt daily report. Temporary PROCESSING folders are removed when empty."
                textSize = 11f
                setPadding(gap, gap, gap, gap)
            })
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(startButton, mainButtonLayoutParams())
            addView(statusView, matchWrap())
            addView(recordingRow, matchWrap())
            addView(outputRow, matchWrap())
            addView(wifiSection, matchWrap())
            addView(advancedButton, mainButtonLayoutParams())
            addView(advancedSection, matchWrap())
        }

        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            addView(content, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val versionView = TextView(this).apply {
            text = "Version ${BuildConfig.VERSION_NAME}"
            textSize = 17f
            setTextColor(Color.rgb(0, 100, 0))
            gravity = Gravity.CENTER
            setPadding(0, gap, 0, gap)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                scrollView,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(versionView, matchWrap())
        }
    }

    private fun folderRow(
        title: String,
        path: TextView,
        buttonText: String,
        action: () -> Unit
    ): LinearLayout {
        val density = resources.displayMetrics.density
        val gap = (3 * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, gap, 0, gap)
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 13f
                setTypeface(typeface, Typeface.BOLD)
            }, matchWrap())
            addView(path, matchWrap())
            addView(compactButton(buttonText, lightBlue = true, action = action), mainButtonLayoutParams())
        }
    }

    private fun pathView(path: String) = TextView(this).apply {
        text = compactPath(path)
        textSize = 11f
        maxLines = 1
        setPadding(2, 2, 2, 2)
    }

    private fun compactButton(
        label: String,
        lightBlue: Boolean = false,
        violet: Boolean = false,
        textSizeSp: Float = 12f,
        action: () -> Unit
    ) = Button(this).apply {
        val density = resources.displayMetrics.density
        text = label
        textSize = textSizeSp
        minHeight = 0
        minimumHeight = 0
        setTypeface(typeface, Typeface.BOLD)
        backgroundTintList = null
        setPadding(
            (12 * density).toInt(),
            (4 * density).toInt(),
            (12 * density).toInt(),
            (7 * density).toInt()
        )
        when {
            violet -> {
                setTextColor(Color.WHITE)
                background = uniformButton3dBackground(
                    density = density,
                    baseColor = Color.rgb(81, 45, 168),
                    faceTopColor = Color.rgb(149, 117, 205),
                    faceBottomColor = Color.rgb(103, 58, 183),
                    strokeColor = Color.rgb(126, 87, 194)
                )
            }
            lightBlue -> {
                setTextColor(Color.rgb(13, 71, 99))
                background = uniformButton3dBackground(
                    density = density,
                    baseColor = Color.rgb(2, 119, 189),
                    faceTopColor = Color.rgb(225, 245, 254),
                    faceBottomColor = Color.rgb(129, 212, 250),
                    strokeColor = Color.rgb(79, 195, 247)
                )
            }
            else -> {
                setTextColor(Color.rgb(13, 71, 99))
                background = uniformButton3dBackground(
                    density = density,
                    baseColor = Color.rgb(2, 119, 189),
                    faceTopColor = Color.rgb(225, 245, 254),
                    faceBottomColor = Color.rgb(129, 212, 250),
                    strokeColor = Color.rgb(79, 195, 247)
                )
            }
        }
        elevation = 2f * density
        setOnClickListener { action() }
    }

    /**
     * Shared raised shape for every main-app button. Colors may differ by action type,
     * but radius, depth, pressed behavior, height and width are deliberately identical.
     */
    private fun uniformButton3dBackground(
        density: Float,
        baseColor: Int,
        faceTopColor: Int,
        faceBottomColor: Int,
        strokeColor: Int
    ): StateListDrawable {
        fun makeState(pressed: Boolean): LayerDrawable {
            val radius = 10f * density
            val strokeWidth = maxOf(1, (1f * density).toInt())

            val base = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(baseColor)
            }

            val face = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(faceTopColor, faceBottomColor)
            ).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = radius
                setStroke(strokeWidth, strokeColor)
            }

            return LayerDrawable(arrayOf(base, face)).apply {
                val side = maxOf(1, (1f * density).toInt())
                val faceTop = if (pressed) (3f * density).toInt() else 0
                val faceBottom = if (pressed) (1f * density).toInt() else (5f * density).toInt()
                setLayerInset(1, side, faceTop, side, faceBottom)
            }
        }

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), makeState(pressed = true))
            addState(intArrayOf(), makeState(pressed = false))
        }
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )

    private fun mainButtonLayoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        (MAIN_BUTTON_HEIGHT_DP * resources.displayMetrics.density).toInt()
    ).apply {
        val verticalMargin = (2 * resources.displayMetrics.density).toInt()
        setMargins(0, verticalMargin, 0, verticalMargin)
    }

    private fun compactPath(path: String): String {
        if (path.length <= 34) return path
        val normalized = path.replace("content://", "")
        return "…" + normalized.takeLast(33)
    }

    private fun toggleAdvanced() {
        advancedVisible = !advancedVisible
        advancedSection.visibility = if (advancedVisible) View.VISIBLE else View.GONE
        advancedButton.text = if (advancedVisible) "ADVANCED ▲" else "ADVANCED ▼"
    }

    private fun updateStatus(status: String) {
        val serviceActive = RecordingMonitorService.isRunningInProcess()
        if (serviceActive || status.startsWith(RecordingMonitorService.STATUS_FAILED_PREFIX) ||
            status == RecordingMonitorService.STATUS_IDLE
        ) {
            monitoringStartPending = false
        }
        startButton.text = if (serviceActive) "STOP MONITORING" else "START MONITORING"
        startButton.isEnabled = !monitoringStartPending

        when {
            status.startsWith(RecordingMonitorService.STATUS_PROCESSING_PREFIX) -> {
                statusView.text = "● Processing: " + status.removePrefix(RecordingMonitorService.STATUS_PROCESSING_PREFIX)
                statusView.setTextColor(Color.rgb(30, 136, 229))
            }
            status.startsWith(RecordingMonitorService.STATUS_MOVED_PREFIX) -> {
                statusView.text = "✓ Moved: " + status.removePrefix(RecordingMonitorService.STATUS_MOVED_PREFIX)
                statusView.setTextColor(Color.rgb(0, 128, 0))
            }
            status.startsWith(RecordingMonitorService.STATUS_FAILED_PREFIX) -> {
                statusView.text = "! Failed: " + status.removePrefix(RecordingMonitorService.STATUS_FAILED_PREFIX)
                statusView.setTextColor(Color.rgb(198, 40, 40))
            }
            status == RecordingMonitorService.STATUS_MONITORING -> {
                statusView.text = "● Monitoring"
                statusView.setTextColor(Color.rgb(0, 128, 0))
            }
            else -> {
                statusView.text = "○ Stopped"
                statusView.setTextColor(defaultStatusColor)
            }
        }
    }

    private fun openDirectoryPicker(requestCode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Storage permission is required before selecting a folder.", Toast.LENGTH_LONG).show()
            requestStoragePermissionIfNeeded()
            return
        }

        val configuredPath = if (requestCode == INPUT_DIRECTORY_PICKER_REQUEST) {
            selectedRecordingPath
        } else {
            selectedOutputPath
        }
        val configured = File(configuredPath)
        val initial = when {
            configured.isDirectory && configured.canRead() -> configured
            configured.parentFile?.isDirectory == true -> configured.parentFile
            else -> storagePickerRoot()
        }
        showLocalDirectoryPicker(requestCode, initial)
    }

    /**
     * A small local filesystem picker for the Pilot One.
     *
     * Some Android 7 firmware builds expose an empty DocumentsUI window for
     * ACTION_OPEN_DOCUMENT_TREE. Browsing File directories directly avoids that
     * firmware problem and works for both internal shared storage and mounted SD cards.
     */
    private fun showLocalDirectoryPicker(requestCode: Int, directory: File) {
        val current = directory.takeIf { it.isDirectory && it.canRead() } ?: storagePickerRoot()
        val children = runCatching {
            current.listFiles()
                ?.asSequence()
                ?.filter { it.isDirectory && !it.isHidden && it.canRead() }
                ?.sortedBy { it.name.lowercase() }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())

        val labels = ArrayList<String>()
        labels += "✓ SELECT THIS FOLDER"
        if (current.parentFile != null) labels += "↩ PARENT FOLDER"
        if (current.absolutePath != storagePickerRoot().absolutePath) labels += "⌂ STORAGE ROOT"
        children.forEach { labels += "📁 ${it.name}" }

        AlertDialog.Builder(this)
            .setTitle(current.absolutePath)
            .setItems(labels.toTypedArray()) { _, position ->
                var index = 0
                if (position == index++) {
                    applySelectedLocalDirectory(requestCode, current)
                    return@setItems
                }
                current.parentFile?.let { parent ->
                    if (position == index++) {
                        showLocalDirectoryPicker(requestCode, parent)
                        return@setItems
                    }
                }
                if (current.absolutePath != storagePickerRoot().absolutePath) {
                    if (position == index++) {
                        showLocalDirectoryPicker(requestCode, storagePickerRoot())
                        return@setItems
                    }
                }
                val childIndex = position - index
                if (childIndex in children.indices) {
                    showLocalDirectoryPicker(requestCode, children[childIndex])
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun applySelectedLocalDirectory(requestCode: Int, directory: File) {
        if (!directory.isDirectory || !directory.canRead()) {
            Toast.makeText(this, "Cannot access this folder.", Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode == INPUT_DIRECTORY_PICKER_REQUEST) {
            selectedRecordingPath = directory.absolutePath
            recordingPathView.text = compactPath(selectedRecordingPath)
            Toast.makeText(this, "Folder selected", Toast.LENGTH_SHORT).show()
            return
        }

        if (requiresSafWritePermission(directory)) {
            // Keep this as a pending UI choice until Android grants a persisted SAF permission.
            selectedOutputTreeUri = null
            selectedOutputPath = directory.absolutePath
            outputPathView.text = compactPath(selectedOutputPath)
            AlertDialog.Builder(this)
                .setTitle("Allow SD-card writing")
                .setMessage(
                    "Android requires a one-time system permission before this app can write to " +
                        "a removable SD card. In the next window, select this same folder, " +
                        "then tap ALLOW."
                )
                .setPositiveButton("CONTINUE") { _, _ -> requestOutputTreePermission() }
                .setNegativeButton("CANCEL", null)
                .show()
            return
        }

        commitLocalOutputSelection(directory)
    }

    /**
     * A local OUTPUT selection becomes authoritative immediately. The dashboard and processing
     * engine both read these preferences live, so the companion client sees the new path on its
     * next poll and future recording transactions use it without restarting Monitoring.
     */
    private fun commitLocalOutputSelection(directory: File) {
        if (!directory.exists() && !directory.mkdirs()) {
            showFolderAccessFailure("Cannot create Output folder: ${directory.absolutePath}")
            return
        }
        val accessProblem = verifyLocalFolderAccess(directory, "Output")
        if (accessProblem != null) {
            showFolderAccessFailure(accessProblem)
            return
        }
        try {
            GlobalOutputReportStore(this).ensureReportFiles(
                GlobalOutputReportStore.Destination(directory = directory, treeUri = null)
            )
        } catch (error: Throwable) {
            showFolderAccessFailure(
                "Cannot prepare OUTPUT folder: " + (error.message ?: error.javaClass.simpleName)
            )
            return
        }

        selectedOutputTreeUri = null
        selectedOutputPath = directory.absolutePath
        preferences.edit()
            .putString(KEY_OUTPUT_DIRECTORY, selectedOutputPath)
            .remove(KEY_OUTPUT_TREE_URI)
            .apply()
        outputPathView.text = compactPath(selectedOutputPath)
        Toast.makeText(this, "Output folder updated", Toast.LENGTH_SHORT).show()
    }

    private fun requiresSafWritePermission(directory: File): Boolean {
        val path = directory.absolutePath
        val primary = Environment.getExternalStorageDirectory().absolutePath
        return path.startsWith("/storage/") && !path.startsWith(primary) && !directory.canWrite()
    }

    private fun requestOutputTreePermission() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            putExtra("android.content.extra.SHOW_ADVANCED", true)
        }
        try {
            startActivityForResult(intent, OUTPUT_TREE_PERMISSION_REQUEST)
        } catch (error: Exception) {
            Toast.makeText(this, "The system SD-card permission window is unavailable.", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Android API; retained for Android 7 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != OUTPUT_TREE_PERMISSION_REQUEST || resultCode != RESULT_OK) return

        val uri = data?.data ?: run {
            Toast.makeText(this, "No folder permission was returned.", Toast.LENGTH_LONG).show()
            return
        }
        val grantFlags = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            contentResolver.takePersistableUriPermission(uri, grantFlags)
        } catch (error: SecurityException) {
            Toast.makeText(this, "The selected folder did not grant persistent write access.", Toast.LENGTH_LONG).show()
            return
        }

        val outputProblem = verifyDocumentTreeAccess(uri)
        if (outputProblem != null) {
            showFolderAccessFailure(outputProblem)
            return
        }
        try {
            GlobalOutputReportStore(this).ensureReportFiles(
                GlobalOutputReportStore.Destination(directory = null, treeUri = uri)
            )
        } catch (error: Throwable) {
            showFolderAccessFailure(
                "Cannot prepare OUTPUT folder: " + (error.message ?: error.javaClass.simpleName)
            )
            return
        }

        selectedOutputTreeUri = uri
        DocumentTreePathResolver.resolve(this, uri)?.let { resolved ->
            selectedOutputPath = resolved.absolutePath
        }
        preferences.edit()
            .putString(KEY_OUTPUT_DIRECTORY, selectedOutputPath)
            .putString(KEY_OUTPUT_TREE_URI, uri.toString())
            .apply()
        outputPathView.text = compactPath(selectedOutputPath)
        Toast.makeText(this, "SD-card output folder updated", Toast.LENGTH_LONG).show()
    }

    private fun storagePickerRoot(): File {
        val storage = File("/storage")
        if (storage.isDirectory && storage.canRead()) return storage
        return Environment.getExternalStorageDirectory()
    }

    private fun toggleMonitoring() {
        // Manual-only policy: the live service state is authoritative. A stale preference from an
        // interrupted process must never turn a START press into an unexpected STOP press.
        if (monitoringStartPending) return
        if (RecordingMonitorService.isRunningInProcess()) stopMonitoring()
        else saveDirectoriesAndStartMonitoring()
    }

    private fun stopMonitoring() {
        preferences.edit()
            .putBoolean(KEY_MONITORING_ENABLED, false)
            .putString(RecordingMonitorService.KEY_LAST_STATUS, RecordingMonitorService.STATUS_IDLE)
            .apply()
        stopService(Intent(this, RecordingMonitorService::class.java))
        updateStatus(RecordingMonitorService.STATUS_IDLE)
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show()
    }

    private fun saveDirectoriesAndStartMonitoring() {
        val input = File(selectedRecordingPath.trim())
        if (selectedRecordingPath.isBlank() || (input.exists() && !input.isDirectory)) {
            Toast.makeText(this, "Invalid recording folder", Toast.LENGTH_LONG).show()
            return
        }
        if (!input.exists() && !input.mkdirs()) {
            Toast.makeText(this, "Cannot access recording folder", Toast.LENGTH_LONG).show()
            return
        }

        val inputProblem = verifyLocalFolderAccess(input, "Recording")
        if (inputProblem != null) {
            showFolderAccessFailure(inputProblem)
            return
        }

        val outputUri = selectedOutputTreeUri
        if (outputUri == null) {
            val output = File(selectedOutputPath.trim())
            if (requiresSafWritePermission(output)) {
                Toast.makeText(
                    this,
                    "SD-card write permission is required. Browse the output folder again and tap CONTINUE.",
                    Toast.LENGTH_LONG
                ).show()
                requestOutputTreePermission()
                return
            }
            if ((output.exists() && !output.isDirectory) || (!output.exists() && !output.mkdirs())) {
                Toast.makeText(this, "Cannot access output folder", Toast.LENGTH_LONG).show()
                return
            }
            val outputProblem = verifyLocalFolderAccess(output, "Output")
            if (outputProblem != null) {
                showFolderAccessFailure(outputProblem)
                return
            }
        } else {
            val outputProblem = verifyDocumentTreeAccess(outputUri)
            if (outputProblem != null) {
                showFolderAccessFailure(outputProblem)
                return
            }
        }

        val reportDestination = GlobalOutputReportStore.Destination(
            directory = if (outputUri == null) File(selectedOutputPath.trim()) else null,
            treeUri = outputUri
        )
        try {
            GlobalOutputReportStore(this).ensureReportFiles(reportDestination)
        } catch (error: Throwable) {
            showFolderAccessFailure(
                "Cannot prepare OUTPUT report storage: " +
                    (error.message ?: error.javaClass.simpleName)
            )
            return
        }

        preferences.edit()
            .putString(KEY_RECORDING_DIRECTORY, input.absolutePath)
            .putString(KEY_OUTPUT_DIRECTORY, selectedOutputPath)
            .putBoolean(KEY_MONITORING_ENABLED, true)
            .apply {
                if (outputUri == null) remove(KEY_OUTPUT_TREE_URI)
                else putString(KEY_OUTPUT_TREE_URI, outputUri.toString())
            }
            .apply()

        // Folder probes and report-storage validation have succeeded at this point. Keep the
        // button text as START MONITORING until RecordingMonitorService itself finishes engine +
        // FileObserver initialization and broadcasts STATUS_MONITORING.
        monitoringStartPending = true
        startButton.isEnabled = false
        statusView.text = "● Starting monitoring…"
        statusView.setTextColor(Color.rgb(30, 136, 229))
        try {
            startMonitorService()
            Toast.makeText(this, "Starting monitoring…", Toast.LENGTH_SHORT).show()
        } catch (error: Throwable) {
            monitoringStartPending = false
            startButton.isEnabled = true
            preferences.edit().putBoolean(KEY_MONITORING_ENABLED, false).apply()
            showFolderAccessFailure("Monitoring service could not start: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    /** Performs a real create/write/read/delete probe instead of trusting canWrite(). */
    private fun verifyLocalFolderAccess(directory: File, label: String): String? {
        if (!directory.isDirectory) return "$label folder is not a directory: ${directory.absolutePath}"
        if (!directory.canRead()) return "$label folder is not readable: ${directory.absolutePath}"
        if (!directory.canWrite()) return "$label folder is not writable: ${directory.absolutePath}"

        val probe = File(directory, ".gpx_access_${UUID.randomUUID()}.tmp")
        return try {
            probe.outputStream().use { it.write(byteArrayOf(0x47, 0x50, 0x58)) }
            if (!probe.isFile || probe.length() != 3L) {
                "$label folder failed the write test: ${directory.absolutePath}"
            } else {
                val bytes = probe.inputStream().use { it.readBytes() }
                when {
                    bytes.size != 3 -> "$label folder failed the read-back test: ${directory.absolutePath}"
                    !probe.delete() || probe.exists() ->
                        "$label folder failed the delete test: ${directory.absolutePath}"
                    else -> null
                }
            }
        } catch (error: Throwable) {
            "$label folder access test failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            // Best-effort cleanup if an earlier stage failed. A successful probe already verified
            // deletion above; failure is returned rather than silently treating the folder as ready.
            runCatching { if (probe.exists()) probe.delete() }
        }
    }

    /** Verifies a persisted SAF tree grant by creating, writing, reading and deleting a probe file. */
    private fun verifyDocumentTreeAccess(treeUri: Uri): String? {
        val permission = contentResolver.persistedUriPermissions.firstOrNull { it.uri == treeUri }
        if (permission == null || !permission.isReadPermission || !permission.isWritePermission) {
            return "Output folder permission is missing or is not read/write. Browse the folder again and tap ALLOW."
        }

        var probeUri: Uri? = null
        return try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocumentId)
            probeUri = DocumentsContract.createDocument(
                contentResolver,
                parentDocumentUri,
                "application/octet-stream",
                ".gpx_access_${UUID.randomUUID()}.tmp"
            ) ?: return "Output folder rejected the write test."

            contentResolver.openOutputStream(probeUri, "w")?.use {
                it.write(byteArrayOf(0x47, 0x50, 0x58))
                it.flush()
            } ?: return "Output folder could not open a test file for writing."

            val bytes = contentResolver.openInputStream(probeUri)?.use { it.readBytes() }
                ?: return "Output folder could not read back the test file."
            if (bytes.size != 3) {
                "Output folder failed the read-back test."
            } else {
                val deleteSucceeded = DocumentsContract.deleteDocument(contentResolver, probeUri)
                if (!deleteSucceeded) "Output folder failed the delete test." else {
                    probeUri = null
                    null
                }
            }
        } catch (error: Throwable) {
            "Output folder access test failed: ${error.message ?: error.javaClass.simpleName}"
        } finally {
            probeUri?.let { uri -> runCatching { DocumentsContract.deleteDocument(contentResolver, uri) } }
        }
    }

    private fun showFolderAccessFailure(message: String) {
        updateStatus(RecordingMonitorService.STATUS_FAILED_PREFIX + "folder access")
        AlertDialog.Builder(this)
            .setTitle("Folder access check failed")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun wifiPortButtonLabel(): String = "WI-FI SERVER PORT: ${currentWifiPort()}"

    private fun currentWifiPort(): Int = WifiFileServerService.configuredPort(this)

    private fun showWifiPortDialog() {
        if (preferences.getBoolean(KEY_WIFI_SERVER_ENABLED, false)) {
            Toast.makeText(this, "Stop Wi-Fi file access before changing the port.", Toast.LENGTH_LONG).show()
            return
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(currentWifiPort().toString())
            selectAll()
        }
        AlertDialog.Builder(this)
            .setTitle("Wi-Fi server port")
            .setMessage("Choose a dedicated TCP port for this app (${WifiFileServerService.MIN_PORT}-${WifiFileServerService.MAX_PORT}). Leave 1100 here if another app uses 1200.")
            .setView(input)
            .setNegativeButton("CANCEL", null)
            .setNeutralButton("RESET") { _, _ ->
                preferences.edit().putInt(KEY_WIFI_SERVER_PORT, WifiFileServerService.DEFAULT_PORT).apply()
                wifiPortButton.text = wifiPortButtonLabel()
                updateWifiServerUi(false)
            }
            .setPositiveButton("SAVE") { _, _ ->
                val port = input.text.toString().trim().toIntOrNull()
                if (port == null || port !in WifiFileServerService.MIN_PORT..WifiFileServerService.MAX_PORT) {
                    Toast.makeText(
                        this,
                        "Port must be between ${WifiFileServerService.MIN_PORT} and ${WifiFileServerService.MAX_PORT}.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    preferences.edit().putInt(KEY_WIFI_SERVER_PORT, port).apply()
                    wifiPortButton.text = wifiPortButtonLabel()
                    updateWifiServerUi(false)
                    Toast.makeText(this, "Wi-Fi server port set to $port", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun toggleWifiServer() {
        val enabled = preferences.getBoolean(KEY_WIFI_SERVER_ENABLED, false)
        if (enabled) stopWifiServer() else startWifiServer()
    }

    private fun startWifiServer() {
        preferences.edit().putBoolean(KEY_WIFI_SERVER_ENABLED, true).apply()
        val intent = Intent(this, WifiFileServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
        updateWifiServerUi(true)
        Toast.makeText(this, "Wi-Fi file access started", Toast.LENGTH_SHORT).show()
    }

    private fun stopWifiServer() {
        preferences.edit().putBoolean(KEY_WIFI_SERVER_ENABLED, false).apply()
        stopService(Intent(this, WifiFileServerService::class.java))
        updateWifiServerUi(false)
        Toast.makeText(this, "Wi-Fi file access stopped", Toast.LENGTH_SHORT).show()
    }

    private fun updateWifiServerUi(active: Boolean) {
        if (!::wifiButton.isInitialized || !::wifiUrlView.isInitialized) return
        wifiButton.text = if (active) "STOP WI-FI FILE ACCESS" else "START WI-FI FILE ACCESS"
        wifiUrlView.visibility = if (active) View.VISIBLE else View.GONE
        wifiUrlView.text = if (active) {
            val address = localIpv4Address()
            if (address == null) "Connect camera to Wi-Fi" else "http://$address:${currentWifiPort()}"
        } else ""
        wifiUrlView.setTextColor(if (active) Color.rgb(0, 128, 0) else defaultStatusColor)
    }

    private fun localIpv4Address(): String? {
        @Suppress("DEPRECATION")
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        @Suppress("DEPRECATION")
        val value = wifiManager?.connectionInfo?.ipAddress ?: 0
        if (value != 0) {
            return listOf(value and 0xff, value shr 8 and 0xff, value shr 16 and 0xff, value shr 24 and 0xff)
                .joinToString(".")
        }
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }

    private fun requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val missing = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        // Bluetooth/GNSS diagnostics published to the Client need location permission for the
        // passive Android Location/GnssStatus APIs. Denying it does not affect MP4 processing;
        // the diagnostics screen will simply report that location permission is unavailable.
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), STORAGE_PERMISSION_REQUEST)
        }
    }

    private fun startMonitorService(intent: Intent = Intent(this, RecordingMonitorService::class.java)) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    companion object {
        private const val MAIN_BUTTON_HEIGHT_DP = 52
        const val PREFERENCES_NAME = "settings"
        const val KEY_RECORDING_DIRECTORY = "recording_directory"
        const val KEY_OUTPUT_DIRECTORY = "output_directory"
        const val KEY_OUTPUT_TREE_URI = "output_tree_uri"
        private const val OUTPUT_TREE_PERMISSION_REQUEST = 4403
        const val KEY_MONITORING_ENABLED = "monitoring_enabled"
        const val KEY_WIFI_SERVER_ENABLED = "wifi_server_enabled"
        const val KEY_WIFI_SERVER_PORT = "wifi_server_port"
        private const val STORAGE_PERMISSION_REQUEST = 2001
        private const val INPUT_DIRECTORY_PICKER_REQUEST = 2002
        private const val OUTPUT_DIRECTORY_PICKER_REQUEST = 2003
    }
}
