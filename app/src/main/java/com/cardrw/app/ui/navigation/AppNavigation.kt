package com.cardrw.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cardrw.app.nfc.NfcReaderController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.cardrw.app.ui.screens.card.CardScreen
import com.cardrw.app.ui.screens.dumps.DumpsScreen
import com.cardrw.app.ui.screens.home.HomeScreen
import com.cardrw.app.ui.screens.journal.JournalScreen
import com.cardrw.app.ui.screens.profiles.ProfileDetailScreen
import com.cardrw.app.ui.screens.profiles.ProfilesScreen
import com.cardrw.app.ui.screens.templates.TemplatesScreen
import com.cardrw.app.ui.screens.vault.VaultScreen
import com.cardrw.app.viewmodel.AppNavViewModel

object Routes {
    const val HOME = "home"
    const val CARD = "card"
    const val VAULT = "vault"
    const val PROFILES = "profiles"
    const val PROFILE_DETAIL = "profiles/{profileId}"
    const val TEMPLATES = "templates"
    const val DUMPS = "dumps"
    const val JOURNAL = "journal"

    fun profileDetail(profileId: String) = "profiles/$profileId"
}

@Composable
fun CardRwNavHost(
    navController: NavHostController = rememberNavController(),
    appNavViewModel: AppNavViewModel = hiltViewModel(),
) {
    // Option B : tag IsoDep hors écran Carte → ouvrir Carte (reader mode déjà actif app-wide)
    LaunchedEffect(appNavViewModel.tagBus) {
        appNavViewModel.tagBus.tags.collect { tag ->
            if (!NfcReaderController.isIsoDep(tag)) return@collect
            val route = navController.currentBackStackEntry?.destination?.route
            if (route != Routes.CARD) {
                navController.navigate(Routes.CARD) {
                    launchSingleTop = true
                }
            }
        }
    }

    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenCard = { navController.navigate(Routes.CARD) },
                onOpenVault = { navController.navigate(Routes.VAULT) },
                onOpenProfiles = { navController.navigate(Routes.PROFILES) },
                onOpenTemplates = { navController.navigate(Routes.TEMPLATES) },
                onOpenDumps = { navController.navigate(Routes.DUMPS) },
                onOpenJournal = { navController.navigate(Routes.JOURNAL) },
            )
        }
        composable(Routes.CARD) {
            CardScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.VAULT) {
            VaultScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.PROFILES) {
            ProfilesScreen(
                onBack = { navController.popBackStack() },
                onOpenProfile = { id -> navController.navigate(Routes.profileDetail(id)) },
            )
        }
        composable(
            route = Routes.PROFILE_DETAIL,
            arguments = listOf(navArgument("profileId") { type = NavType.StringType }),
        ) {
            ProfileDetailScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.TEMPLATES) {
            TemplatesScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.DUMPS) {
            DumpsScreen(onBack = { navController.popBackStack() })
        }
        composable(Routes.JOURNAL) {
            JournalScreen(onBack = { navController.popBackStack() })
        }
    }
}
