package com.bess.salestrainer.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.ui.graphics.vector.ImageVector
import com.bess.salestrainer.R

/**
 * Bottom navigation: vocabulary, scenario and article. Settings lives in the
 * top app bar gear instead of a tab.
 */
enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    VOCABULARY("vocabulary", R.string.nav_vocabulary, Icons.Filled.List),
    SCENARIO("scenario", R.string.nav_scenario, Icons.Filled.PlayArrow),
    ARTICLE("article", R.string.nav_article, Icons.Filled.Star),
}
