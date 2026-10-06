package com.hubidubitask.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RssFeed
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
import com.hubidubitask.desktop.ui.HubidubiTaskTheme
import com.hubidubitask.desktop.ui.HubidubiTaskViewModel
import com.hubidubitask.desktop.ui.FeedsScreen
import com.hubidubitask.desktop.ui.ImportScreen
import com.hubidubitask.desktop.ui.NextScreen
import com.hubidubitask.desktop.ui.ProjectsScreen
import com.hubidubitask.desktop.ui.SettingsScreen

private data class Dest(val route: String, val label: String, val icon: ImageVector)

fun main() {
    val store = Store()
    var vm: HubidubiTaskViewModel? = null
    val server = PeerServer(store, deviceName = Platform.computerName()) { vm?.refresh() }
    server.start()
    vm = HubidubiTaskViewModel(store, server)
    // The window goes the moment it is closed. Withdrawing the LAN
    // announcement (JmDNS) can take seconds, so it runs after, with a limit:
    // the phone copes with a Mac that vanished without saying goodbye.
    application(exitProcessOnExit = false) {
        Window(
            onCloseRequest = ::exitApplication,
            title = "HubidubiTask",
            state = rememberWindowState(size = DpSize(1080.dp, 780.dp)),
        ) { App(vm) }
    }
    val goodbye = Thread { runCatching { server.stop() } }.apply { isDaemon = true; start() }
    goodbye.join(1500)
    kotlin.system.exitProcess(0)
}


@Composable
fun App(vm: HubidubiTaskViewModel, start: String = "next") {
    HubidubiTaskTheme {
        val c = HubidubiTaskTheme.colors
        val state by vm.state.collectAsState()
        var route by remember { mutableStateOf(start) }
        val dests = listOf(
            // Next first, and where the app opens: what to do comes before where it is filed.
            Dest("next", "Next", Icons.Outlined.CheckCircle),
            Dest("projects", "Projects", Icons.Outlined.Folder),
            Dest("feeds", "Feeds", Icons.Outlined.RssFeed),
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
                // The page takes the whole window: long titles get the room
                // instead of wrapping beside empty space.
                Box(Modifier.fillMaxSize()) { when (route) {
                    "projects" -> ProjectsScreen(state, vm)
                    "next" -> NextScreen(state, vm)
                    "feeds" -> FeedsScreen(state, vm)
                    "settings" -> SettingsScreen(state, vm, onImport = { route = "import" })
                    "import" -> ImportScreen(vm, onBack = { route = "settings" })
                } }
            }
        }
    }
}
