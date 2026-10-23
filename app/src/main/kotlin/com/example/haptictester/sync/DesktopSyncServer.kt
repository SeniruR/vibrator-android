package com.example.haptictester.sync

import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom

data class SyncServerStatus(
    val running: Boolean = false,
    val port: Int = DesktopSyncServer.DEFAULT_PORT,
    val pin: String = "",
    val localIps: List<String> = emptyList(),
    val connectedClientIp: String? = null,
    val error: String? = null,
)

sealed interface RemoteSyncCommand {
    data class Play(val timeMs: Long?) : RemoteSyncCommand
    data class Pause(val timeMs: Long?) : RemoteSyncCommand
    data class Seek(val timeMs: Long) : RemoteSyncCommand
    data class SetSpeed(val speed: Float) : RemoteSyncCommand
    data class SelectAlgorithm(val algorithmId: String) : RemoteSyncCommand
    data class ToggleEvent(val eventId: String) : RemoteSyncCommand
    data class SetSelectedEvents(val eventIds: Set<String>) : RemoteSyncCommand
    data class LoadBundle(val bundleId: String) : RemoteSyncCommand
    data class UpdateDesktopLibrary(val bundles: List<String>) : RemoteSyncCommand
    data object ClearMedia : RemoteSyncCommand
    data object RequestState : RemoteSyncCommand
}

class DesktopSyncServer(
    private val onCommand: (RemoteSyncCommand) -> Unit,
    private val onReceiveFile: ((bundleId: String, fileName: String, contentLength: Long, input: InputStream) -> Result<Unit>)? = null,
    private val onFinishBundleUpload: ((bundleId: String, autoLoad: Boolean) -> Result<Unit>)? = null,
    private val onGetBundleManifest: ((bundleId: String) -> Result<JSONArray>)? = null,
    private val onOpenBundleFileStream: ((bundleId: String, fileName: String) -> Result<Pair<Long, InputStream>>)? = null,
) {
    companion object {
        const val DEFAULT_PORT = 8765
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_FRAME_BYTES = 64 * 1024 // 64 KB cap for control messages

        fun findLocalIpv4Addresses(): List<String> {
            return try {
                val results = mutableListOf<Pair<Int, String>>()
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                for (nif in interfaces) {
                    if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                    val name = nif.name.lowercase()
                    // Ignore mobile data interfaces (rmnet, ccmni, pdp)
                    if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") || name.startsWith("dummy")) {
                        continue
                    }
                    val priority = when {
                        name.contains("ap") || name.contains("swlan") || name.contains("hotspot") || name.contains("tether") -> 0
                        name.contains("wlan") || name.contains("wifi") -> 1
                        name.contains("rndis") || name.contains("eth") -> 2
                        else -> 3
                    }
                    for (addr in nif.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress && isPrivateLanAddress(addr)) {
                            val ip = addr.hostAddress ?: continue
                            results += priority to ip
                        }
                    }
                }
                results.sortedBy { it.first }.map { it.second }.distinct()
            } catch (_: Throwable) {
                emptyList()
            }
        }

        private fun isPrivateLanAddress(address: InetAddress): Boolean {
            if (address.isLoopbackAddress || address.isSiteLocalAddress) return true
            val raw = address.address
            if (raw.size == 4) {
                val b0 = raw[0].toInt() and 0xFF
                val b1 = raw[1].toInt() and 0xFF
                if (b0 == 10) return true
                if (b0 == 172 && b1 in 16..31) return true
                if (b0 == 192 && b1 == 168) return true
            }
            return false
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    @Volatile
    private var activeClientSocket: Socket? = null

    @Volatile
    private var activeOutput: OutputStream? = null
    private val writeLock = Any()

    private val _status = MutableStateFlow(SyncServerStatus())
    val status = _status.asStateFlow()

    fun start(port: Int = DEFAULT_PORT) {
        stop()
        val pin = "%04d".format(SecureRandom().nextInt(10_000))
        val ips = findLocalIpv4Addresses()

        acceptJob = scope.launch {
            try {
                val server = ServerSocket()
                server.reuseAddress = true
                server.bind(InetSocketAddress(port))
                serverSocket = server

                _status.value = SyncServerStatus(
                    running = true,
                    port = port,
                    pin = pin,
                    localIps = ips.ifEmpty { findLocalIpv4Addresses() },
                    connectedClientIp = null,
                    error = null,
                )

                while (isActive && !server.isClosed) {
                    val client = try {
                        server.accept()
                    } catch (_: Throwable) {
                        break
                    }
                    // Security check: only allow private LAN / Hotspot addresses
                    val remoteAddr = client.inetAddress
                    if (remoteAddr == null || !isPrivateLanAddress(remoteAddr)) {
                        runCatching { client.close() }
                        continue
                    }
                    launch {
                        handleIncomingSocket(client, pin)
                    }
                }
            } catch (e: Throwable) {
                _status.value = _status.value.copy(
                    running = false,
                    connectedClientIp = null,
                    error = e.message ?: "Failed to start sync server",
                )
            }
        }
    }

    fun refreshLocalIps() {
        if (_status.value.running) {
            _status.value = _status.value.copy(localIps = findLocalIpv4Addresses())
        }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        synchronized(writeLock) {
            runCatching { activeClientSocket?.close() }
            activeClientSocket = null
            activeOutput = null
        }
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.coroutineContext.cancelChildren()
        _status.value = SyncServerStatus(running = false)
    }

    fun broadcastJson(payload: JSONObject) {
        val text = payload.toString()
        scope.launch {
            sendTextFrame(text)
        }
    }

    private fun handleIncomingSocket(socket: Socket, expectedPin: String) {
        try {
            socket.soTimeout = 10_000 // 10s timeout during handshake
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())

            val requestLines = readHttpHeaders(input)
            if (requestLines.isEmpty()) {
                socket.close()
                return
            }

            val firstLine = requestLines[0]
            val parts = firstLine.split(" ")
            val method = parts.getOrNull(0)?.uppercase() ?: "GET"
            val path = parts.getOrNull(1) ?: "/"

            val headers = mutableMapOf<String, String>()
            for (i in 1 until requestLines.size) {
                val line = requestLines[i]
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val key = line.substring(0, colon).trim().lowercase()
                    val value = line.substring(colon + 1).trim()
                    headers[key] = value
                }
            }

            if (method == "OPTIONS") {
                sendHttpJsonResponse(output, 200, JSONObject().put("ok", true))
                socket.close()
                return
            }

            // Lightweight discovery ping for Desktop "Auto-Find Phone"
            if (path.startsWith("/discover")) {
                val body = JSONObject()
                    .put("app", "vibrator-android")
                    .put("version", 2)
                sendHttpJsonResponse(output, 200, body)
                socket.close()
                return
            }

            // Phase 3: Wireless file upload endpoint (POST /upload-file?pin=XXXX&bundleId=...&fileName=...)
            if (path.startsWith("/upload-file") && method == "POST") {
                val providedPin = parseQueryParam(path, "pin")
                if (providedPin != expectedPin) {
                    sendHttpJsonResponse(
                        output,
                        401,
                        JSONObject().put("ok", false).put("error", "Invalid 4-digit PIN"),
                    )
                    socket.close()
                    return
                }
                val rawBundleId = parseQueryParam(path, "bundleId").orEmpty()
                val rawFileName = parseQueryParam(path, "fileName").orEmpty()
                val bundleId = sanitizeSegment(rawBundleId)
                val fileName = sanitizeSegment(rawFileName)
                val contentLength = headers["content-length"]?.toLongOrNull() ?: -1L

                if (bundleId.isEmpty() || fileName.isEmpty() || contentLength < 0L) {
                    sendHttpJsonResponse(
                        output,
                        400,
                        JSONObject().put("ok", false).put("error", "Missing bundleId, fileName, or Content-Length"),
                    )
                    socket.close()
                    return
                }

                socket.soTimeout = 60_000 // Allow 60s per read during large video file transfer
                val handler = onReceiveFile
                val result = if (handler != null) {
                    handler(bundleId, fileName, contentLength, input)
                } else {
                    Result.failure(IllegalStateException("File receiver not configured"))
                }

                if (result.isSuccess) {
                    sendHttpJsonResponse(
                        output,
                        200,
                        JSONObject().put("ok", true).put("bundleId", bundleId).put("fileName", fileName),
                    )
                } else {
                    sendHttpJsonResponse(
                        output,
                        500,
                        JSONObject()
                            .put("ok", false)
                            .put("error", result.exceptionOrNull()?.message ?: "Upload failed"),
                    )
                }
                socket.close()
                return
            }

            // Phase 3: Complete bundle upload & optionally trigger load on Android (POST /finish-bundle?pin=XXXX&bundleId=...&autoLoad=...)
            if (path.startsWith("/finish-bundle") && method == "POST") {
                val providedPin = parseQueryParam(path, "pin")
                if (providedPin != expectedPin) {
                    sendHttpJsonResponse(
                        output,
                        401,
                        JSONObject().put("ok", false).put("error", "Invalid 4-digit PIN"),
                    )
                    socket.close()
                    return
                }
                val bundleId = sanitizeSegment(parseQueryParam(path, "bundleId").orEmpty())
                val autoLoadParam = parseQueryParam(path, "autoLoad")?.lowercase()
                val autoLoad = autoLoadParam != "false" && autoLoadParam != "0"
                if (bundleId.isEmpty()) {
                    sendHttpJsonResponse(
                        output,
                        400,
                        JSONObject().put("ok", false).put("error", "Missing bundleId"),
                    )
                    socket.close()
                    return
                }
                val handler = onFinishBundleUpload
                val result = if (handler != null) {
                    handler(bundleId, autoLoad)
                } else {
                    Result.success(Unit)
                }
                if (result.isSuccess) {
                    sendHttpJsonResponse(output, 200, JSONObject().put("ok", true).put("bundleId", bundleId))
                } else {
                    sendHttpJsonResponse(
                        output,
                        500,
                        JSONObject()
                            .put("ok", false)
                            .put("error", result.exceptionOrNull()?.message ?: "Failed to finalize bundle"),
                    )
                }
                socket.close()
                return
            }

            // Phase 3: Get bundle file manifest for Phone -> PC sync (GET /bundle-manifest?pin=XXXX&bundleId=...)
            if (path.startsWith("/bundle-manifest") && method == "GET") {
                val providedPin = parseQueryParam(path, "pin")
                if (providedPin != expectedPin) {
                    sendHttpJsonResponse(
                        output,
                        401,
                        JSONObject().put("ok", false).put("error", "Invalid 4-digit PIN"),
                    )
                    socket.close()
                    return
                }
                val bundleId = sanitizeSegment(parseQueryParam(path, "bundleId").orEmpty())
                if (bundleId.isEmpty()) {
                    sendHttpJsonResponse(
                        output,
                        400,
                        JSONObject().put("ok", false).put("error", "Missing bundleId"),
                    )
                    socket.close()
                    return
                }
                val handler = onGetBundleManifest
                val result = if (handler != null) {
                    handler(bundleId)
                } else {
                    Result.failure(IllegalStateException("Manifest provider not configured"))
                }
                if (result.isSuccess) {
                    sendHttpJsonResponse(
                        output,
                        200,
                        JSONObject()
                            .put("ok", true)
                            .put("bundleId", bundleId)
                            .put("files", result.getOrThrow()),
                    )
                } else {
                    sendHttpJsonResponse(
                        output,
                        404,
                        JSONObject()
                            .put("ok", false)
                            .put("error", result.exceptionOrNull()?.message ?: "Bundle not found"),
                    )
                }
                socket.close()
                return
            }

            // Phase 3: Download a file from Phone Library to PC (GET /download-file?pin=XXXX&bundleId=...&fileName=...)
            if (path.startsWith("/download-file") && method == "GET") {
                val providedPin = parseQueryParam(path, "pin")
                if (providedPin != expectedPin) {
                    sendHttpJsonResponse(
                        output,
                        401,
                        JSONObject().put("ok", false).put("error", "Invalid 4-digit PIN"),
                    )
                    socket.close()
                    return
                }
                val bundleId = sanitizeSegment(parseQueryParam(path, "bundleId").orEmpty())
                val fileName = sanitizeSegment(parseQueryParam(path, "fileName").orEmpty())
                if (bundleId.isEmpty() || fileName.isEmpty()) {
                    sendHttpJsonResponse(
                        output,
                        400,
                        JSONObject().put("ok", false).put("error", "Missing bundleId or fileName"),
                    )
                    socket.close()
                    return
                }
                socket.soTimeout = 60_000
                val handler = onOpenBundleFileStream
                val result = if (handler != null) {
                    handler(bundleId, fileName)
                } else {
                    Result.failure(IllegalStateException("Download provider not configured"))
                }
                if (result.isFailure) {
                    sendHttpJsonResponse(
                        output,
                        404,
                        JSONObject()
                            .put("ok", false)
                            .put("error", result.exceptionOrNull()?.message ?: "File not found"),
                    )
                    socket.close()
                    return
                }
                val (fileSize, fileInput) = result.getOrThrow()
                fileInput.use { stream ->
                    val respHeaders = buildString {
                        append("HTTP/1.1 200 OK\r\n")
                        append("Content-Type: application/octet-stream\r\n")
                        append("Access-Control-Allow-Origin: *\r\n")
                        if (fileSize >= 0L) {
                            append("Content-Length: $fileSize\r\n")
                        }
                        append("Connection: close\r\n\r\n")
                    }.toByteArray(Charsets.UTF_8)
                    output.write(respHeaders)
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                    }
                    output.flush()
                }
                socket.close()
                return
            }

            val wsKey = headers["sec-websocket-key"]
            if (wsKey.isNullOrBlank()) {
                socket.close()
                return
            }

            // Complete RFC-6455 WebSocket handshake
            val acceptHash = MessageDigest.getInstance("SHA-1")
                .digest((wsKey + WS_GUID).toByteArray(Charsets.UTF_8))
            val acceptKey = Base64.encodeToString(acceptHash, Base64.NO_WRAP)

            val handshakeResponse = buildString {
                append("HTTP/1.1 101 Switching Protocols\r\n")
                append("Upgrade: websocket\r\n")
                append("Connection: Upgrade\r\n")
                append("Sec-WebSocket-Accept: $acceptKey\r\n\r\n")
            }.toByteArray(Charsets.UTF_8)

            output.write(handshakeResponse)
            output.flush()

            // Verify 4-digit PIN from query string (?pin=XXXX)
            val providedPin = parseQueryParam(path, "pin")
            if (providedPin != expectedPin) {
                val errJson = JSONObject()
                    .put("type", "AUTH_ERROR")
                    .put("message", "Invalid 4-digit PIN. Please check the PIN shown on the Android app.")
                    .toString()
                writeFrameDirect(output, 0x1, errJson.toByteArray(Charsets.UTF_8))
                writeFrameDirect(output, 0x8, byteArrayOf(0x0F, 0xA1.toByte())) // 4001 close code
                socket.close()
                return
            }

            // Authenticated! Remove read timeout so idle connection uses 0% CPU
            socket.soTimeout = 0

            // Replace any previous client connection cleanly
            synchronized(writeLock) {
                runCatching { activeClientSocket?.close() }
                activeClientSocket = socket
                activeOutput = output
            }

            val clientIp = socket.inetAddress?.hostAddress ?: "Desktop"
            _status.value = _status.value.copy(
                connectedClientIp = clientIp,
                localIps = findLocalIpv4Addresses().ifEmpty { _status.value.localIps },
                error = null,
            )

            // Send immediate AUTH_OK and trigger full state snapshot
            val welcome = JSONObject()
                .put("type", "AUTH_OK")
                .put("clientIp", clientIp)
            sendTextFrame(welcome.toString())
            onCommand(RemoteSyncCommand.RequestState)

            // Read incoming WebSocket frames
            while (scope.isActive && !socket.isClosed) {
                val frame = readFrame(input) ?: break
                when (frame.opcode) {
                    0x1 -> { // Text frame
                        val text = String(frame.payload, Charsets.UTF_8)
                        handleIncomingJson(text)
                    }
                    0x8 -> { // Close frame
                        break
                    }
                    0x9 -> { // Ping -> reply with Pong
                        synchronized(writeLock) {
                            activeOutput?.let { out ->
                                runCatching { writeFrameDirect(out, 0xA, frame.payload) }
                            }
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // Socket closed or disconnected
        } finally {
            synchronized(writeLock) {
                if (activeClientSocket === socket) {
                    activeClientSocket = null
                    activeOutput = null
                    _status.value = _status.value.copy(connectedClientIp = null)
                }
            }
            runCatching { socket.close() }
        }
    }

    private fun handleIncomingJson(rawText: String) {
        val obj = runCatching { JSONObject(rawText) }.getOrNull() ?: return
        when (obj.optString("type")) {
            "COMMAND_PLAY" -> {
                val timeMs = if (obj.has("timeMs") && !obj.isNull("timeMs")) obj.optLong("timeMs") else null
                onCommand(RemoteSyncCommand.Play(timeMs))
            }
            "COMMAND_PAUSE" -> {
                val timeMs = if (obj.has("timeMs") && !obj.isNull("timeMs")) obj.optLong("timeMs") else null
                onCommand(RemoteSyncCommand.Pause(timeMs))
            }
            "COMMAND_SEEK" -> {
                if (obj.has("timeMs")) {
                    onCommand(RemoteSyncCommand.Seek(obj.optLong("timeMs", 0L).coerceAtLeast(0L)))
                }
            }
            "COMMAND_SPEED" -> {
                val speed = obj.optDouble("speed", 1.0).toFloat().coerceIn(0.25f, 2.0f)
                onCommand(RemoteSyncCommand.SetSpeed(speed))
            }
            "COMMAND_SELECT_ALGO" -> {
                val algo = obj.optString("algorithm").trim().lowercase()
                if (algo.isNotEmpty()) {
                    onCommand(RemoteSyncCommand.SelectAlgorithm(algo))
                }
            }
            "COMMAND_TOGGLE_EVENT" -> {
                val eventId = obj.optString("eventId").trim()
                if (eventId.isNotEmpty()) {
                    onCommand(RemoteSyncCommand.ToggleEvent(eventId))
                }
            }
            "COMMAND_SET_SELECTED_EVENTS" -> {
                val arr: JSONArray? = obj.optJSONArray("eventIds")
                val ids = linkedSetOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val id = arr.optString(i).trim()
                        if (id.isNotEmpty()) ids += id
                    }
                }
                onCommand(RemoteSyncCommand.SetSelectedEvents(ids))
            }
            "COMMAND_LOAD_BUNDLE" -> {
                val bundleId = sanitizeSegment(obj.optString("bundleId").trim())
                if (bundleId.isNotEmpty()) {
                    onCommand(RemoteSyncCommand.LoadBundle(bundleId))
                }
            }
            "COMMAND_CLEAR_MEDIA" -> {
                onCommand(RemoteSyncCommand.ClearMedia)
            }
            "COMMAND_DESKTOP_LIBRARY" -> {
                val arr: JSONArray? = obj.optJSONArray("bundles")
                val list = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val b = sanitizeSegment(arr.optString(i).trim())
                        if (b.isNotEmpty()) list += b
                    }
                }
                onCommand(RemoteSyncCommand.UpdateDesktopLibrary(list))
            }
            "REQUEST_STATE" -> {
                onCommand(RemoteSyncCommand.RequestState)
            }
        }
    }

    private fun sendHttpJsonResponse(output: OutputStream, statusCode: Int, json: JSONObject) {
        val statusText = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            else -> "Internal Server Error"
        }
        val bodyBytes = json.toString().toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        output.write(headers)
        output.write(bodyBytes)
        output.flush()
    }

    private fun sanitizeSegment(raw: String): String {
        return raw.trim()
            .replace('\\', '_')
            .replace('/', '_')
            .replace("..", "_")
    }

    private fun sendTextFrame(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        synchronized(writeLock) {
            val out = activeOutput ?: return
            try {
                writeFrameDirect(out, 0x1, bytes)
            } catch (_: Throwable) {
                runCatching { activeClientSocket?.close() }
                activeClientSocket = null
                activeOutput = null
                _status.value = _status.value.copy(connectedClientIp = null)
            }
        }
    }

    private fun writeFrameDirect(out: OutputStream, opcode: Int, payload: ByteArray) {
        val b0 = 0x80 or (opcode and 0x0F)
        out.write(b0)
        val len = payload.size
        when {
            len <= 125 -> {
                out.write(len)
            }
            len <= 65535 -> {
                out.write(126)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(127)
                val longLen = len.toLong()
                for (shift in 56 downTo 0 step 8) {
                    out.write(((longLen ushr shift) and 0xFFL).toInt())
                }
            }
        }
        out.write(payload)
        out.flush()
    }

    private class WsFrame(val opcode: Int, val payload: ByteArray)

    private fun readFrame(input: InputStream): WsFrame? {
        val b0 = input.read()
        if (b0 == -1) return null
        val b1 = input.read()
        if (b1 == -1) return null

        val opcode = b0 and 0x0F
        val masked = (b1 and 0x80) != 0
        var payloadLen = (b1 and 0x7F).toLong()

        if (payloadLen == 126L) {
            val hi = input.read()
            val lo = input.read()
            if (hi == -1 || lo == -1) return null
            payloadLen = ((hi and 0xFF) shl 8 or (lo and 0xFF)).toLong()
        } else if (payloadLen == 127L) {
            payloadLen = 0L
            repeat(8) {
                val b = input.read()
                if (b == -1) return null
                payloadLen = (payloadLen shl 8) or (b.toLong() and 0xFFL)
            }
        }

        if (payloadLen < 0L || payloadLen > MAX_FRAME_BYTES) {
            throw IllegalArgumentException("Frame exceeds maximum allowed control size")
        }

        val maskKey = ByteArray(4)
        if (masked) {
            readFully(input, maskKey)
        }

        val payload = ByteArray(payloadLen.toInt())
        readFully(input, payload)

        if (masked) {
            for (i in payload.indices) {
                payload[i] = (payload[i].toInt() xor maskKey[i and 3].toInt()).toByte()
            }
        }

        return WsFrame(opcode, payload)
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw EOFException()
            offset += read
        }
    }

    private fun readHttpHeaders(input: InputStream): List<String> {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        var totalBytes = 0
        while (totalBytes < 8192) {
            val b = input.read()
            if (b == -1) break
            totalBytes++
            if (b == '\r'.code) continue
            if (b == '\n'.code) {
                val line = sb.toString()
                sb.setLength(0)
                if (line.isEmpty()) break
                lines += line
            } else {
                sb.append(b.toChar())
            }
        }
        return lines
    }

    private fun parseQueryParam(path: String, key: String): String? {
        val qIndex = path.indexOf('?')
        if (qIndex < 0 || qIndex == path.lastIndex) return null
        val query = path.substring(qIndex + 1)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq > 0) {
                val k = pair.substring(0, eq)
                val v = pair.substring(eq + 1)
                if (k == key) {
                    return runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
                }
            }
        }
        return null
    }
}
