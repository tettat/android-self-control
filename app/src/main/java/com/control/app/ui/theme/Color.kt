package com.control.app.ui.theme

import androidx.compose.ui.graphics.Color

// -- Primary palette: Deep indigo/blue --
val PrimaryDark = Color(0xFF8AB4F8)       // Soft blue for dark theme primary
val OnPrimaryDark = Color(0xFF002D6D)
val PrimaryContainerDark = Color(0xFF004397)
val OnPrimaryContainerDark = Color(0xFFD3E3FD)

val PrimaryLight = Color(0xFF1A5DB0)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFD3E3FD)
val OnPrimaryContainerLight = Color(0xFF001C3A)

// -- Secondary palette: Teal/cyan accent --
val SecondaryDark = Color(0xFF4DD0E1)      // Vibrant cyan accent
val OnSecondaryDark = Color(0xFF00363D)
val SecondaryContainerDark = Color(0xFF004F58)
val OnSecondaryContainerDark = Color(0xFF97F0FF)

val SecondaryLight = Color(0xFF006874)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFF97F0FF)
val OnSecondaryContainerLight = Color(0xFF001F24)

// -- Tertiary palette: Soft lavender --
val TertiaryDark = Color(0xFFCBBEFF)
val OnTertiaryDark = Color(0xFF322075)
val TertiaryContainerDark = Color(0xFF49398D)
val OnTertiaryContainerDark = Color(0xFFE7DEFF)

val TertiaryLight = Color(0xFF6151A6)
val OnTertiaryLight = Color(0xFFFFFFFF)
val TertiaryContainerLight = Color(0xFFE7DEFF)
val OnTertiaryContainerLight = Color(0xFF1C0A60)

// -- Error palette: Warm red --
val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)

val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

// -- Background & Surface: Near-black with subtle blue tint --
val BackgroundDark = Color(0xFF0E1117)         // Deep near-black
val OnBackgroundDark = Color(0xFFE2E2E9)
val SurfaceDark = Color(0xFF0E1117)
val OnSurfaceDark = Color(0xFFE2E2E9)
val SurfaceVariantDark = Color(0xFF43474E)
val OnSurfaceVariantDark = Color(0xFFC3C6CF)
val SurfaceDimDark = Color(0xFF0E1117)
val SurfaceBrightDark = Color(0xFF33373E)
val SurfaceContainerLowestDark = Color(0xFF090B11)
val SurfaceContainerLowDark = Color(0xFF161920)
val SurfaceContainerDark = Color(0xFF1A1D24)
val SurfaceContainerHighDark = Color(0xFF24272F)
val SurfaceContainerHighestDark = Color(0xFF2F323A)

val BackgroundLight = Color(0xFFF8F9FF)
val OnBackgroundLight = Color(0xFF191C20)
val SurfaceLight = Color(0xFFF8F9FF)
val OnSurfaceLight = Color(0xFF191C20)
val SurfaceVariantLight = Color(0xFFDEE3EB)
val OnSurfaceVariantLight = Color(0xFF43474E)
val SurfaceDimLight = Color(0xFFD7DAE0)
val SurfaceBrightLight = Color(0xFFF8F9FF)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFF1F3F9)
val SurfaceContainerLight = Color(0xFFECEDF4)
val SurfaceContainerHighLight = Color(0xFFE6E8EE)
val SurfaceContainerHighestLight = Color(0xFFE0E2E8)

// -- Outline & Inverse --
val OutlineDark = Color(0xFF8D9199)
val OutlineVariantDark = Color(0xFF43474E)
val InverseSurfaceDark = Color(0xFFE2E2E9)
val InverseOnSurfaceDark = Color(0xFF2E3138)
val InversePrimaryDark = Color(0xFF1A5DB0)
val ScrimDark = Color(0xFF000000)

val OutlineLight = Color(0xFF73777F)
val OutlineVariantLight = Color(0xFFC3C6CF)
val InverseSurfaceLight = Color(0xFF2E3138)
val InverseOnSurfaceLight = Color(0xFFEFF0F7)
val InversePrimaryLight = Color(0xFF8AB4F8)
val ScrimLight = Color(0xFF000000)

// -- Custom status colors for agent states --
object StatusColors {
    val Listening = Color(0xFF4DD0E1)      // Cyan - actively listening
    val Processing = Color(0xFF8AB4F8)     // Blue - processing/thinking
    val Executing = Color(0xFFFFA726)      // Orange - executing actions
    val Success = Color(0xFF66BB6A)        // Green - completed successfully
    val Error = Color(0xFFEF5350)          // Red - error occurred
    val Idle = Color(0xFF78909C)           // Blue-grey - idle/ready

    val VoiceBadge = Color(0xFFAB47BC)     // Purple - voice input
    val ApiBadge = Color(0xFFFFA726)       // Orange - API calls
    val ActionBadge = Color(0xFF66BB6A)    // Green - actions
    val InfoBadge = Color(0xFF42A5F5)      // Blue - info
    val ErrorBadge = Color(0xFFEF5350)     // Red - errors
    val ScreenshotBadge = Color(0xFF26C6DA) // Cyan - screenshots
}

// -- Gradient colors for the mic button --
object GradientColors {
    val MicButtonIdle = listOf(
        Color(0xFF1A5DB0),
        Color(0xFF2979CF),
        Color(0xFF3D8FE0)
    )
    val MicButtonListening = listOf(
        Color(0xFF00BFA5),
        Color(0xFF00ACC1),
        Color(0xFF0288D1)
    )
    val MicButtonGlow = Color(0xFF4DD0E1).copy(alpha = 0.3f)
}
