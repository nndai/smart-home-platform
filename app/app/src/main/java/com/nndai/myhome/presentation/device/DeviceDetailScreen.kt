package com.nndai.myhome.presentation.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.presentation.device.components.ConnectionStatusIndicator
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.data.model.ConnectionState
import com.nndai.myhome.data.repository.DeviceManagerRepository
import com.nndai.myhome.presentation.device.common.deviceinfo.DeviceInfoScreen
import com.nndai.myhome.presentation.device.common.history.ToggleHistoryScreen
import com.nndai.myhome.presentation.device.common.log.LogScreen
import com.nndai.myhome.presentation.device.profiles.pump.DashboardScreen
import com.nndai.myhome.presentation.device.profiles.pump.history.EnergyHistoryScreen
import com.nndai.myhome.presentation.device.profiles.pump.settings.SettingsScreen
import com.nndai.myhome.presentation.device.profiles.remoteswitch.RemoteSwitchDashboardScreen
import com.nndai.myhome.presentation.device.profiles.remoteswitch.RemoteSwitchSettingsScreen

private data class DeviceTabItem(
    val route: String,
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailScreen(
    deviceId: String,
    profile: String,
    deviceRepository: DeviceManagerRepository? = null,
    onNavigateBack: () -> Unit
) {
    // Set active device synchronously before any child view models or composables run
    if (deviceId.isNotBlank() && PumpRepositoryProvider.getActiveDeviceId() != deviceId) {
        PumpRepositoryProvider.setActiveDeviceId(deviceId)
    }

    val repository = remember(deviceId) { PumpRepositoryProvider.provide(deviceId) }
    val connectionState by repository.connectionState.collectAsStateWithLifecycle()
    val pumpStatus by repository.pumpStatus.collectAsStateWithLifecycle()
    val statusLatencyMs by repository.statusLatencyMs.collectAsStateWithLifecycle()

    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceManager = remember { deviceRepository ?: DeviceManagerRepository(context.applicationContext) }
    val devices by deviceManager.devices.collectAsStateWithLifecycle()
    val device = remember(devices, deviceId) { devices.find { it.device_id == deviceId } }
    val deviceName = device?.name ?: when (profile.lowercase()) {
        "pump" -> stringResource(R.string.pair_profile_pump)
        "remote_switch" -> "Remote Switch"
        else -> "${profile.replaceFirstChar { it.uppercase() }} Control"
    }

    val isConnected = connectionState is ConnectionState.Connected
    val isConnecting = connectionState is ConnectionState.Connecting || connectionState is ConnectionState.TransportReady
    val rssi = pumpStatus?.rssi ?: 0

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val isPump = profile.equals("pump", ignoreCase = true)
    val isRemoteSwitch = profile.equals("remote_switch", ignoreCase = true)

    // VIEWER của thiết bị được chia sẻ: chỉ xem — không điều khiển, không cài đặt.
    // (Firmware vẫn là ranh giới cuối: VIEWER không có control_key để ký lệnh.)
    val isViewer = device?.role?.uppercase() == com.nndai.myhome.data.model.DeviceRoles.VIEWER

    // Settings tab là tập hợp lệnh ghi (setConfig/reboot/factory reset) → ẩn
    // hẳn với VIEWER. Dispatch nội dung theo route key để không lệch index
    // khi danh sách tab thay đổi.
    val tabs = remember(profile, isViewer) {
        buildList {
            add(DeviceTabItem("dash", R.string.nav_dashboard, Icons.Filled.Dashboard, Icons.Outlined.Dashboard))
            add(DeviceTabItem("hist", R.string.nav_history, Icons.Filled.BarChart, Icons.Outlined.BarChart))
            add(DeviceTabItem("log", R.string.nav_log, Icons.Filled.Terminal, Icons.Filled.Terminal))
            if (!isViewer) {
                add(DeviceTabItem("settings", R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings))
            }
            add(DeviceTabItem("sysinfo", R.string.nav_system, Icons.Filled.Info, Icons.Outlined.Info))
        }
    }
    val currentTabRoute = tabs.getOrNull(selectedTabIndex)?.route ?: "dash"

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Manage device streaming lifecycle: start/stop based on app foreground/background and exit
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, deviceId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                // Resume streams based on current tab
                when (currentTabRoute) {
                    "sysinfo" -> repository.ensureSysInfoStream()
                    "settings" -> { /* settings không stream */ }
                    else -> repository.ensureStatusStream()
                }
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Stop streams when app goes to background
                repository.stopAllStreams()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Start status stream on first entry (if not in settings/sysinfo tab)
        when (currentTabRoute) {
            "sysinfo" -> repository.ensureSysInfoStream()
            "settings" -> { /* settings không stream */ }
            else -> repository.ensureStatusStream()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            repository.stopAllStreams()
        }
    }

    // Pre-fetch initial config and sysinfo (no stream) on entering device screen to optimize UX
    LaunchedEffect(deviceId) {
        repository.refreshConfig()
        repository.refreshInfo(stream = false)
    }

    // Handle tab switching
    LaunchedEffect(currentTabRoute) {
        when (currentTabRoute) {
            "settings" -> { // Settings
                repository.stopSysInfoStream()
                repository.refreshConfig()
            }
            "sysinfo" -> { // System info
                repository.ensureSysInfoStream()
            }
            else -> { // Dashboard, History, Log
                repository.stopSysInfoStream()
                repository.ensureStatusStream()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.Center) {
                        Text(
                            text = deviceName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ConnectionStatusIndicator(
                                isConnected = isConnected,
                                isConnecting = isConnecting,
                                dotSize = 6.dp,
                                iconSize = 11.dp
                            )
                        Text(
                            text = when {
                                isConnected -> stringResource(R.string.conn_connected)
                                isConnecting -> stringResource(R.string.conn_connecting)
                                else -> stringResource(R.string.conn_disconnected)
                            },
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium,
                                color = if (isConnected) GreenOk else ( if (isConnecting) OrangeWarning else MaterialTheme.colorScheme.onSurfaceVariant)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                },
                actions = {
                    // RSSI Signal (dBm) [Top] + Latency (ms) [Bottom] Right-Aligned Stacked Pill
                    val pillColor = if (isConnected) CyanBlue else RedError
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = pillColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, pillColor.copy(alpha = 0.3f)),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(1.dp)
                        ) {
                            // Row 1 (Top): WiFi RSSI (dBm)
                            if (isConnected && rssi != 0) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Wifi,
                                        contentDescription = null,
                                        tint = CyanBlue,
                                        modifier = Modifier.size(11.dp)
                                    )
                                    Text(
                                        text = "$rssi dBm",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = CyanBlue
                                    )
                                }
                            }

                            // Row 2 (Bottom): Latency (ms) or 9999 (when disconnected)
                            Text(
                                text = if (!isConnected) "+9999ms" else (statusLatencyMs?.let { "${it}ms" } ?: "--ms"),
                                style = MaterialTheme.typography.labelSmall,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isConnected) RedError else CyanBlue
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (isPump || isRemoteSwitch) {
                NavigationBar(
                    modifier = Modifier
                        .navigationBarsPadding()
                        .height(60.dp),
                    windowInsets = WindowInsets(0),
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    tabs.forEachIndexed { index, tabItem ->
                        val selected = selectedTabIndex == index
                        NavigationBarItem(
                            selected = selected,
                            onClick = { selectedTabIndex = index },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tabItem.selectedIcon else tabItem.unselectedIcon,
                                    contentDescription = stringResource(tabItem.titleRes)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(tabItem.titleRes),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ── VIEWER banner: chế độ chỉ xem ──
            if (isViewer) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = stringResource(R.string.viewer_banner),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            when (profile.lowercase()) {
                "pump" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        when (currentTabRoute) {
                            "dash" -> DashboardScreen(
                                snackbarHostState = snackbarHostState,
                                readOnly = isViewer
                            )
                            "hist" -> EnergyHistoryScreen()
                            "log" -> LogScreen(bottomPadding = paddingValues.calculateBottomPadding())
                            "settings" -> SettingsScreen(snackbarHostState = snackbarHostState)
                            "sysinfo" -> DeviceInfoScreen()
                        }
                    }
                }
                "remote_switch" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        when (currentTabRoute) {
                            "dash" -> RemoteSwitchDashboardScreen(
                                snackbarHostState = snackbarHostState,
                                readOnly = isViewer
                            )
                            "hist" -> ToggleHistoryScreen()
                            "log" -> LogScreen(bottomPadding = paddingValues.calculateBottomPadding())
                            "settings" -> RemoteSwitchSettingsScreen(snackbarHostState = snackbarHostState)
                            "sysinfo" -> DeviceInfoScreen()
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.profile_coming_soon, profile),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
