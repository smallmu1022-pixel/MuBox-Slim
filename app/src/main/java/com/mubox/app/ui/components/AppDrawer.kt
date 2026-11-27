package com.mubox.app.ui.components

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Brightness6
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.mubox.app.R
import com.mubox.app.model.AppLanguage
import com.mubox.app.model.AppThemeMode
import com.mubox.app.model.ImportOptions
import com.mubox.app.repository.RuleSetRepository
import com.mubox.app.ui.navigation.Screen
import com.mubox.app.ui.navigation.getTabForRoute
import com.mubox.app.ui.theme.liquidGlassSwitchColors
import com.mubox.app.viewmodel.ImportState
import com.mubox.app.viewmodel.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private data class DrawerEntry(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val topLevel: Boolean,
    val action: (() -> Unit)? = null,
    val value: String? = null,
    val switchChecked: Boolean? = null,
    val onSwitchChange: ((Boolean) -> Unit)? = null
)

@Composable
fun AppDrawer(
    navController: NavController,
    currentRoute: String?,
    drawerState: DrawerState,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showAboutDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var isUpdatingRuleSets by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf("") }

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()

    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager }
    var isIgnoringBatteryOptimizations by remember {
        mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        isIgnoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { viewModel.exportData(it) }
    }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.validateImportFile(it) }
    }

    fun generateExportFileName(): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
        return "mubox_backup_${dateFormat.format(Date())}.json"
    }

    val preparingUpdateMsg = stringResource(R.string.settings_preparing_update)
    val updateSuccessMsg = stringResource(R.string.settings_update_success)
    val updateFailedMsg = stringResource(R.string.settings_update_failed)
    val rulesetUpdateSuccessMsg = stringResource(R.string.settings_ruleset_update_success)
    val rulesetUpdateFailedMsg = stringResource(R.string.settings_ruleset_update_failed)
    val restartNeededMessage = stringResource(R.string.settings_restart_needed)
    val intervalMinMsg = stringResource(R.string.settings_update_interval_min)

    if (showThemeDialog) {
        SingleSelectDialog(
            title = stringResource(R.string.settings_app_theme),
            options = AppThemeMode.entries.map { stringResource(it.displayNameRes) },
            selectedIndex = AppThemeMode.entries.indexOf(settings.appTheme),
            onSelect = { index ->
                viewModel.setAppTheme(AppThemeMode.entries[index])
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showLanguageDialog) {
        SingleSelectDialog(
            title = stringResource(R.string.settings_app_language),
            options = AppLanguage.entries.map { stringResource(it.displayNameRes) },
            selectedIndex = AppLanguage.entries.indexOf(settings.appLanguage),
            onSelect = { index ->
                viewModel.setAppLanguage(AppLanguage.entries[index])
                showLanguageDialog = false
                AppNotificationManager.showMessage(
                    context = context,
                    message = restartNeededMessage,
                    duration = androidx.compose.material3.SnackbarDuration.Long
                )
            },
            onDismiss = { showLanguageDialog = false }
        )
    }

    if (showIntervalDialog) {
        InputDialog(
            title = stringResource(R.string.settings_update_interval),
            initialValue = settings.ruleSetAutoUpdateInterval.toString(),
            onConfirm = { newValue ->
                val interval = newValue.filter { it.isDigit() }.toIntOrNull()
                if (interval != null && interval >= 15) {
                    viewModel.setRuleSetAutoUpdateInterval(interval)
                } else {
                    AppNotificationManager.showMessage(context, intervalMinMsg)
                }
                showIntervalDialog = false
            },
            onDismiss = { showIntervalDialog = false }
        )
    }

    ExportProgressDialog(
        state = exportState,
        onDismiss = { viewModel.resetExportState() }
    )

    if (importState is ImportState.Preview) {
        val previewState = importState as ImportState.Preview
        ImportPreviewDialog(
            summary = previewState.summary,
            onConfirm = {
                viewModel.confirmImport(previewState.uri, ImportOptions(overwriteExisting = true))
            },
            onDismiss = { viewModel.resetImportState() }
        )
    }
    ImportProgressDialog(
        state = importState,
        onDismiss = { viewModel.resetImportState() }
    )
    if (importState is ImportState.Validating) {
        ValidatingDialog()
    }

    val homeEntries = listOf(
        DrawerEntry(Screen.Dashboard.route, "仪表盘", Icons.Rounded.Dashboard, topLevel = true),
        DrawerEntry(Screen.Nodes.route, stringResource(R.string.nodes_title), Icons.Rounded.Dns, topLevel = true),
        DrawerEntry(Screen.Profiles.route, stringResource(R.string.profiles_title), Icons.Rounded.FolderCopy, topLevel = true)
    )

    val networkEntries = listOf(
        DrawerEntry(Screen.ConnectionSettings.route, stringResource(R.string.connection_settings_title), Icons.Rounded.PowerSettingsNew, topLevel = false),
        DrawerEntry(Screen.RoutingSettings.route, stringResource(R.string.routing_settings_title), Icons.Rounded.Route, topLevel = false),
        DrawerEntry(Screen.DnsSettings.route, stringResource(R.string.dns_settings_title), Icons.Rounded.Dns, topLevel = false),
        DrawerEntry(Screen.TunSettings.route, stringResource(R.string.tun_settings_title), Icons.Rounded.VpnKey, topLevel = false),
        DrawerEntry(Screen.ConnectionInfo.route, stringResource(R.string.connection_info_title), Icons.Rounded.SwapVert, topLevel = false)
    )

    val rulesEntries = listOf(
        DrawerEntry(Screen.RuleSets.route, stringResource(R.string.rulesets_title), Icons.Rounded.Sync, topLevel = false),
        DrawerEntry(Screen.CustomRules.route, stringResource(R.string.custom_rules_title), Icons.Rounded.Language, topLevel = false),
        DrawerEntry(Screen.DomainRules.route, stringResource(R.string.domain_rules_title), Icons.Rounded.Language, topLevel = false),
        DrawerEntry(Screen.AppRouting.route, stringResource(R.string.app_rules_title), Icons.Rounded.Apps, topLevel = false)
    )

    val toolsEntries = listOf(
        DrawerEntry(Screen.Diagnostics.route, stringResource(R.string.diagnostics_title), Icons.Rounded.BugReport, topLevel = false),
        DrawerEntry(Screen.Logs.route, stringResource(R.string.logs_title), Icons.Rounded.History, topLevel = false),
        DrawerEntry(Screen.TrafficStats.route, stringResource(R.string.traffic_stats_title), Icons.Rounded.Analytics, topLevel = false),
        DrawerEntry(
            route = "",
            label = if (isUpdatingRuleSets) updateMessage else stringResource(R.string.settings_update_rulesets),
            icon = Icons.Rounded.Sync,
            topLevel = false,
            action = {
                if (!isUpdatingRuleSets) {
                    isUpdatingRuleSets = true
                    updateMessage = preparingUpdateMsg
                    scope.launch {
                        try {
                            val success = RuleSetRepository.getInstance(context).ensureRuleSetsReady(
                                forceUpdate = true,
                                allowNetwork = true
                            ) {
                                updateMessage = it
                            }
                            updateMessage = if (success) updateSuccessMsg else updateFailedMsg
                            AppNotificationManager.showMessage(
                                context = context,
                                message = if (success) rulesetUpdateSuccessMsg else rulesetUpdateFailedMsg
                            )
                        } catch (e: Exception) {
                            updateMessage = "Error: ${e.message}"
                            AppNotificationManager.showMessage(
                                context = context,
                                message = "$rulesetUpdateFailedMsg: ${e.message}"
                            )
                        } finally {
                            kotlinx.coroutines.delay(1000)
                            isUpdatingRuleSets = false
                        }
                    }
                }
            }
        ),
        DrawerEntry(
            route = "",
            label = stringResource(R.string.settings_debug_mode),
            icon = Icons.Rounded.BugReport,
            topLevel = false,
            switchChecked = settings.debugLoggingEnabled,
            onSwitchChange = { viewModel.setDebugLoggingEnabled(it) }
        )
    )

    val settingsEntries = listOf(
        DrawerEntry(
            route = "",
            label = stringResource(R.string.settings_app_theme),
            icon = Icons.Rounded.Brightness6,
            topLevel = false,
            value = stringResource(settings.appTheme.displayNameRes),
            action = { showThemeDialog = true }
        ),
        DrawerEntry(
            route = "",
            label = stringResource(R.string.settings_app_language),
            icon = Icons.Rounded.Language,
            topLevel = false,
            value = stringResource(settings.appLanguage.displayNameRes),
            action = { showLanguageDialog = true }
        ),
        DrawerEntry(
            route = "",
            label = "检查更新",
            icon = Icons.Rounded.Refresh,
            topLevel = false,
            action = {
                scope.launch {
                    when (val result = com.mubox.app.utils.AppUpdateChecker.check(context)) {
                        is com.mubox.app.utils.UpdateCheckResult.UpdateAvailable ->
                            com.mubox.app.utils.UpdateUiEvents.post(result)
                        is com.mubox.app.utils.UpdateCheckResult.UpToDate ->
                            android.widget.Toast.makeText(context, "已是最新版本", android.widget.Toast.LENGTH_SHORT).show()
                        is com.mubox.app.utils.UpdateCheckResult.Error ->
                            android.widget.Toast.makeText(context, "检查失败：${result.message}", android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        ),
        DrawerEntry(
            route = "",
            label = "自动检查更新",
            icon = Icons.Rounded.SystemUpdate,
            topLevel = false,
            switchChecked = settings.autoCheckUpdate,
            onSwitchChange = { scope.launch { viewModel.setAutoCheckUpdate(it) } }
        ),
        DrawerEntry(
            route = "",
            label = stringResource(R.string.settings_battery_exemption),
            icon = Icons.Rounded.BatterySaver,
            topLevel = false,
            value = if (isIgnoringBatteryOptimizations) {
                stringResource(R.string.settings_battery_exempted)
            } else {
                stringResource(R.string.settings_battery_not_exempted)
            },
            action = {
                if (!isIgnoringBatteryOptimizations) {
                    val intent = Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    ).apply {
                        data = "package:${context.packageName}".toUri()
                    }
                    runCatching { context.startActivity(intent) }
                }
            }
        ),
        DrawerEntry(
            route = "",
            label = stringResource(R.string.settings_ruleset_auto_update),
            icon = Icons.Rounded.Schedule,
            topLevel = false,
            switchChecked = settings.ruleSetAutoUpdateEnabled,
            onSwitchChange = { viewModel.setRuleSetAutoUpdateEnabled(it) }
        ),
        DrawerEntry(
            route = "",
            label = stringResource(R.string.settings_export_data),
            icon = Icons.Rounded.Upload,
            topLevel = false,
            action = {
                exportLauncher.launch(generateExportFileName())
            }
        ),
        DrawerEntry(
            route = "",
            label = stringResource(R.string.settings_import_data),
            icon = Icons.Rounded.Download,
            topLevel = false,
            action = {
                importLauncher.launch(arrayOf("application/json", "*/*"))
            }
        )
    )

    val aboutEntry = DrawerEntry(
        route = "",
        label = stringResource(R.string.settings_about_app),
        icon = Icons.Rounded.Info,
        topLevel = false,
        action = { showAboutDialog = true }
    )

    // Click handling: switches toggle in place without closing the drawer;
    // everything else closes the drawer first, then runs its action or navigates.
    fun onEntryClick(entry: DrawerEntry) {
        if (entry.switchChecked != null) {
            entry.onSwitchChange?.invoke(!entry.switchChecked)
            return
        }
        scope.launch {
            drawerState.close()
            entry.action?.invoke() ?: navigateEntry(navController, entry)
        }
    }

    ModalDrawerSheet(
        modifier = modifier.width(300.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp)
        ) {
            DrawerSection(title = "主页")
            homeEntries.forEach { entry ->
                DrawerRow(
                    entry = entry,
                    currentRoute = currentRoute,
                    onClick = { onEntryClick(entry) }
                )
            }

            Spacer(Modifier.height(16.dp))

            DrawerSection(title = "网络")
            networkEntries.forEach { entry ->
                DrawerRow(
                    entry = entry,
                    currentRoute = currentRoute,
                    onClick = { onEntryClick(entry) }
                )
            }

            Spacer(Modifier.height(16.dp))

            DrawerSection(title = "规则")
            rulesEntries.forEach { entry ->
                DrawerRow(
                    entry = entry,
                    currentRoute = currentRoute,
                    onClick = { onEntryClick(entry) }
                )
            }

            Spacer(Modifier.height(16.dp))

            DrawerSection(title = "工具")
            toolsEntries.forEach { entry ->
                DrawerRow(
                    entry = entry,
                    currentRoute = currentRoute,
                    onClick = { onEntryClick(entry) }
                )
            }

            Spacer(Modifier.height(16.dp))

            DrawerSection(title = stringResource(R.string.settings_title))
            settingsEntries.forEach { entry ->
                DrawerRow(
                    entry = entry,
                    currentRoute = currentRoute,
                    onClick = { onEntryClick(entry) }
                )
            }

            if (settings.ruleSetAutoUpdateEnabled) {
                val intervalEntry = DrawerEntry(
                    route = "",
                    label = stringResource(R.string.settings_update_interval),
                    icon = Icons.Rounded.Schedule,
                    topLevel = false,
                    value = pluralStringResource(
                        R.plurals.settings_update_interval_value,
                        settings.ruleSetAutoUpdateInterval,
                        settings.ruleSetAutoUpdateInterval
                    ),
                    action = { showIntervalDialog = true }
                )
                DrawerRow(
                    entry = intervalEntry,
                    currentRoute = currentRoute,
                    onClick = { onEntryClick(intervalEntry) }
                )
            }

            DrawerRow(
                entry = aboutEntry,
                currentRoute = currentRoute,
                onClick = { onEntryClick(aboutEntry) }
            )

            if (showAboutDialog) {
                AboutDialog(onDismiss = { showAboutDialog = false })
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
    val selected = entry.route.isNotEmpty() && if (entry.topLevel) {
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
        if (entry.value != null) {
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(8.dp))
        }
        if (entry.switchChecked != null) {
            Switch(
                checked = entry.switchChecked,
                onCheckedChange = { entry.onSwitchChange?.invoke(it) },
                colors = liquidGlassSwitchColors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                    uncheckedBorderColor = Color.Transparent
                )
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        }
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
