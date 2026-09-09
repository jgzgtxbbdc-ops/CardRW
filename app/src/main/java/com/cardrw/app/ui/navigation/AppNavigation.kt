package com.cardrw.app.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cardrw.app.R
import com.cardrw.app.nfc.NfcReaderController
import com.cardrw.app.ui.screens.atelier.AtelierScreen
import com.cardrw.app.ui.screens.card.CardScreen
import com.cardrw.app.ui.screens.home.DisclaimerDialog
import com.cardrw.app.ui.screens.jobs.JobsScreen
import com.cardrw.app.ui.screens.journal.JournalScreen
import com.cardrw.app.ui.screens.profiles.ProfileDetailScreen
import com.cardrw.app.viewmodel.AppNavViewModel
import com.cardrw.app.viewmodel.UiSettingsViewModel

object Routes {
    const val CARD = "card"
    const val ATELIER = "atelier"
    const val PROFILE_DETAIL = "profiles/{profileId}"
    const val JOBS = "jobs"
    const val JOURNAL = "journal"

    fun profileDetail(profileId: String) = "profiles/$profileId"
}

private data class TopTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

private val TopTabs = listOf(
    TopTab(Routes.CARD, R.string.nav_card, Icons.Outlined.CreditCard),
    TopTab(Routes.ATELIER, R.string.nav_atelier, Icons.Outlined.Key),
    TopTab(Routes.JOBS, R.string.nav_jobs, Icons.Outlined.FolderOpen),
    TopTab(Routes.JOURNAL, R.string.nav_journal, Icons.AutoMirrored.Outlined.ListAlt),
)

@Composable
fun CardRwNavHost(
    navController: NavHostController = rememberNavController(),
    appNavViewModel: AppNavViewModel = hiltViewModel(),
    settings: UiSettingsViewModel = hiltViewModel(),
) {
    val disclaimerAccepted by settings.disclaimerAccepted.collectAsStateWithLifecycle()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    LaunchedEffect(appNavViewModel.tagBus) {
        appNavViewModel.tagBus.tags.collect { tag ->
            if (!NfcReaderController.isIsoDep(tag)) return@collect
            val route = navController.currentBackStackEntry?.destination?.route
            if (route != Routes.CARD) {
                navController.navigate(Routes.CARD) {
                    launchSingleTop = true
                    restoreState = true
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                }
            }
        }
    }

    if (!disclaimerAccepted) {
        DisclaimerDialog(onAccept = { settings.acceptDisclaimer() })
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopTabs.forEach { tab ->
                    val selected = currentDestination?.hierarchy?.any { dest ->
                        dest.route == tab.route ||
                            (tab.route == Routes.ATELIER && dest.route == Routes.PROFILE_DETAIL)
                    } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(tab.route) {
                                launchSingleTop = true
                                restoreState = true
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                            }
                        },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.CARD,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.CARD) {
                CardScreen()
            }
            composable(Routes.ATELIER) {
                AtelierScreen(
                    onOpenProfile = { id -> navController.navigate(Routes.profileDetail(id)) },
                )
            }
            composable(
                route = Routes.PROFILE_DETAIL,
                arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
            ) {
                ProfileDetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.JOBS) {
                JobsScreen()
            }
            composable(Routes.JOURNAL) {
                JournalScreen()
            }
        }
    }
}
