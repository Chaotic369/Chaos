package com.chandan.assistant.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.chandan.assistant.MainViewModel

private data class Tab(val route: String, val label: String, val icon: ImageVector)

@Composable
fun AssistantApp(vm: MainViewModel) {
    val onboarded by vm.onboarded.collectAsState()
    if (!onboarded) {
        OnboardingScreen(vm)
        return
    }
    val nav = rememberNavController()
    val tabs = listOf(
        Tab("home", "Home", Icons.Filled.Home),
        Tab("skills", "Skills", Icons.Filled.AutoAwesome),
        Tab("triggers", "Triggers", Icons.Filled.Schedule),
        Tab("activity", "Activity", Icons.Filled.History),
        Tab("settings", "Settings", Icons.Filled.Settings)
    )
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
                        selected = current == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { pad ->
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
            composable("home") { HomeScreen(vm) }
            composable("skills") { SkillsScreen(vm) }
            composable("triggers") { TriggersScreen(vm) }
            composable("activity") { ActivityScreen(vm) }
            composable("settings") { SettingsScreen(vm) }
        }
    }
}
