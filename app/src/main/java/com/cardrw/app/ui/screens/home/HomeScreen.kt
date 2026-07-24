package com.cardrw.app.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cardrw.app.BuildConfig
import com.cardrw.app.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenCard: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenDumps: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.home_title))
                        Text(
                            stringResource(R.string.version_label, BuildConfig.VERSION_NAME),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item {
                HomeEntry(
                    icon = Icons.Outlined.CreditCard,
                    title = stringResource(R.string.nav_card),
                    description = stringResource(R.string.nav_card_desc),
                    onClick = onOpenCard,
                )
            }
            item {
                HomeEntry(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.nav_vault),
                    description = stringResource(R.string.nav_vault_desc),
                    onClick = onOpenVault,
                )
            }
            item {
                HomeEntry(
                    icon = Icons.Outlined.Key,
                    title = stringResource(R.string.nav_profiles),
                    description = stringResource(R.string.nav_profiles_desc),
                    onClick = onOpenProfiles,
                )
            }
            item {
                HomeEntry(
                    icon = Icons.Outlined.ViewModule,
                    title = stringResource(R.string.nav_templates),
                    description = stringResource(R.string.nav_templates_desc),
                    onClick = onOpenTemplates,
                )
            }
            item {
                HomeEntry(
                    icon = Icons.Outlined.Description,
                    title = stringResource(R.string.nav_dumps),
                    description = stringResource(R.string.nav_dumps_desc),
                    onClick = onOpenDumps,
                )
            }
            item {
                HomeEntry(
                    icon = Icons.AutoMirrored.Outlined.ListAlt,
                    title = stringResource(R.string.nav_journal),
                    description = stringResource(R.string.nav_journal_desc),
                    onClick = onOpenJournal,
                )
            }
        }
    }
}

@Composable
private fun HomeEntry(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
