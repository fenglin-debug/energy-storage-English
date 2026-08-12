package com.bess.salestrainer.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.bess.salestrainer.feature.article.ArticleListScreen
import com.bess.salestrainer.feature.article.ArticlePlayerScreen
import com.bess.salestrainer.feature.scenario.ScenarioPracticeScreen
import com.bess.salestrainer.feature.scenario.ScenarioScreen
import com.bess.salestrainer.feature.settings.SettingsScreen
import com.bess.salestrainer.feature.vocabulary.VocabularyPracticeScreen
import com.bess.salestrainer.feature.vocabulary.VocabularyScreen

object Routes {
    const val VOCAB_PRACTICE = "vocabulary/practice"
    const val SCENARIO_PRACTICE = "scenario/practice/{scenarioId}"
    const val ARTICLE_PLAYER = "article/player/{articleId}"
    const val SETTINGS = "settings"
    fun scenarioPractice(scenarioId: String) = "scenario/practice/$scenarioId"
    fun articlePlayer(articleId: String) = "article/player/$articleId"
}

@Composable
fun BessNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = TopLevelDestination.VOCABULARY.route,
        modifier = modifier,
    ) {
        composable(TopLevelDestination.VOCABULARY.route) {
            VocabularyScreen(
                onStartPractice = { navController.navigate(Routes.VOCAB_PRACTICE) },
            )
        }
        composable(Routes.VOCAB_PRACTICE) {
            VocabularyPracticeScreen(
                onFinished = { navController.popBackStack() },
            )
        }
        composable(TopLevelDestination.SCENARIO.route) {
            ScenarioScreen(
                onOpenScenario = { id -> navController.navigate(Routes.scenarioPractice(id)) },
            )
        }
        composable(
            route = Routes.SCENARIO_PRACTICE,
            arguments = listOf(navArgument("scenarioId") { type = NavType.StringType }),
        ) { backStackEntry ->
            ScenarioPracticeScreen(
                scenarioId = backStackEntry.arguments?.getString("scenarioId").orEmpty(),
                onFinished = { navController.popBackStack() },
            )
        }
        composable(TopLevelDestination.ARTICLE.route) {
            ArticleListScreen(
                onOpenArticle = { id -> navController.navigate(Routes.articlePlayer(id)) },
            )
        }
        composable(
            route = Routes.ARTICLE_PLAYER,
            arguments = listOf(navArgument("articleId") { type = NavType.StringType }),
        ) { backStackEntry ->
            ArticlePlayerScreen(
                articleId = backStackEntry.arguments?.getString("articleId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) { SettingsScreen() }
    }
}
