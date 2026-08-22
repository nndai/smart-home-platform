package com.nndai.myhome.presentation.device

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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

    val context = androidx.compose.ui.platform.LocalContext.current
    val deviceManager = remember { deviceRepository ?: DeviceManagerRepository(context.applicationContext) }
    val devices by deviceManager.devices.collectAsStateWithLifecycle()
    val device = remember(devices, deviceId) { devices.find { it.device_id == deviceId } }
    val deviceName = device?.name ?: when (profile.lowercase()) {
        "pump" -> "Máy Bơm (Pump)"
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

    val tabs = remember(profile) {
        listOf(
            DeviceTabItem(R.string.nav_dashboard, Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
            DeviceTabItem(R.string.nav_history, Icons.Filled.BarChart, Icons.Outlined.BarChart),
            DeviceTabItem(R.string.nav_log, Icons.Filled.Terminal, Icons.Filled.Terminal),
            DeviceTabItem(R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
            DeviceTabItem(R.string.nav_system, Icons.Filled.Info, Icons.Outlined.Info)
        )
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Manage device streaming lifecycle: start/stop based on app foreground/background and exit
    androidx.compose.runtime.DisposableEffect(lifecycleOwner, deviceId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                // Resume streams based on current tab
                if (selectedTabIndex == 4) {
                    repository.ensureSysInfoStream()
                } else if (selectedTabIndex != 3) {
                    repository.ensureStatusStream()
                }
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                // Stop streams when app goes to background
                repository.stopAllStreams()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Start status stream on first entry (if not in settings/sysinfo tab)
        if (selectedTabIndex != 3 && selectedTabIndex != 4) {
            repository.ensureStatusStream()
        } else if (selectedTabIndex == 4) {
            repository.ensureSysInfoStream()
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
    LaunchedEffect(selectedTabIndex) {
        when (selectedTabIndex) {
            3 -> { // Settings
                repository.stopSysInfoStream()
                repository.refreshConfig()
            }
            4 -> { // System info
                repository.ensureSysInfoStream()
            }
            else -> { // Dashboard (0), History (1), Log (2)
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
                                    isConnected -> "Đã kết nối qua MQTT"
                                    isConnecting -> "Đang kết nối..."
                                    else -> "Mất kết nối"
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
                    // RSSI Signal dBm Pill
                    if (rssi != 0) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = CyanBlue.copy(alpha = 0.12f),
                            border = BorderStroke(1.dp, CyanBlue.copy(alpha = 0.3f)),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Wifi,
                                    contentDescription = null,
                                    tint = CyanBlue,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "$rssi dBm",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanBlue
                                )
                            }
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
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.height(64.dp)
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
            when (profile.lowercase()) {
                "pump" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        when (selectedTabIndex) {
                            0 -> DashboardScreen(snackbarHostState = snackbarHostState)
                            1 -> EnergyHistoryScreen()
                            2 -> LogScreen()
                            3 -> SettingsScreen(snackbarHostState = snackbarHostState)
                            4 -> DeviceInfoScreen()
                        }
                    }
                }
                "remote_switch" -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        when (selectedTabIndex) {
                            0 -> RemoteSwitchDashboardScreen(snackbarHostState = snackbarHostState)
                            1 -> ToggleHistoryScreen()
                            2 -> LogScreen()
                            3 -> RemoteSwitchSettingsScreen(snackbarHostState = snackbarHostState)
                            4 -> DeviceInfoScreen()
                        }
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Coming soon for $profile",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
