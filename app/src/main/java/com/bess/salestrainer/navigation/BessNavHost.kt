package com.bess.salestrainer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.bess.salestrainer.feature.home.HomeScreen
import com.bess.salestrainer.feature.scenario.ScenarioScreen
import com.bess.salestrainer.feature.settings.SettingsScreen
import com.bess.salestrainer.feature.vocabulary.VocabularyScreen

@Composable
fun BessNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.HOME.route,
        modifier = modifier,
    ) {
        composable(TopLevelDestination.HOME.route) { HomeScreen() }
        composable(TopLevelDestination.VOCABULARY.route) { VocabularyScreen() }
        composable(TopLevelDestination.SCENARIO.route) { ScenarioScreen() }
        composable(TopLevelDestination.SETTINGS.route) { SettingsScreen() }
    }
}
