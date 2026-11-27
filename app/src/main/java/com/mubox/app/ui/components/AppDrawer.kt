package com.mubox.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.mubox.app.R
import com.mubox.app.ui.navigation.Screen
import com.mubox.app.ui.navigation.getTabForRoute
import kotlinx.coroutines.launch

private data class DrawerEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val topLevel: Boolean
)

@Composable
fun AppDrawer(
    navController: NavController,
    currentRoute: String?,
    drawerState: DrawerState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()

    val homeEntries = listOf(
        DrawerEntry(Screen.Dashboard.route, "仪表盘", Icons.Rounded.Dashboard, topLevel = true),
        DrawerEntry(Screen.Nodes.route, stringResource(R.string.nodes_title), Icons.Rounded.Dns, topLevel = true),
        DrawerEntry(Screen.Profiles.route, stringResource(R.string.profiles_title), Icons.Rounded.FolderCopy, topLevel = true)
    )

    val settingsEntries = listOf(
        DrawerEntry(Screen.Settings.route, stringResource(R.string.settings_title), Icons.Rounded.Settings, topLevel = true),
        DrawerEntry(Screen.ConnectionSettings.route, stringResource(R.string.connection_settings_title), Icons.Rounded.PowerSettingsNew, topLevel = false),
        DrawerEntry(Screen.RoutingSettings.route, stringResource(R.string.routing_settings_title), Icons.Rounded.Route, topLevel = false),
        DrawerEntry(Screen.DnsSettings.route, stringResource(R.string.dns_settings_title), Icons.Rounded.Dns, topLevel = false),
        DrawerEntry(Screen.TunSettings.route, stringResource(R.string.tun_settings_title), Icons.Rounded.VpnKey, topLevel = false),
        DrawerEntry(Screen.RuleSets.route, stringResource(R.string.rulesets_title), Icons.Rounded.Sync, topLevel = false),
        DrawerEntry(Screen.CustomRules.route, stringResource(R.string.custom_rules_title), Icons.Rounded.Language, topLevel = false),
        DrawerEntry(Screen.DomainRules.route, stringResource(R.string.domain_rules_title), Icons.Rounded.Language, topLevel = false),
        DrawerEntry(Screen.AppRouting.route, stringResource(R.string.app_rules_title), Icons.Rounded.Apps, topLevel = false),
        DrawerEntry(Screen.RuleSetHub.route, stringResource(R.string.ruleset_hub_title), Icons.Rounded.Hub, topLevel = false),
        DrawerEntry(Screen.Diagnostics.route, stringResource(R.string.diagnostics_title), Icons.Rounded.BugReport, topLevel = false),
        DrawerEntry(Screen.Logs.route, stringResource(R.string.logs_title), Icons.Rounded.History, topLevel = false),
        DrawerEntry(Screen.TrafficStats.route, stringResource(R.string.traffic_stats_title), Icons.Rounded.Analytics, topLevel = false),
        DrawerEntry(Screen.ConnectionInfo.route, stringResource(R.string.connection_info_title), Icons.Rounded.SwapVert, topLevel = false)
    )

    ModalDrawerSheet(
        modifier = modifier,
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.Menu,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(Modifier.height(12.dp))

            DrawerSection(title = "主页")
            homeEntries.forEach { entry ->
                DrawerRow(
                    entry = entry,
                    currentRoute = currentRoute,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navigateEntry(navController, entry)
                        }
                    }
                )
            }

            Spacer(Modifier.height(16.dp))

            DrawerSection(title = stringResource(R.string.settings_title))
            settingsEntries.forEach { entry ->
                DrawerRow(
                    entry = entry,
                    currentRoute = currentRoute,
                    onClick = {
                        scope.launch {
                            drawerState.close()
                            navigateEntry(navController, entry)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun DrawerSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
    )
}

@Composable
private fun DrawerRow(
    entry: DrawerEntry,
    currentRoute: String?,
    onClick: () -> Unit
) {
    val selected = if (entry.topLevel) {
        getTabForRoute(currentRoute) == entry.route
    } else {
        currentRoute == entry.route
    }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surface
                },
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = entry.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp)
        )
    }
}

private fun navigateEntry(navController: NavController, entry: DrawerEntry) {
    if (entry.topLevel) {
        navController.navigate(entry.route) {
            popUpTo(Screen.Dashboard.route) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    } else {
        navController.navigate(entry.route) {
            launchSingleTop = true
        }
    }
}
