package com.bess.salestrainer.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.bess.salestrainer.R
import com.bess.salestrainer.navigation.BessNavHost
import com.bess.salestrainer.navigation.Routes
import com.bess.salestrainer.navigation.TopLevelDestination

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BessAppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTopLevel = TopLevelDestination.entries.any { it.route == currentRoute }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            if (isTopLevel) {
                TopAppBar(
                    title = {
                        Text(
                            text = TopLevelDestination.entries
                                .firstOrNull { it.route == currentRoute }
                                ?.let { stringResource(it.labelRes) }
                                ?: stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = { navController.navigate(Routes.SETTINGS) },
                            modifier = Modifier.semantics { contentDescription = "设置" },
                        ) {
                            Icon(Icons.Filled.Settings, contentDescription = null)
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (isTopLevel) {
                NavigationBar {
                    TopLevelDestination.entries.forEach { dest ->
                        NavigationBarItem(
                            selected = currentRoute == dest.route,
                            onClick = {
                                navController.navigate(dest.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    dest.icon,
                                    contentDescription = stringResource(dest.labelRes),
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        BessNavHost(
            navController = navController,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
