package com.hubidubios.mobile.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class Dest(val route: String, val label: String, val icon: @Composable () -> Unit)

@Composable
fun HubidubiOsApp(vm: HubidubiOsViewModel = viewModel()) {
    HubidubiOsTheme {
        Surface {
            val nav = rememberNavController()
            val dests = listOf(
                Dest("projects", "Projects") { Icon(Icons.Filled.AccountTree, null) },
                Dest("home", "Home") { Icon(Icons.Filled.Add, null) },
                Dest("next", "Next") { Icon(Icons.Filled.CheckCircle, null) },
                Dest("chat", "Chat") { Icon(Icons.Filled.Chat, null) },
                Dest("settings", "Settings") { Icon(Icons.Filled.Settings, null) },
            )
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        val current by nav.currentBackStackEntryAsState()
                        val route = current?.destination?.route
                        dests.forEach { d ->
                            NavigationBarItem(
                                selected = route == d.route,
                                onClick = {
                                    nav.navigate(d.route) {
                                        popUpTo("projects"); launchSingleTop = true
                                    }
                                },
                                icon = d.icon,
                                label = { Text(d.label) },
                            )
                        }
                    }
                }
            ) { pad ->
                val state by vm.state.collectAsStateWithLifecycle()
                NavHost(nav, startDestination = "projects", modifier = Modifier.padding(pad)) {
                    composable("projects") { ProjectsScreen(state, vm) }
                    composable("home") { HomeScreen(state, vm) }
                    composable("next") { NextScreen(state, vm) }
                    composable("chat") { ChatScreen(state, vm) }
                    composable("settings") { SettingsScreen(state, vm) }
                }
            }
        }
    }
}

/** The inference/sync status dot — the native equivalent of the old web chip. */
@Composable
fun StatusDot(connected: Boolean, label: String) {
    Row {
        Text("● ", color = if (connected) Color(0xFF22AA66) else Color(0xFF999999))
        Text(label)
    }
}
