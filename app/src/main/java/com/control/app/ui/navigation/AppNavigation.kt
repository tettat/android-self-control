package com.control.app.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.control.app.ui.screens.DebugScreen
import com.control.app.ui.screens.HomeScreen
import com.control.app.ui.screens.PromptDetailScreen
import com.control.app.ui.screens.PromptListScreen
import com.control.app.ui.screens.SettingsScreen

object Routes {
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val PROMPTS = "prompts"
    const val PROMPT_DETAIL = "prompt_detail/{key}"
    const val DEBUG = "debug"

    fun promptDetail(key: String): String = "prompt_detail/$key"
}

private const val NAV_ANIMATION_DURATION = 300

@Composable
fun AppNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        enterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(NAV_ANIMATION_DURATION)
            ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION))
        },
        exitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Start,
                animationSpec = tween(NAV_ANIMATION_DURATION)
            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION))
        },
        popEnterTransition = {
            slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(NAV_ANIMATION_DURATION)
            ) + fadeIn(animationSpec = tween(NAV_ANIMATION_DURATION))
        },
        popExitTransition = {
            slideOutOfContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.End,
                animationSpec = tween(NAV_ANIMATION_DURATION)
            ) + fadeOut(animationSpec = tween(NAV_ANIMATION_DURATION))
        }
    ) {
        composable(Routes.HOME) {
            HomeScreen(navController = navController)
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(navController = navController)
        }

        composable(Routes.PROMPTS) {
            PromptListScreen(navController = navController)
        }

        composable(
            route = Routes.PROMPT_DETAIL,
            arguments = listOf(
                navArgument("key") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val key = backStackEntry.arguments?.getString("key") ?: ""
            PromptDetailScreen(navController = navController, promptKey = key)
        }

        composable(Routes.DEBUG) {
            DebugScreen(navController = navController)
        }
    }
}
