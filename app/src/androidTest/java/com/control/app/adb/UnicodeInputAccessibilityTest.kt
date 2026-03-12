package com.control.app.adb

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.control.app.ControlApp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader

@RunWith(AndroidJUnit4::class)
class UnicodeInputAccessibilityTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val serviceComponent =
        "com.control.app.debug/com.control.app.service.UiTreeAccessibilityService"

    private var originalEnabledServices: String = ""
    private var originalAccessibilityEnabled: String = ""

    @Before
    fun setUp() {
        originalEnabledServices = execShell("settings get secure enabled_accessibility_services")
            .trim()
        originalAccessibilityEnabled = execShell("settings get secure accessibility_enabled")
            .trim()

        execShell("settings delete secure enabled_accessibility_services")
        execShell("settings put secure accessibility_enabled 0")
    }

    @After
    fun tearDown() {
        if (originalEnabledServices.isBlank() || originalEnabledServices == "null") {
            execShell("settings delete secure enabled_accessibility_services")
        } else {
            execShell(
                "settings put secure enabled_accessibility_services '$originalEnabledServices'"
            )
        }

        val accessibilityEnabledValue = originalAccessibilityEnabled
            .takeUnless { it.isBlank() || it == "null" }
            ?: "0"
        execShell("settings put secure accessibility_enabled $accessibilityEnabledValue")
    }

    @Test
    fun unicodeInputAutoEnablesAccessibilityService() = runBlocking {
        val app = instrumentation.targetContext.applicationContext as ControlApp
        app.ensureAdbReady()
        waitUntil(timeoutMs = 15_000L) { app.adbExecutor.isConnected() }

        app.adbExecutor.inputText("你好")

        val enabledServices = execShell("settings get secure enabled_accessibility_services").trim()
        assertTrue(
            "Expected accessibility service to be enabled, but was: $enabledServices",
            enabledServices.contains(serviceComponent)
        )
    }

    private fun waitUntil(timeoutMs: Long, predicate: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(250L)
        }
        check(predicate()) { "Condition was not met within ${timeoutMs}ms" }
    }

    private fun execShell(command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            BufferedReader(InputStreamReader(input)).readText()
        }
    }
}
