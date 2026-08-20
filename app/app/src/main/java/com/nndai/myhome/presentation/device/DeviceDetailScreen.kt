package com.nndai.myhome.presentation.device

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nndai.myhome.R
import com.nndai.myhome.data.di.PumpRepositoryProvider
import com.nndai.myhome.presentation.device.profiles.pump.DashboardScreen
import com.nndai.myhome.presentation.device.profiles.pump.deviceinfo.DeviceInfoScreen
import com.nndai.myhome.presentation.device.profiles.pump.history.EnergyHistoryScreen
import com.nndai.myhome.presentation.device.profiles.pump.log.LogScreen
import com.nndai.myhome.presentation.device.profiles.pump.settings.SettingsScreen

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
    onNavigateBack: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    val tabs = remember {
        listOf(
            DeviceTabItem(R.string.nav_dashboard, Icons.Filled.Dashboard, Icons.Outlined.Dashboard),
            DeviceTabItem(R.string.nav_history, Icons.Filled.BarChart, Icons.Outlined.BarChart),
            DeviceTabItem(R.string.nav_log, Icons.Filled.Terminal, Icons.Filled.Terminal),
            DeviceTabItem(R.string.nav_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
            DeviceTabItem(R.string.nav_system, Icons.Filled.Info, Icons.Outlined.Info)
        )
    }

    LaunchedEffect(deviceId) {
        if (deviceId.isNotBlank()) {
            PumpRepositoryProvider.setActiveDeviceId(deviceId)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (profile.equals("pump", ignoreCase = true)) "Pump Control" else "${profile.replaceFirstChar { it.uppercase() }} Control",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = deviceId,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            if (profile.equals("pump", ignoreCase = true)) {
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
