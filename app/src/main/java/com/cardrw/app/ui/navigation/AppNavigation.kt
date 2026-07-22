package com.cardrw.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.cardrw.app.ui.screens.card.CardScreen
import com.cardrw.app.ui.screens.dumps.DumpsScreen
import com.cardrw.app.ui.screens.home.HomeScreen
import com.cardrw.app.ui.screens.journal.JournalScreen
import com.cardrw.app.ui.screens.templates.TemplatesScreen
import com.cardrw.app.ui.screens.vault.VaultScreen

object Routes {
    const val HOME = "home"
    const val CARD = "card"
    const val VAULT = "vault"
    const val TEMPLATES = "templates"
    const val DUMPS = "dumps"
    const val JOURNAL = "journal"
}

@Composable
fun CardRwNavHost(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onOpenCard = { navController.navigate(Routes.CARD) },
                onOpenVault = { navController.navigate(Routes.VAULT) },
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
