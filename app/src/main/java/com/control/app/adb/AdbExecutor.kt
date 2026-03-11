package com.control.app.adb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

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
    suspend fun executeCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Executing: ${command.take(100)}")
            val stream = openStreamWithReconnect("shell:$command")
            val output = readStreamFully(stream)
            stream.close()
            output
        }
    }

    /**
     * Read all data from an ADB stream until it closes.
     */
    private fun readStreamFully(stream: AdbStream): String {
        val baos = ByteArrayOutputStream()
        val inputStream = stream.openInputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val bytesRead = try {
                inputStream.read(buffer)
            } catch (e: Exception) {
                // Stream closed or EOF
                break
            }
            if (bytesRead == -1) break
            baos.write(buffer, 0, bytesRead)
        }
        return baos.toString("UTF-8")
    }

    /**
     * Read binary data from an ADB stream until it closes.
     */
    private fun readStreamBytes(stream: AdbStream): ByteArray {
        val baos = ByteArrayOutputStream()
        val inputStream = stream.openInputStream()
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
        return baos.toByteArray()
    }

    /**
     * Take a screenshot and return it as a Bitmap, optionally scaled down.
     *
     * The screenshot is captured via "screencap -p" piped through the ADB stream,
     * or falls back to saving to a temp file and reading it via "cat".
     */
    suspend fun takeScreenshot(scale: Float = 0.5f): Result<Bitmap> = withContext(Dispatchers.IO) {
        runCatching {
            // Strategy: use screencap -p and read the PNG directly from the ADB stream.
            // This avoids filesystem permission issues.
            val pngBytes = try {
                val stream = openStreamWithReconnect("shell:screencap -p")
                val bytes = readStreamBytes(stream)
                stream.close()
                bytes
            } catch (e: Exception) {
                Log.w(TAG, "Direct screencap stream failed, falling back to temp file: ${e.message}")
                // Fallback: save to file and read via cat
                screenshotViaFile()
            }

            if (pngBytes.isEmpty()) {
                throw RuntimeException("Screenshot returned empty data")
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
        readStreamFully(saveStream)
        saveStream.close()

        // Read the file contents back via cat
        val catStream = openStreamWithReconnect("shell:cat $SCREENSHOT_PATH")
        val bytes = readStreamBytes(catStream)
        catStream.close()

        // Clean up temp file
        try {
            val rmStream = openStreamWithReconnect("shell:rm -f $SCREENSHOT_PATH")
            readStreamFully(rmStream)
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

            val imeList = try {
                executeCommand("ime list -s").getOrDefault("")
            } catch (_: Exception) {
                ""
            }

            val hasAdbKeyboard = imeList.contains("com.android.adbkeyboard")

            if (hasAdbKeyboard) {
                executeCommand("ime set com.android.adbkeyboard/.AdbIME").getOrThrow()
                val escapedText = text.replace("'", "'\\''")
                executeCommand("am broadcast -a ADB_INPUT_TEXT --es msg '$escapedText'").getOrThrow()
            } else {
                val isAscii = text.all { it.code in 0..127 }
                if (isAscii) {
                    val escaped = text
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
                    executeCommand("input text \"$escaped\"").getOrThrow()
                } else {
                    Log.w(TAG, "Non-ASCII without ADBKeyboard, using clipboard fallback")
                    val escapedClip = text.replace("'", "'\\''")
                    executeCommand("am broadcast -a clipper.set -e text '$escapedClip'").getOrThrow()
                    executeCommand("input keyevent --meta 28672 50").getOrThrow()
                    "input via clipboard: $text"
                }
            }
        }
    }

    suspend fun pressBack(): Result<String> = executeCommand("input keyevent 4")

    suspend fun pressHome(): Result<String> = executeCommand("input keyevent 3")

    suspend fun launchApp(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Launch app: $packageName")
            val result = executeCommand(
                "monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>&1"
            ).getOrThrow()

            if (result.contains("No activities found") || result.contains("Error")) {
                val launchActivity = executeCommand(
                    "cmd package resolve-activity --brief $packageName | tail -n 1"
                ).getOrThrow().trim()
                if (launchActivity.contains("/")) {
                    executeCommand("am start -n $launchActivity").getOrThrow()
                } else {
                    throw RuntimeException("Cannot find launcher activity for $packageName")
                }
            } else {
                result
            }
        }
    }

    suspend fun dumpUiHierarchy(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            executeCommand("uiautomator dump /data/local/tmp/ui_dump.xml 2>&1").getOrThrow()
            val xml = executeCommand("cat /data/local/tmp/ui_dump.xml").getOrThrow()
            executeCommand("rm -f /data/local/tmp/ui_dump.xml")
            xml
        }
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
