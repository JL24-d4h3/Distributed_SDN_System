package org.sdn.sdn_mobile_agent.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.sdn.sdn_mobile_agent.ui.screens.*
import org.sdn.sdn_mobile_agent.viewmodel.MainViewModel

/**
 * Definición de las pantallas de la app con navegación bottom bar.
 */
sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object Search : Screen("search", "Buscar", Icons.Default.Search)
    data object Log : Screen("log", "Log", Icons.Default.List)
    data object Config : Screen("config", "Config", Icons.Default.Settings)
}

/**
 * Componente raíz de navegación.
 *
 * Estructura:
 * - Bottom Navigation Bar con 4 pestañas
 * - NavHost que muestra la pantalla correspondiente
 * - Comparte el MainViewModel entre todas las pantallas
 *
 * Pantallas:
 * 1. Dashboard - Estado general del agente
 * 2. Buscar - Solicitar contenido al controlador
 * 3. Log - Historial de comandos recibidos
 * 4. Config - Configuración de conexión
 */
@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val screens = listOf(Screen.Dashboard, Screen.Search, Screen.Log, Screen.Config)
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    NavigationBarItem(
                        selected = currentRoute == screen.route,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(screen.icon, contentDescription = screen.title) },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) { DashboardScreen(viewModel) }
            composable(Screen.Search.route) { SearchScreen(viewModel) }
            composable(Screen.Log.route) { LogScreen(viewModel) }
            composable(Screen.Config.route) { ConfigScreen(viewModel) }
        }
    }
}
