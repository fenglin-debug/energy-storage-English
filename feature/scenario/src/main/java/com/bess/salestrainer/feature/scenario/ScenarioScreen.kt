package com.bess.salestrainer.feature.scenario

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Wave 0 placeholder — replaced by TASK-B-02 with scenario simulation UI. */
@Composable
fun ScenarioScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("情景模拟", style = MaterialTheme.typography.headlineSmall)
        Text("Wave 0 空壳 — TASK-B-02 实现", style = MaterialTheme.typography.bodyMedium)
    }
}
