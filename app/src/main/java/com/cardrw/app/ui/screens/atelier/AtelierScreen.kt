package com.cardrw.app.ui.screens.atelier

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
import com.cardrw.app.ui.screens.profiles.ProfilesScreen
import com.cardrw.app.ui.screens.vault.VaultScreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtelierScreen(
    onOpenProfile: (String) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (tab == 0) {
                            stringResource(R.string.atelier_materials)
                        } else {
                            stringResource(R.string.atelier_profiles)
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
                    text = { Text(stringResource(R.string.atelier_materials)) },
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(stringResource(R.string.atelier_profiles)) },
                )
            }
            when (tab) {
                0 -> VaultScreen(showTopBar = false)
                else -> ProfilesScreen(
                    showTopBar = false,
                    onOpenProfile = onOpenProfile,
                )
            }
        }
    }
}
