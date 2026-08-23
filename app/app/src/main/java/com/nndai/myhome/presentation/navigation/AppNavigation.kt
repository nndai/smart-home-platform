package com.nndai.myhome.presentation.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.nndai.myhome.BuildConfig
import com.nndai.myhome.data.repository.AuthRepository
import com.nndai.myhome.data.repository.DeviceManagerRepository
import com.nndai.myhome.presentation.auth.LoginScreen
import com.nndai.myhome.presentation.device.DeviceDetailScreen
import com.nndai.myhome.presentation.device.share.MemberManageScreen
import com.nndai.myhome.presentation.main.MainScreen
import com.nndai.myhome.presentation.pairing.PairingFlowScreen
import kotlinx.coroutines.launch

@Composable
fun AppNavigation(
    authRepository: AuthRepository = AuthRepository(),
    deviceRepository: DeviceManagerRepository = DeviceManagerRepository(LocalContext.current.applicationContext),
    navController: NavHostController = rememberNavController()
) {
    val isLoggedIn by authRepository.isLoggedIn.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Register for the session listener (purges control keys + device cache on sign-out)
    com.nndai.myhome.data.di.PumpRepositoryProvider.deviceManagerRepository = deviceRepository

    // Start directly with the main screen (which contains the bottom navigation)
    val startDestination = "main_screen"

    NavHost(navController = navController, startDestination = startDestination) {
        composable("login") {
            val context = LocalContext.current
            var onGoogleFinishedCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

            // Google Sign-In Activity Result Launcher
            val googleSignInLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val idToken = account?.idToken
                    if (idToken != null) {
                        coroutineScope.launch {
                            val success = authRepository.signInWithIdToken(idToken)
                            if (success) {
                                navController.navigate("main_screen") {
                                    popUpTo("login") { inclusive = true }
                                }
                            } else {
                                onGoogleFinishedCallback?.invoke()
                            }
                        }
                    } else {
                        onGoogleFinishedCallback?.invoke()
                    }
                } catch (e: Exception) {
                    // Triggers when user cancels the Google Account Chooser dialog or goes back
                    e.printStackTrace()
                    onGoogleFinishedCallback?.invoke()
                }
            }

            LoginScreen(
                onLogin = { email, password, onFinished ->
                    coroutineScope.launch {
                        val success = authRepository.login(email, password)
                        if (success) {
                            navController.navigate("main_screen") {
                                popUpTo("login") { inclusive = true }
                            }
                        } else {
                            onFinished()
                        }
                    }
                },
                onGoogleSignIn = { onFinished ->
                    onGoogleFinishedCallback = onFinished
                    try {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                            .requestEmail()
                            .build()
                        val googleSignInClient = GoogleSignIn.getClient(context, gso)
                        googleSignInClient.signOut().addOnCompleteListener {
                            googleSignInLauncher.launch(googleSignInClient.signInIntent)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // Fallback to Credential Manager if Play Services fails
                        coroutineScope.launch {
                            val success = authRepository.signInWithGoogle(context)
                            if (success) {
                                navController.navigate("main_screen") {
                                    popUpTo("login") { inclusive = true }
                                }
                            } else {
                                onFinished()
                            }
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

        composable("pairing") {
            PairingFlowScreen(
                onNavigateBack = { navController.popBackStack() },
                // Claim thiết bị (RPC claim_device) đã được xử lý tự động trong
                // PairingRepository (retry + lưu tạm khi mất mạng) — chỉ quay lại.
                onPairComplete = { _, _, _, _ ->
                    navController.popBackStack()
                }
            )
        }

        composable("device/{deviceId}/{profile}") { backStackEntry ->
            val deviceId = backStackEntry.arguments?.getString("deviceId") ?: ""
            val profile = backStackEntry.arguments?.getString("profile") ?: "pump"

            DeviceDetailScreen(
                deviceId = deviceId,
                profile = profile,
                deviceRepository = deviceRepository,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        // Quản lý thành viên/thiết bị chia sẻ. deviceUuid = devices.id (UUID),
        // deviceKey = device_id ("dev-xxx") dùng cho các luồng MQTT sẵn có.
        composable("members/{deviceUuid}/{deviceKey}/{deviceName}") { backStackEntry ->
            MemberManageScreen(
                deviceUuid = backStackEntry.arguments?.getString("deviceUuid") ?: "",
                deviceKey = backStackEntry.arguments?.getString("deviceKey") ?: "",
                deviceName = backStackEntry.arguments?.getString("deviceName") ?: "",
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
