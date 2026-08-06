package com.nndai.myhome.presentation.device

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.nndai.myhome.presentation.device.profiles.pump.DashboardScreen

@Composable
fun DeviceDetailScreen(
    // Normally, we would pass a deviceId here and load the Device details to check its profile.
    // For now, as per requirements, we are hardcoding the Pump profile.
) {
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Route to the specific profile's UI based on device.profile
    // Hardcoded to Pump for now
    DashboardScreen(snackbarHostState = snackbarHostState)
}
