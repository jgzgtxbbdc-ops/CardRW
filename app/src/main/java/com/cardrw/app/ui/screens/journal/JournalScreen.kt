package com.cardrw.app.ui.screens.journal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.cardrw.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.journal_title)) },
            )
        },
    ) { padding ->
        JournalPanel(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
