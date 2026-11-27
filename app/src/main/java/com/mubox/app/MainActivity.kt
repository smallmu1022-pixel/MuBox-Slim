package com.mubox.app

import android.content.Intent
import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mubox.app.repository.SettingsRepository
import com.mubox.app.viewmodel.DashboardViewModel
import com.mubox.app.model.ConnectionState
import com.mubox.app.model.AppThemeStyle
import com.mubox.app.utils.LocaleHelper
import com.mubox.app.utils.DeepLinkHandler
import com.mubox.app.ipc.SingBoxRemote
import com.mubox.app.service.VpnTileService
import com.mubox.app.ui.components.AppNotificationManager
import com.mubox.app.ui.components.AppDrawer
import com.mubox.app.ui.navigation.AppNavigation
import com.mubox.app.ui.theme.SingBoxTheme
import com.mubox.app.ui.theme.appBackgroundBrush
import com.mubox.app.ui.theme.LocalLiquidGlassBackdrop
import com.mubox.app.ui.theme.liquidGlassBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import android.content.ComponentName
import android.service.quicksettings.TileService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.mubox.app.ui.scanner.QrScannerActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import java.util.concurrent.atomic.AtomicLong

private data class MainIntentEvent(
    val id: Long,
    val intent: Intent
)

private object MainIntentEvents {
    private val nextIntentEventId = AtomicLong()
    private val _events = MutableStateFlow<MainIntentEvent?>(null)
    val events: StateFlow<MainIntentEvent?> = _events

    fun emit(intent: Intent) {
        _events.value = MainIntentEvent(
            id = nextIntentEventId.incrementAndGet(),
            intent = intent
        )
    }
}

class MainActivity : ComponentActivity() {

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        MainIntentEvents.emit(intent)
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrapFromCache(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        MainIntentEvents.emit(intent)
        SingBoxRemote.ensureBound(this)
        setContent {
            SingBoxApp()
        }
    }
}

@Composable
fun SingBoxApp() {
    val context = LocalContext.current
    val restartNeededMessage = stringResource(R.string.settings_restart_needed)
    val importedSubscriptionDefaultName = stringResource(R.string.profiles_imported_subscription_default_name)

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted ->
        }
    )

    LaunchedEffect(Unit) {
        runCatching {
            TileService.requestListeningState(context, ComponentName(context, VpnTileService::class.java))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)

            if (permission != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val settingsRepository = remember { SettingsRepository.getInstance(context) }
    val settings by settingsRepository.settings.collectAsStateWithLifecycle()
    val dashboardViewModel: DashboardViewModel = viewModel()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        dashboardViewModel.refreshState()
    }

    LaunchedEffect(settings.appLanguage) {
        val language = settings.appLanguage
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        prefs.edit { putString("app_language_cache", language.name) }
    }

    val isVpnRunningForUpdate by SingBoxRemote.isRunning.collectAsStateWithLifecycle()
    var updateChecked by remember { mutableStateOf(false) }

    LaunchedEffect(settings.autoCheckUpdate, isVpnRunningForUpdate) {
        if (!settings.autoCheckUpdate || updateChecked) return@LaunchedEffect

        if (isVpnRunningForUpdate) {
            kotlinx.coroutines.delay(1000L)
            val result = com.mubox.app.utils.AppUpdateChecker.check(context)
            if (result !is com.mubox.app.utils.UpdateCheckResult.Error) {
                updateChecked = true
                if (result is com.mubox.app.utils.UpdateCheckResult.UpdateAvailable) {
                    com.mubox.app.utils.UpdateUiEvents.post(result)
                }
            }
        }
    }

    LaunchedEffect(settings.autoCheckUpdate) {
        if (!settings.autoCheckUpdate) return@LaunchedEffect
        kotlinx.coroutines.delay(10000L)
        val retryDelaysMs = longArrayOf(0L, 90_000L, 300_000L)
        for (delayMs in retryDelaysMs) {
            if (updateChecked) break
            if (delayMs > 0L) kotlinx.coroutines.delay(delayMs)
            if (updateChecked) break
            val result = com.mubox.app.utils.AppUpdateChecker.check(context)
            if (result !is com.mubox.app.utils.UpdateCheckResult.Error) {
                updateChecked = true
                if (result is com.mubox.app.utils.UpdateCheckResult.UpdateAvailable) {
                    com.mubox.app.utils.UpdateUiEvents.post(result)
                }
                break
            }
        }
    }

    var pendingNavigation by remember { mutableStateOf<String?>(null) }
    val intentEvent by MainIntentEvents.events.collectAsStateWithLifecycle()

    LaunchedEffect(intentEvent?.id) {
        val intent = intentEvent?.intent ?: return@LaunchedEffect
        when (intent.action) {
            "com.mubox.app.action.SCAN" -> {
                val scanIntent = android.content.Intent(context, QrScannerActivity::class.java)
                context.startActivity(scanIntent)
                intent.action = null
            }
            "com.mubox.app.action.SWITCH_NODE" -> {
                pendingNavigation = "nodes"
                intent.action = null
            }
            android.content.Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    val scheme = uri.scheme
                    val host = uri.host

                    if ((scheme == "singbox" || scheme == "mubox") && host == "install-config") {
                        val url = uri.getQueryParameter("url")
                        val name = uri.getQueryParameter("name")
                            ?: importedSubscriptionDefaultName
                        val intervalStr = uri.getQueryParameter("interval")
                        val interval = intervalStr?.toIntOrNull() ?: 0

                        if (!url.isNullOrBlank()) {
                            DeepLinkHandler.setPendingSubscriptionImport(name, url, interval)
                            pendingNavigation = "profiles"
                        }
                    }
                }
                intent.data = null
            }
        }
    }
    val connectionState by dashboardViewModel.connectionState.collectAsStateWithLifecycle()
    val isRunning by SingBoxRemote.isRunning.collectAsStateWithLifecycle()
    val isStarting by SingBoxRemote.isStarting.collectAsStateWithLifecycle()
    var autoConnectAttempted by remember { mutableStateOf(false) }

    LaunchedEffect(isRunning, isStarting) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.mubox.app.utils.NetworkClient.clearConnectionPool()
        }
    }

    LaunchedEffect(settings.autoConnect, connectionState, isRunning, isStarting) {
        if (autoConnectAttempted || !settings.autoConnect) return@LaunchedEffect

        SingBoxRemote.ensureBound(context)
        repeat(5) {
            if (SingBoxRemote.isBound()) return@repeat
            delay(300L)
        }
        if (!SingBoxRemote.isBound()) return@LaunchedEffect
        if (!SingBoxRemote.queryAndSyncState(context)) return@LaunchedEffect

        if (SingBoxRemote.isRunning.value || SingBoxRemote.isStarting.value) {
            autoConnectAttempted = true
            return@LaunchedEffect
        }

        fun shouldAutoConnectNow(): Boolean {
            return settings.autoConnect &&
                connectionState == ConnectionState.Idle &&
                !SingBoxRemote.isRunning.value &&
                !SingBoxRemote.isStarting.value
        }

        if (shouldAutoConnectNow()) {
            delay(1_000L)
            if (SingBoxRemote.queryAndSyncState(context) && shouldAutoConnectNow()) {
                autoConnectAttempted = true
                dashboardViewModel.toggleConnection()
            }
        }
    }

    LaunchedEffect(settings.excludeFromRecent) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        am?.appTasks?.forEach {
            it.setExcludeFromRecents(settings.excludeFromRecent)
        }
    }

    LaunchedEffect(Unit) {
        SettingsRepository.restartRequiredEvents.collectLatest {
            if (!SingBoxRemote.isRunning.value && !SingBoxRemote.isStarting.value) return@collectLatest
            AppNotificationManager.showMessage(
                context = context,
                message = restartNeededMessage
            )
        }
    }

    val appTheme = settings.appTheme
    val appThemeStyle = AppThemeStyle.DEFAULT

    SingBoxTheme(appTheme = appTheme) {
        val navController = rememberNavController()
        val useLiquidGlassNav = appThemeStyle == AppThemeStyle.LIQUID_GLASS
        val liquidGlassContentBackdrop = rememberLayerBackdrop()
        val liquidGlassComponentBackdrop = rememberLayerBackdrop()
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        LaunchedEffect(pendingNavigation) {
            pendingNavigation?.let { route ->
                delay(100)
                navController.navigate(route) {
                    popUpTo(navController.graph.startDestinationId) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
                pendingNavigation = null
            }
        }

        val navBackStackEntry = navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry.value?.destination?.route
        val rootContainerColor = Color.Transparent

        BackHandler(enabled = drawerState.isOpen) {
            scope.launch { drawerState.close() }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                AppDrawer(
                    navController = navController,
                    currentRoute = currentRoute,
                    drawerState = drawerState
                )
            }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(appBackgroundBrush())
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (useLiquidGlassNav) {
                                Modifier.layerBackdrop(liquidGlassComponentBackdrop)
                            } else {
                                Modifier
                            }
                        )
                        .liquidGlassBackdrop()
                )

                CompositionLocalProvider(
                    LocalLiquidGlassBackdrop provides if (useLiquidGlassNav) {
                        liquidGlassComponentBackdrop
                    } else {
                        null
                    }
                ) {
                    Scaffold(
                        containerColor = rootContainerColor,
                        contentWindowInsets = WindowInsets(0, 0, 0, 0)
                    ) { innerPadding ->
                        val contentModifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)

                        if (useLiquidGlassNav) {
                            Box(
                                modifier = contentModifier
                                    .layerBackdrop(liquidGlassContentBackdrop)
                                    .background(rootContainerColor)
                            ) {
                                AppNavigation(
                                    navController = navController,
                                    dashboardViewModel = dashboardViewModel,
                                    dashboardBottomContentPadding = 0.dp,
                                    topLevelBottomContentPadding = 0.dp
                                )
                            }
                        } else {
                            Surface(
                                modifier = contentModifier,
                                color = rootContainerColor
                            ) {
                                AppNavigation(
                                    navController = navController,
                                    dashboardViewModel = dashboardViewModel,
                                    dashboardBottomContentPadding = 0.dp,
                                    topLevelBottomContentPadding = 0.dp
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { scope.launch { drawerState.open() } },
                        modifier = Modifier
                            .statusBarsPadding()
                            .padding(8.dp)
                            .align(Alignment.TopStart)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Menu,
                            contentDescription = stringResource(R.string.common_menu),
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                }
            }
        }

        val pendingUpdate by com.mubox.app.utils.UpdateUiEvents.pending.collectAsStateWithLifecycle()
        pendingUpdate?.let { update ->
            com.mubox.app.ui.components.AppUpdateDialog(
                update = update,
                onDismiss = { com.mubox.app.utils.UpdateUiEvents.clear() }
            )
        }
    }
}
