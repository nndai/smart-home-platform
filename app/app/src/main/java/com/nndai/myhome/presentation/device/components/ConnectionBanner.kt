package com.nndai.myhome.presentation.device.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nndai.myhome.R
import com.nndai.myhome.data.model.ConnectionState
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.core.theme.SecondaryText

/**
 * Banner hiển thị trạng thái kết nối.
 * Hiển thị rõ ràng từng giai đoạn: Connecting → Transport Ready → Device Connected.
 * Tap để reconnect khi disconnected/failed.
 */
@Composable
fun ConnectionBanner(
    state: ConnectionState,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isSpinning = state is ConnectionState.Connecting || state is ConnectionState.TransportReady

    val (icon, text, color) = when (state) {
        is ConnectionState.Connected -> {
            val via = if (state.channel.isNotBlank()) state.channel else ""
            Triple(
                Icons.Filled.CloudDone,
                if (via.isNotBlank()) stringResource(R.string.conn_connected_via, via)
                else stringResource(R.string.conn_connected),
                GreenOk
            )
        }
        is ConnectionState.TransportReady -> {
            Triple(Icons.Outlined.Autorenew, stringResource(R.string.conn_transport_ready, state.channel), CyanBlue)
        }
        is ConnectionState.Connecting -> {
            Triple(Icons.Outlined.Autorenew, stringResource(R.string.conn_connecting), OrangeWarning)
        }
        is ConnectionState.Disconnected -> {
            Triple(Icons.Filled.CloudOff, stringResource(R.string.conn_disconnected), RedError)
        }
        is ConnectionState.Failed -> {
            Triple(Icons.Filled.SyncProblem, stringResource(R.string.conn_failed), RedError)
        }
        is ConnectionState.Idle -> {
            Triple(Icons.Filled.CloudOff, stringResource(R.string.conn_idle), SecondaryText)
        }
    }

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(400),
        label = "bannerColor"
    )

    // Infinite spin animation for Connecting / TransportReady states
    val infiniteTransition = rememberInfiniteTransition(label = "syncSpin")
    val spinAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "spinAngle"
    )

    val isClickable = state is ConnectionState.Disconnected || state is ConnectionState.Failed

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(animatedColor.copy(alpha = 0.12f))
            .then(
                if (isClickable) Modifier.clickable(onClick = onReconnect) else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = animatedColor,
            modifier = Modifier
                .size(18.dp)
                .graphicsLayer {
                    rotationZ = if (isSpinning) spinAngle else 0f
                }

        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = animatedColor
        )
    }
}
