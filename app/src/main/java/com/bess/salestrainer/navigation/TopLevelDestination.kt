package com.bess.salestrainer.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector
import com.bess.salestrainer.R

enum class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME("home", R.string.nav_home, Icons.Filled.Home),
    VOCABULARY("vocabulary", R.string.nav_vocabulary, Icons.Filled.List),
    SCENARIO("scenario", R.string.nav_scenario, Icons.Filled.PlayArrow),
    SETTINGS("settings", R.string.nav_settings, Icons.Filled.Settings),
}
