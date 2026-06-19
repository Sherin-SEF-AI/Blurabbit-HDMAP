package com.blurabbit.hdmap.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.blurabbit.hdmap.ui.dashboard.DashboardScreen
import com.blurabbit.hdmap.ui.settings.SettingsScreen
import com.blurabbit.hdmap.ui.tripdetail.TripDetailScreen
import com.blurabbit.hdmap.ui.tripmap.TripMapScreen
import com.blurabbit.hdmap.ui.trips.TripsScreen

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Dest("dashboard", "Capture", Icons.Filled.LocationOn)
    data object Trips : Dest("trips", "Trips", Icons.AutoMirrored.Filled.List)
    data object Settings : Dest("settings", "Settings", Icons.Filled.Settings)
}

@Composable
fun AppScaffold() {
    val navController = rememberNavController()
    val tabs = listOf(Dest.Dashboard, Dest.Trips, Dest.Settings)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { dest ->
                    val selected = currentDestination?.hierarchy?.any { it.route == dest.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(dest.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(dest.icon, contentDescription = dest.label) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Dashboard.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Dashboard.route) { DashboardScreen() }
            composable(Dest.Trips.route) { TripsScreen(onOpenTrip = { navController.navigate("trip/$it") }) }
            composable(Dest.Settings.route) { SettingsScreen() }
            composable("trip/{tripId}") { entry ->
                TripDetailScreen(onOpenMap = {
                    navController.navigate("trip/${entry.arguments?.getString("tripId")}/map")
                })
            }
            composable("trip/{tripId}/map") { TripMapScreen() }
        }
    }
}
