import java.io.ByteArrayOutputStream
import org.gradle.api.GradleException

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// ---------------------------------------------------------------------------
// Read .env file (gitignored) for build-time configuration
// ---------------------------------------------------------------------------
val envFile = rootProject.file(".env")
val envProps = mutableMapOf<String, String>()
if (envFile.exists()) {
    envFile.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
            val (key, value) = trimmed.split("=", limit = 2)
            envProps[key.trim()] = value.trim()
        }
    }
}

val cleartextDomains = envProps.getOrDefault("CLEARTEXT_DOMAINS", "")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

fun escapeXml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

fun asBuildConfigString(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
    append('"')
}

val generatedNetworkSecurityResDir = layout.buildDirectory.dir("generated/res/networkSecurityConfig")
val generateNetworkSecurityConfig = tasks.register("generateNetworkSecurityConfig") {
    outputs.dir(generatedNetworkSecurityResDir)

    doLast {
        val outputFile = generatedNetworkSecurityResDir.get()
            .file("xml/network_security_config.xml")
            .asFile

        outputFile.parentFile.mkdirs()

        val allowedDomains = (listOf("localhost", "127.0.0.1", "10.0.2.2") + cleartextDomains)
            .distinct()

        val domainEntries = allowedDomains.joinToString("\n") { domain ->
            """        <domain includeSubdomains="true">${escapeXml(domain)}</domain>"""
        }

        outputFile.writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
                appendLine("<network-security-config>")
                appendLine("    <!-- Allow cleartext traffic only for explicitly configured local/dev hosts -->")
                appendLine("    <domain-config cleartextTrafficPermitted=\"true\">")
                appendLine(domainEntries)
                appendLine("    </domain-config>")
                appendLine()
                appendLine("    <!-- Default: only allow HTTPS -->")
                appendLine("    <base-config cleartextTrafficPermitted=\"false\">")
                appendLine("        <trust-anchors>")
                appendLine("            <certificates src=\"system\" />")
                appendLine("        </trust-anchors>")
                appendLine("    </base-config>")
                appendLine("</network-security-config>")
            }
        )
    }
}

android {
    namespace = "com.control.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.control.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }

        // Inject .env values into BuildConfig
        buildConfigField(
            "String",
            "DEFAULT_RELAY_URL",
            asBuildConfigString(envProps.getOrDefault("DEFAULT_RELAY_URL", ""))
        )
        buildConfigField(
            "String",
            "DEFAULT_API_ENDPOINT",
            asBuildConfigString(envProps.getOrDefault("DEFAULT_API_ENDPOINT", ""))
        )
        buildConfigField(
            "String",
            "DEFAULT_API_KEY",
            asBuildConfigString(envProps.getOrDefault("DEFAULT_API_KEY", ""))
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets.getByName("main") {
        res.srcDir(generatedNetworkSecurityResDir)
    }
}

tasks.named("preBuild").configure {
    dependsOn(generateNetworkSecurityConfig)
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = mutableMapOf<String, String>()
if (localPropertiesFile.exists()) {
    localPropertiesFile.readLines().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
            val (key, value) = trimmed.split("=", limit = 2)
            localProperties[key.trim()] = value.trim()
        }
    }
}

val adbExecutableProvider = providers.provider {
    val sdkDir = localProperties["sdk.dir"]
        ?: System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: rootProject.file(".android-sdk").takeIf { it.exists() }?.absolutePath
        ?: throw GradleException(
            "Cannot locate Android SDK. Set sdk.dir in local.properties or ANDROID_HOME."
        )
    val adbName = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        "adb.exe"
    } else {
        "adb"
    }
    rootProject.file("$sdkDir/platform-tools/$adbName").absolutePath
}

fun parseConnectedDeviceSerials(adbOutput: String): List<String> = adbOutput
    .lineSequence()
    .drop(1)
    .map { it.trim() }
    .filter { it.isNotEmpty() && it.endsWith("\tdevice") }
    .map { it.substringBefore('\t') }
    .toList()

fun resolveTargetDeviceSerial(adbExecutable: String): String {
    val explicitSerial = providers.gradleProperty("adbSerial").orNull
        ?: System.getenv("ANDROID_SERIAL")
    if (!explicitSerial.isNullOrBlank()) {
        return explicitSerial
    }

    val stdout = ByteArrayOutputStream()
    exec {
        commandLine(adbExecutable, "devices")
        standardOutput = stdout
    }

    val serials = parseConnectedDeviceSerials(stdout.toString())
    return when (serials.size) {
        0 -> throw GradleException(
            "No online adb device found. Connect a device or pass -PadbSerial=<serial>."
        )
        1 -> serials.single()
        else -> throw GradleException(
            "Multiple adb devices found (${serials.joinToString()}). Pass -PadbSerial=<serial>."
        )
    }
}

val debugApplicationId = "com.control.app.debug"
val debugLaunchComponent = "$debugApplicationId/com.control.app.MainActivity"

tasks.register("installDebugFast") {
    group = "install"
    description = "Builds the debug APK and installs it with adb --fastdeploy."
    dependsOn("packageDebug")

    doLast {
        val adbExecutable = adbExecutableProvider.get()
        val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        if (!apkFile.exists()) {
            throw GradleException("Debug APK not found at ${apkFile.absolutePath}")
        }

        val serial = resolveTargetDeviceSerial(adbExecutable)
        logger.lifecycle("Installing ${apkFile.name} to $serial with fastdeploy")

        exec {
            commandLine(
                adbExecutable,
                "-s",
                serial,
                "install",
                "-r",
                "-t",
                "--fastdeploy",
                "--date-check-agent",
                apkFile.absolutePath
            )
        }
    }
}

tasks.register("installAndLaunchDebugFast") {
    group = "install"
    description = "Builds, installs, and launches the debug app on a connected device."
    dependsOn("installDebugFast")

    doLast {
        val adbExecutable = adbExecutableProvider.get()
        val serial = resolveTargetDeviceSerial(adbExecutable)
        logger.lifecycle("Launching $debugLaunchComponent on $serial")

        exec {
            commandLine(
                adbExecutable,
                "-s",
                serial,
                "shell",
                "am",
                "start",
                "-n",
                debugLaunchComponent,
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER"
            )
        }
    }
}

tasks.register("installAndLaunchDebug") {
    group = "install"
    description = "Installs the debug APK and launches the app on the target device."
    dependsOn("installDebug")

    doLast {
        val adbExecutable = adbExecutableProvider.get()
        val serial = resolveTargetDeviceSerial(adbExecutable)
        logger.lifecycle("Launching $debugLaunchComponent on $serial")

        exec {
            commandLine(
                adbExecutable,
                "-s",
                serial,
                "shell",
                "am",
                "start",
                "-n",
                debugLaunchComponent,
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER"
            )
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.8.2")

    // Navigation Compose
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ADB client (on-device, connects to local daemon)
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Coil (image loading for Compose)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Core KTX
    implementation("androidx.core:core-ktx:1.12.0")

    // Compose debug tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
