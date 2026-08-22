package com.nndai.myhome.presentation.device.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.core.theme.SecondaryText
import com.nndai.myhome.data.remote.DeviceHealthStatus

/**
 * Animated connection status indicator:
 * - Online / Connected: Breathing/pulsing green dot
 * - Connecting / Handshaking: Spinning Autorenew icon (sized to match text height)
 * - Offline / Disconnected: Static dot (red/grey)
 */
@Composable
fun ConnectionStatusIndicator(
    isConnected: Boolean,
    isConnecting: Boolean,
    modifier: Modifier = Modifier,
    dotSize: Dp = 8.dp,
    iconSize: Dp = 12.dp,
    offlineColor: Color = RedError
) {
    if (isConnected) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse_transition")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulse_alpha"
        )

        Box(
            modifier = modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(GreenOk.copy(alpha = alpha))

        )
    } else if (isConnecting) {
        val infiniteTransition = rememberInfiniteTransition(label = "rotate_transition")
        val angle by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "rotate_angle"
        )

        Icon(
            imageVector = Icons.Filled.Autorenew,
            contentDescription = null,
            tint = OrangeWarning,
            modifier = modifier
                .size(iconSize)
                .rotate(angle)
        )
    } else {
        Box(
            modifier = modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(offlineColor)
        )
    }
}

@Composable
fun DeviceHealthIndicator(
    healthState: DeviceHealthStatus,
    isTransferred: Boolean = false,
    modifier: Modifier = Modifier,
    dotSize: Dp = 6.dp,
    iconSize: Dp = 10.dp
) {
    if (isTransferred) {
        Box(
            modifier = modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(Color(0xFF9C27B0))
        )
    } else {
        val isConnected = healthState is DeviceHealthStatus.Online
        val isConnecting = healthState is DeviceHealthStatus.Handshaking
        ConnectionStatusIndicator(
            isConnected = isConnected,
            isConnecting = isConnecting,
            modifier = modifier,
            dotSize = dotSize,
            iconSize = iconSize,
            offlineColor = SecondaryText
        )
    }
}
