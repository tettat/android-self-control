package com.control.app.adb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Replacement for ShizukuExecutor. Uses libadb-android to connect to the device's
 * own ADB daemon via localhost wireless debugging, then executes shell commands
 * through ADB streams.
 *
 * Public method signatures match the old ShizukuExecutor for seamless AgentEngine integration.
 */
class AdbExecutor(private val context: Context) {

    companion object {
        private const val TAG = "AdbExecutor"
        private const val SCREENSHOT_PATH = "/data/local/tmp/ctrl_screenshot.png"
        private const val LOCALHOST = "127.0.0.1"
        private const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000
        private const val MAX_RECONNECT_ATTEMPTS = 2
        private const val DEFAULT_SHELL_TIMEOUT_MS = 15_000L
        private const val SCREENSHOT_TIMEOUT_MS = 20_000L
        private const val SCREENSHOT_MAX_ATTEMPTS = 3
        private const val SCREENSHOT_RETRY_DELAY_MS = 400L
        private const val UI_DUMP_COMMAND_TIMEOUT_MS = 7_000L
        private const val UI_DUMP_READ_TIMEOUT_MS = 5_000L
    }

    private val connectionManager: AdbConnectionManagerImpl by lazy {
        AdbConnectionManagerImpl.getInstance(context)
    }

    val mdnsDiscovery: AdbMdnsDiscovery by lazy {
        AdbMdnsDiscovery(context)
    }

    @Volatile
    var lastConnectedPort: Int = 0

    /**
     * Returns true if currently connected to the local ADB daemon.
     */
    fun isConnected(): Boolean {
        return try {
            connectionManager.isConnected
        } catch (e: Exception) {
            Log.w(TAG, "isConnected check failed: ${e.message}")
            false
        }
    }

    /**
     * Pair with the ADB daemon using the wireless debugging pairing code.
     * This only needs to be done once; subsequent connections don't need pairing.
     */
    suspend fun pair(host: String, port: Int, pairingCode: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.d(TAG, "Pairing with $host:$port")
                val success = connectionManager.pair(host, port, pairingCode)
                if (!success) {
                    throw RuntimeException("Pairing returned false. Check the pairing code and port.")
                }
                Log.d(TAG, "Pairing successful")
                true
            }
        }

    /**
     * Connect to the local ADB daemon on the given wireless debugging port.
     */
    suspend fun connect(port: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Connecting to $LOCALHOST:$port")
            connectionManager.connect(LOCALHOST, port)
            if (!connectionManager.isConnected) {
                throw RuntimeException("Connection established but isConnected returned false")
            }
            lastConnectedPort = port
            Log.d(TAG, "Connected successfully on port $port")
            true
        }
    }

    /**
     * Try to reconnect using the last known port. Returns true if reconnected.
     */
    private fun tryReconnect(): Boolean {
        val port = lastConnectedPort
        if (port == 0) {
            Log.w(TAG, "Cannot reconnect: no last connected port")
            return false
        }
        return try {
            Log.d(TAG, "Attempting auto-reconnect to $LOCALHOST:$port")
            // Disconnect first to clean up stale state
            try { connectionManager.disconnect() } catch (_: Exception) {}
            connectionManager.connect(LOCALHOST, port)
            val connected = connectionManager.isConnected
            Log.d(TAG, "Auto-reconnect result: $connected")
            connected
        } catch (e: Exception) {
            Log.w(TAG, "Auto-reconnect failed: ${e.message}")
            false
        }
    }

    /**
     * Open an ADB stream with automatic reconnection on connection errors.
     */
    private fun openStreamWithReconnect(destination: String): AdbStream {
        for (attempt in 0..MAX_RECONNECT_ATTEMPTS) {
            try {
                return connectionManager.openStream(destination)
            } catch (e: Exception) {
                val msg = e.message?.lowercase() ?: ""
                val isConnectionError = msg.contains("connect") ||
                        msg.contains("closed") ||
                        msg.contains("not connected") ||
                        msg.contains("stream") ||
                        !isConnected()

                if (isConnectionError && attempt < MAX_RECONNECT_ATTEMPTS) {
                    Log.w(TAG, "Stream open failed (attempt ${attempt + 1}), trying reconnect: ${e.message}")
                    if (!tryReconnect()) {
                        throw RuntimeException("ADB connection lost and reconnect failed", e)
                    }
                    // Retry after successful reconnect
                } else {
                    throw e
                }
            }
        }
        throw RuntimeException("Failed to open stream after $MAX_RECONNECT_ATTEMPTS reconnect attempts")
    }

    /**
     * Disconnect from the ADB daemon.
     */
    fun disconnect() {
        try {
            connectionManager.disconnect()
            Log.d(TAG, "Disconnected")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disconnect: ${e.message}")
        }
    }

    /**
     * Execute a shell command via ADB and return stdout as a string.
     * Automatically reconnects if the connection was lost.
     */
    suspend fun executeCommand(
        command: String,
        timeoutMs: Long = DEFAULT_SHELL_TIMEOUT_MS
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Executing: ${command.take(100)} (timeout=${timeoutMs}ms)")
            val stream = openStreamWithReconnect("shell:$command")
            try {
                readStreamFully(stream, timeoutMs)
            } finally {
                runCatching { stream.close() }
            }
        }
    }

    /**
     * Read all data from an ADB stream until it closes.
     */
    private fun readStreamFully(stream: AdbStream, timeoutMs: Long): String {
        val baos = ByteArrayOutputStream()
        val inputStream = stream.openInputStream()
        runWithTimeout(timeoutMs, onTimeout = {
            runCatching { inputStream.close() }
            runCatching { stream.close() }
        }) {
            val buffer = ByteArray(4096)
            while (true) {
                val bytesRead = try {
                    inputStream.read(buffer)
                } catch (e: Exception) {
                    break
                }
                if (bytesRead == -1) break
                baos.write(buffer, 0, bytesRead)
            }
        }
        return baos.toString("UTF-8")
    }

    /**
     * Read binary data from an ADB stream until it closes.
     */
    private fun readStreamBytes(stream: AdbStream, timeoutMs: Long): ByteArray {
        val baos = ByteArrayOutputStream()
        val inputStream = stream.openInputStream()
        runWithTimeout(timeoutMs, onTimeout = {
            runCatching { inputStream.close() }
            runCatching { stream.close() }
        }) {
            val buffer = ByteArray(8192)
            while (true) {
                val bytesRead = try {
                    inputStream.read(buffer)
                } catch (e: Exception) {
                    break
                }
                if (bytesRead == -1) break
                baos.write(buffer, 0, bytesRead)
            }
        }
        return baos.toByteArray()
    }

    private fun <T> runWithTimeout(
        timeoutMs: Long,
        onTimeout: () -> Unit = {},
        block: () -> T
    ): T {
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<T> { block() }
        return try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            onTimeout()
            future.cancel(true)
            throw RuntimeException("ADB 命令超时 (${timeoutMs}ms)", e)
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Fetch raw PNG bytes from device (direct stream or temp-file fallback).
     * May return empty array on transient failure.
     */
    private fun fetchScreenshotBytes(): ByteArray {
        return try {
            val stream = openStreamWithReconnect("shell:screencap -p")
            val bytes = readStreamBytes(stream, SCREENSHOT_TIMEOUT_MS)
            stream.close()
            bytes
        } catch (e: Exception) {
            Log.w(TAG, "Direct screencap stream failed, falling back to temp file: ${e.message}")
            try {
                screenshotViaFile()
            } catch (e2: Exception) {
                Log.w(TAG, "Screenshot via file also failed: ${e2.message}")
                ByteArray(0)
            }
        }
    }

    /**
     * Take a screenshot and return it as a Bitmap, optionally scaled down.
     *
     * The screenshot is captured via "screencap -p" piped through the ADB stream,
     * or falls back to saving to a temp file and reading it via "cat".
     * Retries up to SCREENSHOT_MAX_ATTEMPTS when the device returns empty data.
     */
    suspend fun takeScreenshot(scale: Float = 0.5f): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            var pngBytes = fetchScreenshotBytes()
            var attempt = 1
            while (pngBytes.isEmpty() && attempt < SCREENSHOT_MAX_ATTEMPTS) {
                Log.w(TAG, "Screenshot returned empty data (attempt $attempt/$SCREENSHOT_MAX_ATTEMPTS), retrying in ${SCREENSHOT_RETRY_DELAY_MS}ms...")
                delay(SCREENSHOT_RETRY_DELAY_MS)
                attempt++
                pngBytes = fetchScreenshotBytes()
            }
            if (pngBytes.isEmpty()) {
                throw RuntimeException("Screenshot returned empty data after $SCREENSHOT_MAX_ATTEMPTS attempts")
            }

            val fullBitmap = BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size)
                ?: throw RuntimeException("Failed to decode screenshot PNG (${pngBytes.size} bytes)")

            if (scale < 1.0f && scale > 0f) {
                val scaledWidth = (fullBitmap.width * scale).toInt().coerceAtLeast(1)
                val scaledHeight = (fullBitmap.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(fullBitmap, scaledWidth, scaledHeight, true)
                fullBitmap.recycle()
                scaled
            } else {
                fullBitmap
            }
        }
    }

    /**
     * Fallback screenshot method: saves to a temp file and reads it back via cat.
     */
    private fun screenshotViaFile(): ByteArray {
        // Take screenshot to temp file
        val saveStream = openStreamWithReconnect("shell:screencap -p $SCREENSHOT_PATH")
        readStreamFully(saveStream, SCREENSHOT_TIMEOUT_MS)
        saveStream.close()

        // Read the file contents back via cat
        val catStream = openStreamWithReconnect("shell:cat $SCREENSHOT_PATH")
        val bytes = readStreamBytes(catStream, SCREENSHOT_TIMEOUT_MS)
        catStream.close()

        // Clean up temp file
        try {
            val rmStream = openStreamWithReconnect("shell:rm -f $SCREENSHOT_PATH")
            readStreamFully(rmStream, DEFAULT_SHELL_TIMEOUT_MS)
            rmStream.close()
        } catch (_: Exception) { }

        return bytes
    }

    suspend fun tap(x: Int, y: Int): Result<String> {
        Log.d(TAG, "Tap: ($x, $y)")
        return executeCommand("input tap $x $y")
    }

    suspend fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        duration: Int = 300
    ): Result<String> {
        Log.d(TAG, "Swipe: ($startX,$startY) -> ($endX,$endY) duration=$duration")
        return executeCommand("input swipe $startX $startY $endX $endY $duration")
    }

    suspend fun inputText(text: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Input text: ${text.take(50)}")
            if (text.isEmpty()) return@runCatching ""

            val imeList = try {
                executeCommand("ime list -s").getOrDefault("")
            } catch (_: Exception) {
                ""
            }

            val hasAdbKeyboard = imeList.contains("com.android.adbkeyboard")

            if (hasAdbKeyboard) {
                inputTextWithAdbKeyboard(text)
            } else {
                val isAscii = text.all { it.code in 0..127 }
                if (isAscii) {
                    executeCommand("input text \"${escapeAsciiInputText(text)}\"").getOrThrow()
                } else {
                    Log.w(TAG, "Non-ASCII without ADBKeyboard, using clipboard fallback")
                    inputTextViaClipboard(text)
                }
            }
        }
    }

    private suspend fun inputTextWithAdbKeyboard(text: String): String {
        executeCommand("ime set com.android.adbkeyboard/.AdbIME").getOrThrow()

        val message = if (text.all { it.code in 0..127 }) {
            "am broadcast -a ADB_INPUT_TEXT --es msg ${shellSingleQuote(text)}"
        } else {
            val encoded = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            "am broadcast -a ADB_INPUT_B64 --es msg '$encoded'"
        }

        executeCommand(message).getOrThrow()
        return "input via adb keyboard: $text"
    }

    private suspend fun inputTextViaClipboard(text: String): String {
        val clipboardCommands = listOf(
            "cmd clipboard set text ${shellSingleQuote(text)}",
            "cmd clipboard set ${shellSingleQuote(text)}",
            "am broadcast -a clipper.set -e text ${shellSingleQuote(text)}"
        )

        var lastFailure: Throwable? = null
        for (command in clipboardCommands) {
            val output = runCatching { executeCommand(command).getOrThrow() }
            if (output.isFailure) {
                lastFailure = output.exceptionOrNull()
                continue
            }

            if (looksLikeShellFailure(output.getOrNull().orEmpty())) {
                continue
            }

            runCatching {
                executeCommand("input keyevent 279").getOrThrow()
            }.recoverCatching {
                executeCommand("input keyevent --meta 28672 50").getOrThrow()
            }.getOrThrow()

            return "input via clipboard: $text"
        }

        throw RuntimeException(
            "Unicode text input requires ADBKeyboard or a clipboard service that supports shell paste",
            lastFailure
        )
    }

    private fun shellSingleQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    private fun escapeAsciiInputText(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("'", "\\'")
        .replace(" ", "%s")
        .replace("&", "\\&")
        .replace("<", "\\<")
        .replace(">", "\\>")
        .replace("|", "\\|")
        .replace(";", "\\;")
        .replace("(", "\\(")
        .replace(")", "\\)")

    private fun looksLikeShellFailure(output: String): Boolean {
        val normalized = output.lowercase()
        return normalized.contains("unknown command") ||
            normalized.contains("usage:") ||
            normalized.contains("not found") ||
            normalized.contains("exception") ||
            normalized.contains("error:")
    }

    suspend fun pressBack(): Result<String> = executeCommand("input keyevent 4")

    suspend fun pressHome(): Result<String> = executeCommand("input keyevent 3")

    suspend fun launchApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Launch app: $packageName")
            val launchActivity = executeCommand(
                "cmd package resolve-activity --brief $packageName | tail -n 1",
                timeoutMs = 8_000L
            ).getOrThrow().trim()

            if (launchActivity.contains("/")) {
                val startResult = executeCommand(
                    "am start -n $launchActivity 2>&1",
                    timeoutMs = 8_000L
                ).getOrThrow()
                if (!looksLikeShellFailure(startResult) && !startResult.contains("Error", ignoreCase = true)) {
                    return@runCatching startResult
                }
            }

            val monkeyResult = executeCommand(
                "monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>&1",
                timeoutMs = 12_000L
            ).getOrThrow()
            if (monkeyResult.contains("No activities found") || looksLikeShellFailure(monkeyResult)) {
                throw RuntimeException("Cannot launch $packageName: ${monkeyResult.trim()}")
            }
            monkeyResult
        }
    }

    suspend fun dumpUiHierarchy(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val directOutput = executeCommand(
                "uiautomator dump /dev/tty",
                timeoutMs = UI_DUMP_COMMAND_TIMEOUT_MS
            ).getOrThrow()
            extractUiHierarchyXml(directOutput)?.let { return@runCatching it }

            executeCommand(
                "uiautomator dump /data/local/tmp/ui_dump.xml 2>&1",
                timeoutMs = UI_DUMP_COMMAND_TIMEOUT_MS
            ).getOrThrow()
            val xml = executeCommand(
                "cat /data/local/tmp/ui_dump.xml",
                timeoutMs = UI_DUMP_READ_TIMEOUT_MS
            ).getOrThrow()
            executeCommand("rm -f /data/local/tmp/ui_dump.xml")
            extractUiHierarchyXml(xml) ?: xml
        }
    }

    private fun extractUiHierarchyXml(output: String): String? {
        val start = output.indexOf("<?xml")
        val end = output.lastIndexOf("</hierarchy>")
        if (start == -1 || end == -1 || end <= start) return null
        return output.substring(start, end + "</hierarchy>".length)
    }

    suspend fun sendKeyEvent(keyCode: Int): Result<String> =
        executeCommand("input keyevent $keyCode")

    suspend fun scrollDown(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (width, height) = getScreenResolutionInternal()
            val centerX = width / 2
            val startY = (height * 0.7).toInt()
            val endY = (height * 0.3).toInt()
            executeCommand("input swipe $centerX $startY $centerX $endY 300").getOrThrow()
        }
    }

    suspend fun scrollUp(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val (width, height) = getScreenResolutionInternal()
            val centerX = width / 2
            val startY = (height * 0.3).toInt()
            val endY = (height * 0.7).toInt()
            executeCommand("input swipe $centerX $startY $centerX $endY 300").getOrThrow()
        }
    }

    suspend fun getScreenResolution(): Result<Pair<Int, Int>> = withContext(Dispatchers.IO) {
        runCatching { getScreenResolutionInternal() }
    }

    private suspend fun getScreenResolutionInternal(): Pair<Int, Int> {
        val output = executeCommand("wm size").getOrThrow().trim()
        val regex = Regex("""(\d+)x(\d+)""")
        val matches = regex.findAll(output).toList()
        val match = matches.lastOrNull()
            ?: throw RuntimeException("Failed to parse screen resolution from: $output")
        val width = match.groupValues[1].toInt()
        val height = match.groupValues[2].toInt()
        return Pair(width, height)
    }
}
