package com.cardrw.app.ui.screens.jobs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cardrw.app.R
import com.cardrw.app.ui.screens.dumps.DumpsScreen
import com.cardrw.app.ui.screens.templates.TemplatesScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (tab == 0) {
                            stringResource(R.string.jobs_dumps)
                        } else {
                            stringResource(R.string.jobs_templates)
                        },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text(stringResource(R.string.jobs_dumps)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.jobs_templates)) },
                )
            }
            when (tab) {
                0 -> DumpsScreen(showTopBar = false)
                else -> TemplatesScreen(showTopBar = false)
            }
        }
    }
}
