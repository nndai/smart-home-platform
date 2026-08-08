package com.nndai.myhome.presentation.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nndai.myhome.data.repository.AuthRepository
import com.nndai.myhome.data.repository.DeviceManagerRepository
import com.nndai.myhome.presentation.auth.LoginScreen
import com.nndai.myhome.presentation.device.DeviceDetailScreen
import com.nndai.myhome.presentation.device.management.AddDeviceScreen
import com.nndai.myhome.presentation.main.MainScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(
    authRepository: AuthRepository = AuthRepository(),
    deviceRepository: DeviceManagerRepository = DeviceManagerRepository(),
    navController: NavHostController = rememberNavController()
) {
    val isLoggedIn by authRepository.isLoggedIn.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Start directly with the main screen (which contains the bottom navigation)
    val startDestination = "main_screen"

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            LoginScreen(
                onLogin = { email, password ->
                    coroutineScope.launch {
                        val success = authRepository.login(email, password)
                        if (success) {
                            navController.navigate("main_screen") {
                                popUpTo("login") { inclusive = true }
                            }
                        } else {
                            // Can show error toast later
                        }
                    }
                }
            )
        }

        composable("main_screen") {
            LaunchedEffect(isLoggedIn) {
                if (isLoggedIn) {
                    deviceRepository.fetchDevices()
                }
            }
            MainScreen(
                parentNavController = navController,
                authRepository = authRepository,
                deviceRepository = deviceRepository,
                isLoggedIn = isLoggedIn
            )
        }

        composable("add_device") {
            AddDeviceScreen(
                onNavigateBack = { navController.popBackStack() },
                onDeviceAdded = { name, profile, deviceId ->
                    coroutineScope.launch {
                        deviceRepository.addDevice(name, profile, deviceId)
                        navController.popBackStack()
                    }
                }
            )
        }

        composable("device/{deviceId}/{profile}") { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
            val profile = backStackEntry.arguments?.getString("profile") ?: "pump"
            
            DeviceDetailScreen(
                deviceId = deviceId,
                profile = profile,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
