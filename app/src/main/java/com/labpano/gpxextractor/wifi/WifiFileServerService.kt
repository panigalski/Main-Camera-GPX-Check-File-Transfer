package com.labpano.gpxextractor.wifi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.labpano.gpxextractor.AppConfig
import com.labpano.gpxextractor.R
import com.labpano.gpxextractor.api.DashboardApi
import com.labpano.gpxextractor.data.ProcessedRecordingStore
import com.labpano.gpxextractor.ui.MainActivity
import com.labpano.gpxextractor.monitor.RecordingStatusObserverManager
import com.labpano.gpxextractor.util.AppLog
import com.labpano.gpxextractor.util.StorageAccessCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Small HTTP server for browsing camera storage, synchronizing dashboard/report data, and
 * accepting the companion Client's tightly restricted manual backup-GPX uploads on the same LAN.
 * It exposes only the configured monitoring/output roots; application-private and system paths are
 * never added to the web root, and uploads can target only a date subfolder under OUTPUT.
 */
class WifiFileServerService : Service() {
    private val running = AtomicBoolean(false)
    private val clientPool = ThreadPoolExecutor(
        3,
        3,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(CLIENT_QUEUE_CAPACITY),
        { runnable -> Thread(runnable, "LabpanoWifiClient").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private var acceptThread: Thread? = null
    private var serverSocket: ServerSocket? = null
    private var listenPort: Int = DEFAULT_PORT

    override fun onCreate() {
        super.onCreate()
        listenPort = configuredPort(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        running.set(false)
        runCatching { serverSocket?.close() }
        acceptThread?.interrupt()
        clientPool.shutdownNow()
        RecordingStatusObserverManager.stop()
        setEnabled(false)
        publishStatus(false, null)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startServer() {
        if (!running.compareAndSet(false, true)) return
        acceptThread = Thread({
            try {
                ServerSocket().use { server ->
                    server.reuseAddress = true
                    server.bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), listenPort), BACKLOG)
                    serverSocket = server
                    setEnabled(true)
                    publishStatus(true, null)
                    AppLog.info("Wi-Fi file server listening on port $listenPort")
                    while (running.get()) {
                        val socket = try {
                            server.accept()
                        } catch (error: IOException) {
                            if (running.get()) AppLog.error("Wi-Fi server accept failed", error)
                            break
                        }
                        try {
                            clientPool.execute { handleClient(socket) }
                        } catch (_: java.util.concurrent.RejectedExecutionException) {
                            runCatching { socket.close() }
                            AppLog.warn("Wi-Fi client queue is full; connection rejected")
                        }
                    }
                }
            } catch (error: Exception) {
                AppLog.error("Unable to start Wi-Fi file server", error)
                setEnabled(false)
                val message = if (error is BindException) {
                    "Port $listenPort is already in use. Stop the other service or choose another port in Advanced."
                } else {
                    error.message ?: error.javaClass.simpleName
                }
                publishStatus(false, message)
                stopSelf()
            }
        }, "LabpanoWifiServer").apply {
            isDaemon = true
            start()
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val requestLine = try {
                readHttpLine(input, MAX_REQUEST_LINE_LENGTH) ?: return
            } catch (_: IOException) {
                sendText(output, 414, "URI Too Long", "Request line is too long")
                return
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) {
                sendText(output, 400, "Bad Request", "Malformed HTTP request")
                return
            }
            val method = parts[0].uppercase(Locale.US)
            if (method != "GET" && method != "HEAD" && method != "DELETE" && method != "POST") {
                drainHeaders(input)
                sendText(output, 405, "Method Not Allowed", "Only GET, HEAD, DELETE and POST are supported", method == "HEAD")
                return
            }
            val headers = try {
                readHeaders(input)
            } catch (error: IOException) {
                sendText(output, 400, "Bad Request", error.message ?: "Invalid HTTP headers", method == "HEAD")
                return
            }
            val requestTarget = parts[1]
            val rawPath = requestTarget.substringBefore('?')
            val query = requestTarget.substringAfter('?', "")
            val path = runCatching { URLDecoder.decode(rawPath, "UTF-8") }.getOrElse {
                sendText(output, 400, "Bad Request", "Invalid URL", method == "HEAD")
                return
            }
            val forceDownload = query.split('&').any { it.equals("download=1", ignoreCase = true) }
            try {
                route(method, path, query, headers, input, output, method == "HEAD", forceDownload)
            } catch (error: Throwable) {
                AppLog.error("Wi-Fi request failed: $method $path", error)
                runCatching {
                    sendJson(
                        output,
                        JSONObject().apply {
                            put("error", true)
                            put("message", error.message ?: error.javaClass.simpleName)
                        }.toString(),
                        method == "HEAD",
                        500,
                        "Internal Server Error"
                    )
                }
            }
        }
    }

    private fun route(
        method: String,
        path: String,
        query: String,
        headers: Map<String, String>,
        input: BufferedInputStream,
        output: BufferedOutputStream,
        headOnly: Boolean,
        forceDownload: Boolean
    ) {
        val rangeHeader = headers["range"]
        if (path == "/api/v1/backup-gpx-upload") {
            if (method != "POST") {
                sendText(output, 405, "Method Not Allowed", "Backup GPX upload requires POST", headOnly)
            } else {
                receiveBackupGpxUpload(query, headers, input, output)
            }
            return
        }
        if (path == "/api/v1/pending-gpx-file") {
            if (method != "GET" && method != "HEAD") {
                sendText(output, 405, "Method Not Allowed", "Pending GPX download supports GET and HEAD only", headOnly)
            } else {
                sendPendingGpx(parseQuery(query)["id"].orEmpty(), rangeHeader, output, headOnly)
            }
            return
        }
        if (path == "/api/v1/pending-gpx") {
            if (method != "GET" && method != "HEAD") {
                sendText(output, 405, "Method Not Allowed", "Pending GPX supports GET and HEAD only", headOnly)
            } else {
                sendJson(output, buildPendingGpxQueue(query), headOnly)
            }
            return
        }
        if (path == "/api/v1/live-status") {
            if (method != "GET" && method != "HEAD") {
                sendText(output, 405, "Method Not Allowed", "Live status supports GET and HEAD only", headOnly)
            } else {
                sendJson(output, DashboardApi.buildLiveStatus(this), headOnly)
            }
            return
        }
        if (path == "/api/v1/dashboard") {
            if (method != "GET" && method != "HEAD") {
                sendText(output, 405, "Method Not Allowed", "Dashboard supports GET and HEAD only", headOnly)
            } else {
                // The companion Client marks its first dashboard request with syncCameraSettings=1.
                // Force only that handshake to re-read Camera's /efs/video.properties immediately;
                // periodic full/live polls keep their normal throttling. Older Clients omit the flag.
                val syncCameraSettings = parseQuery(query)["syncCameraSettings"] == "1"
                sendJson(
                    output,
                    DashboardApi.build(this, forceCameraSettingsRefresh = syncCameraSettings),
                    headOnly
                )
            }
            return
        }
        if (path == "/api/v1/report-entry") {
            if (method != "DELETE") {
                sendText(output, 405, "Method Not Allowed", "Report entry endpoint requires DELETE", headOnly)
                return
            }
            val parameters = parseQuery(query)
            val result = DashboardApi.deleteEntry(
                this,
                parameters["type"].orEmpty(),
                parameters["timestamp"].orEmpty(),
                parameters["path"].orEmpty(),
                parameters["message"].orEmpty()
            )
            sendJson(output, result.toJson(), false, result.statusCode, if (result.deleted) "OK" else "Error")
            return
        }
        if (path == "/api/v1/health") {
            sendJson(output, "{\"ok\":true,\"apiVersion\":3}", headOnly)
            return
        }
        if (method == "DELETE") {
            sendText(output, 405, "Method Not Allowed", "DELETE is supported only for report entries", headOnly)
            return
        }
        if (method == "POST") {
            sendText(output, 405, "Method Not Allowed", "POST is supported only for backup GPX upload", headOnly)
            return
        }
        if (path == "/" || path.isBlank()) {
            sendRootPage(output, headOnly)
            return
        }
        val segments = path.trimStart('/').split('/', limit = 2)
        val rootId = segments.firstOrNull().orEmpty()
        val root = storageRoots().firstOrNull { it.id == rootId }
        if (root == null) {
            sendText(output, 404, "Not Found", "Storage root not found", headOnly)
            return
        }
        val relative = if (segments.size == 2) segments[1] else ""
        val target = safeResolve(root.directory, relative)
        if (target == null || !target.exists() || !target.canRead()) {
            sendText(output, 404, "Not Found", "File or folder not found", headOnly)
            return
        }
        if (target.isDirectory) sendDirectoryPage(root, target, output, headOnly)
        else sendFile(target, rangeHeader, output, headOnly, forceDownload)
    }

    /** Returns the durable, readable GPX queue instead of deriving it from a bounded report tail. */
    private fun buildPendingGpxQueue(query: String): String {
        val parameters = parseQuery(query)
        val limit = parameters["limit"]?.toIntOrNull()
            ?.coerceIn(1, AppConfig.MAX_PENDING_API_PAGE_SIZE)
            ?: AppConfig.MAX_PENDING_API_PAGE_SIZE
        val offset = parameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val includeMediaOnly = parameters["includeMediaOnly"] == "1" ||
            parameters["includeMediaOnly"].equals("true", ignoreCase = true)
        val items = JSONArray()
        val store = ProcessedRecordingStore(this)
        val total: Long
        val pageSize: Int
        try {
            store.prune()
            val migrationPrefs = getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            if (store.pendingGpxCount() == 0L && !migrationPrefs.getBoolean(KEY_LEGACY_PENDING_IMPORT_DONE, false)) {
                try {
                    importLegacyPendingQueue(store)
                } finally {
                    // Legacy/daily report import may scan historical days. Attempt it once,
                    // never on every 3-second client synchronization when there is nothing to import.
                    migrationPrefs.edit().putBoolean(KEY_LEGACY_PENDING_IMPORT_DONE, true).apply()
                }
            }
            total = store.pendingGpxCount(includeMediaOnly)
            val page = store.listPendingGpx(limit, offset, includeMediaOnly)
            pageSize = page.size
            page.forEach { entry ->
                val readableSize = pendingGpxReadableSize(entry)
                val hasVideoInterval = entry.videoStartMillis != null && entry.videoEndMillis != null &&
                    entry.videoStartMillis > 0L && entry.videoEndMillis > entry.videoStartMillis
                if (readableSize == null && (!includeMediaOnly || !hasVideoInterval)) return@forEach
                items.put(JSONObject().apply {
                    put("id", entry.id)
                    put("status", entry.status)
                    put("completedAt", entry.completedAt)
                    put("videoName", entry.videoName)
                    put("videoPath", entry.videoPath)
                    put("gpxName", entry.gpxName)
                    put("gpxPath", entry.gpxPath)
                    put("gpxSizeBytes", readableSize ?: 0L)
                    if (entry.videoStartMillis != null) put("videoStartMillis", entry.videoStartMillis)
                    else put("videoStartMillis", JSONObject.NULL)
                    if (entry.videoEndMillis != null) put("videoEndMillis", entry.videoEndMillis)
                    else put("videoEndMillis", JSONObject.NULL)
                    put(
                        "downloadUrl",
                        if (readableSize != null) "/api/v1/pending-gpx-file?id=${urlEncode(entry.id)}" else ""
                    )
                })
            }
        } finally {
            store.close()
        }
        return JSONObject().apply {
            put("apiVersion", 3)
            put("includeMediaOnly", includeMediaOnly)
            put("generatedAt", System.currentTimeMillis())
            put("offset", offset)
            put("limit", limit)
            put("total", total)
            put("nextOffset", if (offset + pageSize < total) offset + pageSize else JSONObject.NULL)
            put("items", items)
        }.toString()
    }

    private fun pendingGpxReadableSize(entry: ProcessedRecordingStore.PendingGpxEntry): Long? {
        if (entry.gpxSizeBytes <= 0L) return null
        return if (entry.gpxPath.startsWith("content://", ignoreCase = true)) {
            runCatching {
                contentResolver.openInputStream(Uri.parse(entry.gpxPath))?.use { entry.gpxSizeBytes }
            }.getOrNull()
        } else {
            val file = File(entry.gpxPath)
            file.takeIf { it.isFile && it.canRead() && it.length() > 0L }?.length()
        }
    }

    private fun sendPendingGpx(
        id: String,
        rangeHeader: String?,
        output: BufferedOutputStream,
        headOnly: Boolean
    ) {
        if (id.isBlank()) {
            sendText(output, 400, "Bad Request", "Missing pending GPX id", headOnly)
            return
        }
        val store = ProcessedRecordingStore(this)
        val entry = try {
            store.findPendingGpx(id)
        } finally {
            store.close()
        }
        if (entry == null) {
            sendText(output, 404, "Not Found", "Pending GPX entry not found", headOnly)
            return
        }
        if (entry.gpxPath.startsWith("content://", ignoreCase = true)) {
            val input = runCatching { contentResolver.openInputStream(Uri.parse(entry.gpxPath)) }.getOrNull()
            if (input == null || entry.gpxSizeBytes <= 0L) {
                input?.close()
                sendText(output, 404, "Not Found", "Pending GPX file is unavailable", headOnly)
                return
            }
            sendInputStream(
                input = input,
                name = entry.gpxName,
                total = entry.gpxSizeBytes,
                rangeHeader = rangeHeader,
                output = output,
                headOnly = headOnly
            )
        } else {
            val file = File(entry.gpxPath)
            if (!file.isFile || !file.canRead() || file.length() <= 0L) {
                sendText(output, 404, "Not Found", "Pending GPX file is unavailable", headOnly)
                return
            }
            sendFile(file, rangeHeader, output, headOnly, true)
        }
    }

    private fun sendInputStream(
        input: InputStream,
        name: String,
        total: Long,
        rangeHeader: String?,
        output: BufferedOutputStream,
        headOnly: Boolean
    ) {
        input.use { rawInput ->
            val range = parseRange(rangeHeader, total)
            val start = range?.first ?: 0L
            val end = range?.second ?: (total - 1L)
            val length = (end - start + 1L).coerceAtLeast(0L)
            val status = if (range == null) 200 else 206
            val reason = if (range == null) "OK" else "Partial Content"
            val headers = linkedMapOf(
                "Content-Type" to "application/gpx+xml",
                "Content-Length" to length.toString(),
                "Accept-Ranges" to "bytes",
                "Content-Disposition" to "attachment; filename*=UTF-8''${urlEncode(name)}",
                "Cache-Control" to "no-store",
                "Connection" to "close"
            )
            if (range != null) headers["Content-Range"] = "bytes $start-$end/$total"
            writeHeaders(output, status, reason, headers)
            if (!headOnly && length > 0L) {
                var skipped = 0L
                while (skipped < start) {
                    val amount = rawInput.skip(start - skipped)
                    if (amount > 0L) {
                        skipped += amount
                    } else {
                        if (rawInput.read() < 0) break
                        skipped++
                    }
                }
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0L) {
                    val read = rawInput.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            output.flush()
        }
    }

    private fun importLegacyPendingQueue(store: ProcessedRecordingStore) {
        val preferences = getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val outputPath = preferences.getString(MainActivity.KEY_OUTPUT_DIRECTORY, null)
            ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
            ?: AppConfig.defaultOutputDirectory.absolutePath
        listOf("GOOD", "FAILED").forEach { status ->
            legacyAndDailyReports(File(outputPath), status).forEach { report ->
                report.useLines(Charsets.UTF_8) { lines ->
                    lines.filter { it.isNotBlank() }.forEach entries@ { line ->
                        val parts = line.split('\t', limit = 3)
                        val completedAt = parts.getOrElse(0) { "" }
                        val sourcePath = parts.getOrElse(1) { "" }
                        val message = parts.getOrElse(2) { "" }
                        val videoName = legacyMessageValue(message, "video") ?: File(sourcePath).name
                        val gpxName = legacyMessageValue(message, "gpx")
                            ?: videoName.replace(Regex("(?i)\\.mp4$"), ".gpx")
                        val destination = legacyMessageValue(message, "destination")
                            ?: legacyMessageValue(message, "movedTo")
                            ?: return@entries
                        val gpxFile = File(destination, gpxName)
                        if (!gpxFile.isFile || gpxFile.length() <= 0L) return@entries
                        store.enqueuePendingGpx(
                            ProcessedRecordingStore.PendingGpxEntry(
                                id = "legacy|$status|${gpxFile.absolutePath.lowercase(Locale.US)}",
                                status = status,
                                completedAt = completedAt,
                                videoName = videoName,
                                videoPath = File(destination, videoName).absolutePath,
                                gpxName = gpxName,
                                gpxPath = gpxFile.absolutePath,
                                gpxSizeBytes = gpxFile.length()
                            )
                        )
                    }
                }
            }
        }
    }

    private fun legacyAndDailyReports(outputRoot: File, status: String): List<File> {
        val reports = linkedSetOf<File>()
        // Current layout keeps both OUTPUT/<STATUS>.TXT and
        // OUTPUT/dd-MM-yyyy/<STATUS>/dd-MM-yyyy_<STATUS>.txt.
        outputRoot.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("\\d{2}-\\d{2}-\\d{4}")) }
            ?.sortedBy { it.name }
            ?.forEach { day ->
                File(File(day, status), "${day.name}_${status}.txt")
                    .takeIf { it.isFile && it.canRead() }?.let(reports::add)
            }
        // Older layouts are accepted only as import sources.
        File(outputRoot, "$status.TXT").takeIf { it.isFile && it.canRead() }?.let(reports::add)
        File(File(outputRoot, status), "$status.TXT").takeIf { it.isFile && it.canRead() }?.let(reports::add)
        return reports.toList()
    }

    private fun legacyMessageValue(message: String, key: String): String? =
        Regex("(?:^|;)\\s*${Regex.escape(key)}=([^;]+)", RegexOption.IGNORE_CASE)
            .find(message)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

    private fun webPathForFile(file: File, roots: List<StorageRoot>): String? {
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        val root = roots.firstOrNull { candidate ->
            val canonicalRoot = runCatching { candidate.directory.canonicalFile }.getOrNull() ?: return@firstOrNull false
            canonical.path == canonicalRoot.path || canonical.path.startsWith(canonicalRoot.path + File.separator)
        } ?: return null
        val canonicalRoot = runCatching { root.directory.canonicalFile }.getOrNull() ?: return null
        val relative = canonical.relativeTo(canonicalRoot).path.replace(File.separatorChar, '/')
        val encoded = relative.split('/').joinToString("/") { urlEncode(it) }
        return "/${urlEncode(root.id)}/$encoded"
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { pair ->
            val equals = pair.indexOf('=')
            val rawKey = if (equals >= 0) pair.substring(0, equals) else pair
            val rawValue = if (equals >= 0) pair.substring(equals + 1) else ""
            val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrNull() ?: return@mapNotNull null
            val value = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrNull() ?: return@mapNotNull null
            key to value
        }.toMap()
    }

    /** Exposes only the configured monitoring and output trees, not all shared storage. */
    private fun storageRoots(): List<StorageRoot> {
        val preferences = getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
        val monitoring = File(
            preferences.getString(MainActivity.KEY_RECORDING_DIRECTORY, null)
                ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
                ?: AppConfig.defaultRecordingDirectory.absolutePath
        )
        val output = File(
            preferences.getString(MainActivity.KEY_OUTPUT_DIRECTORY, null)
                ?.takeIf { it.isNotBlank() && !it.startsWith("content://") }
                ?: AppConfig.defaultOutputDirectory.absolutePath
        )
        val candidates = listOf(
            Triple("monitoring", "Monitoring folder", monitoring),
            Triple("output", "Output folder", output)
        )
        val seen = mutableSetOf<String>()
        return candidates.mapNotNull { (id, label, directory) ->
            if (!directory.exists() || !directory.canRead()) return@mapNotNull null
            val canonical = runCatching { directory.canonicalPath }.getOrElse { directory.absolutePath }
            if (!seen.add(canonical)) return@mapNotNull null
            StorageRoot(id, label, directory)
        }
    }

    private fun safeResolve(root: File, relative: String): File? {
        return runCatching {
            val canonicalRoot = root.canonicalFile
            val candidate = File(canonicalRoot, relative).canonicalFile
            if (candidate.path == canonicalRoot.path || candidate.path.startsWith(canonicalRoot.path + File.separator)) {
                candidate
            } else null
        }.getOrNull()
    }

    private fun sendRootPage(output: BufferedOutputStream, headOnly: Boolean) {
        val items = storageRoots().joinToString("") { root ->
            "<li><a href=\"/${urlEncode(root.id)}/\">${html(root.label)}</a>" +
                "<small>${html(root.directory.absolutePath)}</small></li>"
        }
        val body = page(
            "Labpano Camera Storage",
            """
            <h1>Labpano Camera Storage</h1>
            <div class="notice"><strong>Windows 10:</strong> connect the laptop and camera to the same router, then enter this address in Microsoft Edge, Chrome, or Firefox. Select a storage root to browse folders and download files.</div>
            <ul class="files">$items</ul>
            <p class="muted">Browsing/downloads are restricted to configured monitoring/output folders. Companion Client GPX uploads are restricted to OUTPUT/dd-mm-yyyy/GOOD|FAILED|ERROR folders.</p>
            """.trimIndent()
        )
        sendHtml(output, 200, "OK", body, headOnly)
    }

    private fun sendDirectoryPage(root: StorageRoot, directory: File, output: BufferedOutputStream, headOnly: Boolean) {
        val rootPath = root.directory.canonicalPath
        val directoryPath = directory.canonicalPath
        val relative = if (directoryPath == rootPath) "" else directoryPath
            .removePrefix(rootPath)
            .trimStart(File.separatorChar)
            .replace(File.separatorChar, '/')
        val baseUrl = "/${urlEncode(root.id)}/" + if (relative.isBlank()) "" else relative.split('/').joinToString("/") { urlEncode(it) } + "/"
        val parent = if (directory.canonicalPath == root.directory.canonicalPath) "/" else baseUrl.trimEnd('/').substringBeforeLast('/', "/") + "/"

        val entries = directory.listFiles()
            ?.filter { file -> !file.isHidden && !file.name.endsWith(".part", true) && !file.name.endsWith(".tmp", true) }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.US) }))
            .orEmpty()
        val list = buildString {
            if (directory.canonicalPath != root.directory.canonicalPath) {
                append("<li class=\"dir\"><a href=\"").append(html(parent)).append("\">↩ Parent folder</a></li>")
            }
            entries.forEach { file ->
                val href = baseUrl + urlEncode(file.name) + if (file.isDirectory) "/" else ""
                val icon = if (file.isDirectory) "📁" else "📄"
                val details = if (file.isDirectory) "Folder" else formatSize(file.length())
                append("<li><div class=\"entry\"><a href=\"").append(html(href)).append("\">")
                    .append(icon).append(' ').append(html(file.name)).append("</a>")
                if (!file.isDirectory) {
                    append("<a class=\"download\" href=\"").append(html(href)).append("?download=1\">Download</a>")
                }
                append("</div><small>").append(html(details)).append("</small></li>")
            }
        }
        val breadcrumb = if (relative.isBlank()) root.label else "${root.label}/$relative"
        val body = page(
            breadcrumb,
            "<h1>${html(breadcrumb)}</h1><p><a href=\"/\">Storage roots</a></p><ul class=\"files\">$list</ul>"
        )
        sendHtml(output, 200, "OK", body, headOnly)
    }

    private fun sendFile(
        file: File,
        rangeHeader: String?,
        output: BufferedOutputStream,
        headOnly: Boolean,
        forceDownload: Boolean
    ) {
        val snapshot = try {
            StorageAccessCoordinator.withRead(file) {
                if (!file.isFile || !file.canRead()) throw IOException("File is unavailable")
                DownloadSnapshot(
                    name = file.name,
                    totalBytes = file.length(),
                    modifiedAt = file.lastModified(),
                    input = FileInputStream(file)
                )
            }
        } catch (error: Throwable) {
            sendText(output, 404, "Not Found", error.message ?: "File is unavailable", headOnly)
            return
        }

        snapshot.input.use { rawInput ->
            val total = snapshot.totalBytes
            val range = parseRange(rangeHeader, total)
            if (rangeHeader != null && range == null) {
                writeHeaders(output, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
                output.flush()
                return
            }
            val start = range?.first ?: 0L
            val end = range?.second ?: (total - 1L).coerceAtLeast(0L)
            val length = if (total == 0L) 0L else end - start + 1L
            val status = if (range != null) 206 else 200
            val reason = if (range != null) "Partial Content" else "OK"
            val headers = linkedMapOf(
                "Content-Type" to mimeType(File(snapshot.name)),
                "Content-Length" to length.toString(),
                "Accept-Ranges" to "bytes",
                "Last-Modified" to httpDate(snapshot.modifiedAt),
                "Content-Disposition" to "${if (forceDownload) "attachment" else "inline"}; filename*=UTF-8''${urlEncode(snapshot.name)}",
                "Connection" to "close"
            )
            if (range != null) headers["Content-Range"] = "bytes $start-$end/$total"
            writeHeaders(output, status, reason, headers)
            if (!headOnly && length > 0L) {
                BufferedInputStream(rawInput).use { input ->
                    var skipped = 0L
                    while (skipped < start) {
                        val amount = input.skip(start - skipped)
                        if (amount <= 0L) break
                        skipped += amount
                    }
                    val buffer = ByteArray(64 * 1024)
                    var remaining = length
                    while (remaining > 0L) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            output.flush()
        }
    }

    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return null
        if (!header.startsWith("bytes=") || total <= 0L) return null
        val value = header.removePrefix("bytes=").substringBefore(',').trim()
        val dash = value.indexOf('-')
        if (dash < 0) return null
        val left = value.substring(0, dash).trim()
        val right = value.substring(dash + 1).trim()
        return when {
            left.isBlank() -> {
                val suffix = right.toLongOrNull() ?: return null
                if (suffix <= 0L) return null
                val start = (total - suffix).coerceAtLeast(0L)
                start to (total - 1L)
            }
            else -> {
                val start = left.toLongOrNull() ?: return null
                val end = if (right.isBlank()) total - 1L else right.toLongOrNull() ?: return null
                if (start < 0L || start >= total || end < start) return null
                start to minOf(end, total - 1L)
            }
        }
    }

    private fun readHttpLine(input: BufferedInputStream, maxLength: Int): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) return if (bytes.size() == 0) null else bytes.toString(Charsets.ISO_8859_1.name())
            if (value == '\n'.code) break
            if (value != '\r'.code) {
                if (bytes.size() >= maxLength) throw IOException("HTTP line is too long")
                bytes.write(value)
            }
        }
        return bytes.toString(Charsets.ISO_8859_1.name())
    }

    private fun readHeaders(input: BufferedInputStream): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        var count = 0
        while (true) {
            val line = readHttpLine(input, MAX_HEADER_LINE_LENGTH) ?: break
            if (line.isEmpty()) break
            count++
            if (count > MAX_HEADER_COUNT) throw IOException("Too many HTTP headers")
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] = line.substring(separator + 1).trim()
            }
        }
        return headers
    }

    private fun drainHeaders(input: BufferedInputStream) {
        var count = 0
        while (count++ < MAX_HEADER_COUNT) {
            val line = runCatching { readHttpLine(input, MAX_HEADER_LINE_LENGTH) }.getOrNull() ?: return
            if (line.isEmpty()) return
        }
    }

    private fun receiveBackupGpxUpload(
        query: String,
        headers: Map<String, String>,
        input: BufferedInputStream,
        output: BufferedOutputStream
    ) {
        val parameters = parseQuery(query)
        val status = parameters["status"].orEmpty()
        val subfolder = parameters["subfolder"].orEmpty()
        val fileName = parameters["filename"].orEmpty()
        val expectedSha256 = parameters["sha256"]
        val contentLength = headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0L) {
            sendJson(output, JSONObject().apply {
                put("error", true)
                put("message", "Content-Length is required")
            }.toString(), false, 411, "Length Required")
            return
        }
        if (contentLength > BackupGpxUploadStore.MAX_GPX_UPLOAD_BYTES.toLong()) {
            sendJson(output, JSONObject().apply {
                put("error", true)
                put("message", "GPX upload exceeds the size limit")
            }.toString(), false, 413, "Payload Too Large")
            return
        }
        val body = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = input.read(body, offset, body.size - offset)
            if (read < 0) {
                sendJson(output, JSONObject().apply {
                    put("error", true)
                    put("message", "GPX upload ended before Content-Length bytes were received")
                }.toString(), false, 400, "Bad Request")
                return
            }
            offset += read
        }

        try {
            val stored = BackupGpxUploadStore(this).store(status, subfolder, fileName, body, expectedSha256)
            sendJson(output, JSONObject().apply {
                put("ok", true)
                put("status", stored.status)
                put("subfolder", stored.subfolder)
                put("fileName", stored.fileName)
                put("sizeBytes", stored.sizeBytes)
                put("sha256", stored.sha256)
                put("destination", stored.destination)
                put("alreadyPresent", stored.alreadyPresent)
            }.toString(), false)
        } catch (error: BackupGpxUploadStore.UploadException) {
            val reason = when (error.statusCode) {
                400 -> "Bad Request"
                409 -> "Conflict"
                413 -> "Payload Too Large"
                else -> "Internal Server Error"
            }
            sendJson(output, JSONObject().apply {
                put("error", true)
                put("message", error.message ?: "GPX upload failed")
            }.toString(), false, error.statusCode, reason)
        }
    }

    private fun sendHtml(output: BufferedOutputStream, code: Int, reason: String, body: String, headOnly: Boolean) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHeaders(output, code, reason, mapOf(
            "Content-Type" to "text/html; charset=utf-8",
            "Content-Length" to bytes.size.toString(),
            "Cache-Control" to "no-store",
            "Connection" to "close"
        ))
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    private fun sendText(output: BufferedOutputStream, code: Int, reason: String, message: String, headOnly: Boolean = false) {
        sendHtml(output, code, reason, page(reason, "<h1>${html(reason)}</h1><p>${html(message)}</p>"), headOnly)
    }

    private fun sendJson(
        output: BufferedOutputStream,
        body: String,
        headOnly: Boolean,
        code: Int = 200,
        reason: String = "OK"
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHeaders(output, code, reason, mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "Content-Length" to bytes.size.toString(),
            "Cache-Control" to "no-store",
            "Access-Control-Allow-Origin" to "*",
            "Connection" to "close"
        ))
        if (!headOnly) output.write(bytes)
        output.flush()
    }

    private fun writeHeaders(output: BufferedOutputStream, code: Int, reason: String, headers: Map<String, String>) {
        val text = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Server: Labpano-GPX-Extractor\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun page(title: String, content: String): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${html(title)}</title><style>
body{font-family:sans-serif;margin:0;background:#f4f6f8;color:#202124}main{max-width:900px;margin:auto;padding:16px}
h1{font-size:1.35rem}.files{list-style:none;padding:0}.files li{background:white;margin:6px 0;padding:12px;border-radius:8px;box-shadow:0 1px 2px #bbb}
a{color:#075fa8;text-decoration:none;word-break:break-word}small{display:block;color:#687078;margin-top:4px}.entry{display:flex;gap:12px;align-items:center;justify-content:space-between}.download{flex:none;background:#6f42c1;color:white;padding:7px 11px;border-radius:6px}.notice{background:#eaf3ff;border-left:4px solid #075fa8;padding:12px;border-radius:6px}.muted{color:#687078}
</style></head><body><main>$content</main></body></html>"""

    private fun html(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unit = -1
        while (size >= 1024 && unit < units.lastIndex) { size /= 1024; unit++ }
        return String.format(Locale.US, "%.1f %s", size, units[unit])
    }

    private fun mimeType(file: File): String = when (file.extension.lowercase(Locale.US)) {
        "mp4", "m4v" -> "video/mp4"
        "gpx" -> "application/gpx+xml"
        "txt", "log" -> "text/plain; charset=utf-8"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "json" -> "application/json"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    private fun httpDate(timestamp: Long): String = SimpleDateFormat(
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(timestamp))

    private fun setEnabled(enabled: Boolean) {
        getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(MainActivity.KEY_WIFI_SERVER_ENABLED, enabled).apply()
    }

    private fun publishStatus(active: Boolean, error: String?) {
        sendBroadcast(Intent(ACTION_SERVER_STATUS).setPackage(packageName).apply {
            putExtra(EXTRA_ACTIVE, active)
            putExtra(EXTRA_ERROR, error)
        })
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            CHANNEL_ID, "Wi-Fi connection", NotificationManager.IMPORTANCE_LOW
        ))
    }

    private fun buildNotification(): Notification {
        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), pendingFlags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder.setSmallIcon(R.drawable.ic_app)
            .setContentTitle("Labpano Wi-Fi connection")
            .setContentText("File access and GPX upload available on port $listenPort")
            .setContentIntent(pendingIntent).setOngoing(true).build()
    }

    private data class DownloadSnapshot(
        val name: String,
        val totalBytes: Long,
        val modifiedAt: Long,
        val input: FileInputStream
    )

    data class StorageRoot(val id: String, val label: String, val directory: File)

    companion object {
        private const val KEY_LEGACY_PENDING_IMPORT_DONE = "legacy_pending_import_done_v1"
        const val DEFAULT_PORT = 1100
        const val MIN_PORT = 1024
        const val MAX_PORT = 65535
        /** Backward-compatible alias for integrations that assumed the original default port. */
        const val PORT = DEFAULT_PORT

        fun configuredPort(context: Context): Int = context
            .getSharedPreferences(MainActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getInt(MainActivity.KEY_WIFI_SERVER_PORT, DEFAULT_PORT)
            .coerceIn(MIN_PORT, MAX_PORT)

        const val ACTION_SERVER_STATUS = "com.labpano.gpxextractor.WIFI_SERVER_STATUS"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_ERROR = "error"
        private const val CHANNEL_ID = "wifi_file_access"
        private const val NOTIFICATION_ID = 1101
        private const val BACKLOG = 8
        private const val CLIENT_QUEUE_CAPACITY = 16
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_REQUEST_LINE_LENGTH = 4_096
        private const val MAX_HEADER_LINE_LENGTH = 8_192
        private const val MAX_HEADER_COUNT = 64
    }
}
