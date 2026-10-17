package com.hubidubitask.mobile.ui

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RssFeed
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.hubidubitask.core.i18n.t

private data class Dest(val route: String, val label: String, val icon: ImageVector)

@Composable
fun HubidubiTaskApp(vm: HubidubiTaskViewModel = viewModel()) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    HubidubiTaskTheme(theme) {
        val c = HubidubiTaskTheme.colors
        Surface(color = c.paper) {
            val nav = rememberNavController()
            // A new language redraws each screen and the tab labels, and keeps
            // the place: the navigation lives outside what is redrawn.
            val language by vm.language.collectAsStateWithLifecycle()
            val dests = listOf(
                // Incoming first, and where the app opens: what to do comes before where it is filed.
                Dest("next", "Incoming", Icons.Outlined.CheckCircle),
                Dest("projects", "Projects", Icons.Outlined.Folder),
                Dest("feeds", "Feeds", Icons.Outlined.RssFeed),
                Dest("settings", "Settings", Icons.Outlined.Tune),
            )
            Scaffold(
                containerColor = c.paper,
                bottomBar = {
                    Column {
                        HorizontalDivider(color = c.line, thickness = 1.dp)
                        key(language) { NavigationBar(containerColor = c.nav, tonalElevation = 0.dp) {
                            val current by nav.currentBackStackEntryAsState()
                            val route = current?.destination?.route
                            dests.forEach { d ->
                                // Import is reached from Settings, so Settings stays lit.
                                val selected = route == d.route ||
                                    (d.route == "settings" && route == "import")
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = {
                                        nav.navigate(d.route) {
                                            popUpTo("next"); launchSingleTop = true
                                        }
                                    },
                                    icon = { Icon(d.icon, contentDescription = null) },
                                    label = {
                                        Text(t(d.label), style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (selected) FontWeight.SemiBold
                                                         else FontWeight.Medium)
                                    },
                                    // A rule over the selected tab instead of Material's
                                    // pill: it marks the place without adding a shape.
                                    modifier = Modifier.drawBehind {
                                        if (selected) drawLine(
                                            c.accent, Offset(0f, 1f), Offset(size.width, 1f),
                                            strokeWidth = 2.dp.toPx(),
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = c.accent,
                                        selectedTextColor = c.accent,
                                        unselectedIconColor = c.muted,
                                        unselectedTextColor = c.muted,
                                        indicatorColor = Color.Transparent,
                                    ),
                                )
                            }
                        } }
                    }
                }
            ) { pad ->
                val state by vm.state.collectAsStateWithLifecycle()
                // Consumed here so a screen asking for imePadding() gets only the
                // part of the keyboard that rises above the bottom bar.
                NavHost(nav, startDestination = "next",
                    modifier = Modifier.padding(pad).consumeWindowInsets(pad)) {
                    composable("projects") { key(language) { ProjectsScreen(state, vm) } }
                    composable("next") { key(language) { NextScreen(state, vm) } }
                    composable("feeds") { key(language) { FeedsScreen(state, vm) } }
                    composable("settings") {
                        key(language) { SettingsScreen(state, vm, onImport = { nav.navigate("import") }) }
                    }
                    composable("import") { key(language) { ImportScreen(vm, onBack = { nav.popBackStack() }) } }
                }
            }
        }
    }
}
