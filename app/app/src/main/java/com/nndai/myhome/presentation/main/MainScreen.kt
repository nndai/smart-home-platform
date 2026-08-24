package com.nndai.myhome.presentation.main

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nndai.myhome.R
import com.nndai.myhome.data.repository.AuthRepository
import com.nndai.myhome.data.repository.DeviceManagerRepository
import com.nndai.myhome.presentation.device.management.DeviceManagementScreen
import com.nndai.myhome.presentation.home.HomeDashboardScreen
import com.nndai.myhome.presentation.profile.ProfileScreen

sealed class BottomNavItem(
    val route: String,
    val titleRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Dashboard : BottomNavItem("tab_dashboard", R.string.nav_home, Icons.Filled.Home, Icons.Outlined.Home)
    data object Devices : BottomNavItem("tab_devices", R.string.nav_devices, Icons.Filled.Devices, Icons.Outlined.Devices)
    data object Profile : BottomNavItem("tab_profile", R.string.nav_profile, Icons.Filled.Person, Icons.Outlined.Person)
}

private val bottomNavItems = listOf(
    BottomNavItem.Dashboard,
    BottomNavItem.Devices,
    BottomNavItem.Profile
)

@Composable
fun MainScreen(
    parentNavController: NavHostController,
    authRepository: AuthRepository,
    deviceRepository: DeviceManagerRepository,
    isLoggedIn: Boolean
) {
    val bottomNavController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionColor = MaterialTheme.colorScheme.primary,
                    shape = MaterialTheme.shapes.medium
                )
            }
        },
        bottomBar = {
            NavigationBar(
                modifier = Modifier
                    .navigationBarsPadding()
                    .height(64.dp),
                windowInsets = WindowInsets(0),
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface
            ) {
                val navBackStackEntry by bottomNavController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination

                bottomNavItems.forEach { item ->
                    val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                    val title = stringResource(item.titleRes)

                    NavigationBarItem(
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = title,
                                modifier = Modifier.size(22.dp)
                            )
                        },
                        label = {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        selected = selected,
                        onClick = {
                            bottomNavController.navigate(item.route) {
                                popUpTo(bottomNavController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
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
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        NavHost(
            navController = bottomNavController,
            startDestination = BottomNavItem.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(BottomNavItem.Dashboard.route) {
                HomeDashboardScreen(
                    deviceRepository = deviceRepository,
                    isLoggedIn = isLoggedIn,
                    onNavigateToLogin = { parentNavController.navigate("login") },
                    onNavigateToDevice = { deviceId, profile ->
                        parentNavController.navigate("device/$deviceId/$profile")
                    }
                )
            }
            composable(BottomNavItem.Devices.route) {
                DeviceManagementScreen(
                    deviceRepository = deviceRepository,
                    isLoggedIn = isLoggedIn,
                    onNavigateToLogin = { parentNavController.navigate("login") },
                    onAddDeviceClick = { parentNavController.navigate("pairing") },
                    onNavigateToDevice = { deviceId, profile ->
                        parentNavController.navigate("device/$deviceId/$profile")
                    },
                    onManageMembers = { uuid, key, name ->
                        parentNavController.navigate(
                            "members/$uuid/$key/${android.net.Uri.encode(name)}"
                        )
                    }
                )
            }
            composable(BottomNavItem.Profile.route) {
                ProfileScreen(
                    authRepository = authRepository,
                    isLoggedIn = isLoggedIn,
                    onNavigateToLogin = { parentNavController.navigate("login") }
                )
            }
        }
    }
}
