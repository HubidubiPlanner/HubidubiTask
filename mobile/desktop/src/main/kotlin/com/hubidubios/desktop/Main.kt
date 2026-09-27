package com.hubidubios.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.hubidubios.desktop.ui.ChatScreen
import com.hubidubios.desktop.ui.HomeScreen
import com.hubidubios.desktop.ui.HubidubiOsTheme
import com.hubidubios.desktop.ui.HubidubiOsViewModel
import com.hubidubios.desktop.ui.ImportScreen
import com.hubidubios.desktop.ui.NextScreen
import com.hubidubios.desktop.ui.ProjectsScreen
import com.hubidubios.desktop.ui.SettingsScreen

private data class Dest(val route: String, val label: String, val icon: ImageVector)

fun main() {
    val store = Store()
    var vm: HubidubiOsViewModel? = null
    val server = PeerServer(store, deviceName = macName()) { vm?.refresh() }
    server.start()
    vm = HubidubiOsViewModel(store, server)
    application {
        Window(
            onCloseRequest = { server.stop(); exitApplication() },
            title = "HubidubiOS",
            state = rememberWindowState(size = DpSize(1080.dp, 780.dp)),
        ) { App(vm) }
    }
}

private fun macName(): String = runCatching {
    ProcessBuilder("scutil", "--get", "ComputerName").start().inputStream.bufferedReader().readText().trim()
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "Mac"

@Composable
fun App(vm: HubidubiOsViewModel, start: String = "projects") {
    HubidubiOsTheme {
        val c = HubidubiOsTheme.colors
        val state by vm.state.collectAsState()
        var route by remember { mutableStateOf(start) }
        val dests = listOf(
            Dest("projects", "Projects", Icons.Outlined.Folder),
            Dest("home", "Capture", Icons.Filled.Add),
            Dest("next", "Next", Icons.Outlined.CheckCircle),
            Dest("chat", "Agent", Icons.Outlined.ChatBubbleOutline),
            Dest("settings", "Settings", Icons.Outlined.Tune),
        )
        Surface(color = c.paper, modifier = Modifier.fillMaxSize()) {
            Row {
                // On a desktop the tabs sit on the left, the page gets the width.
                NavigationRail(containerColor = c.nav, modifier = Modifier.width(92.dp)) {
                    dests.forEach { d ->
                        val selected = route == d.route || (d.route == "settings" && route == "import")
                        NavigationRailItem(
                            selected = selected, onClick = { route = d.route },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(d.label, style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = c.accent, selectedTextColor = c.accent,
                                unselectedIconColor = c.muted, unselectedTextColor = c.muted,
                                indicatorColor = Color.Transparent),
                        )
                    }
                }
                VerticalDivider(color = c.line, modifier = Modifier.fillMaxHeight())
                // A page as wide as a big window reads badly; keep a column.
                Box(Modifier.fillMaxHeight().widthIn(max = 820.dp)) { when (route) {
                    "projects" -> ProjectsScreen(state, vm)
                    "home" -> HomeScreen(state, vm)
                    "next" -> NextScreen(state, vm)
                    "chat" -> ChatScreen(state, vm)
                    "settings" -> SettingsScreen(state, vm, onImport = { route = "import" })
                    "import" -> ImportScreen(vm, onBack = { route = "settings" })
                } }
            }
        }
    }
}
