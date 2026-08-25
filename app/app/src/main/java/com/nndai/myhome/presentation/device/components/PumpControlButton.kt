package com.nndai.myhome.presentation.device.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.DeepNavy
import com.nndai.myhome.core.theme.GreenOk

/**
 * Large circular power toggle for the pump.
 * Both states are deliberately loud so users instantly recognize it as THE
 * button: solid brand-colored disc (blue = OFF, green = ON), thick double
 * border + outer halo ring + colored glow shadow.
 */
@Composable
fun PumpControlButton(
    isOn: Boolean,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = GreenOk
    val idleColor = DeepNavy          // OFF: đậm, không phải xanh dương
    val offBorderColor = CyanBlue     // viền sáng để vẫn nổi bật trên nền tối

    val bgColor by animateColorAsState(
        targetValue = if (isOn) activeColor else idleColor,
        animationSpec = tween(350),
        label = "pumpBg"
    )
    // Viền: ON = xanh lá, OFF = cyan sáng nổi trên nền tối
    val borderColor by animateColorAsState(
        targetValue = if (isOn) activeColor else offBorderColor,
        animationSpec = tween(350),
        label = "pumpBorder"
    )
    // Chữ nhãn luôn dùng màu accent dễ đọc (không dùng màu nền đậm)
    val labelColor by animateColorAsState(
        targetValue = if (isOn) activeColor else offBorderColor,
        animationSpec = tween(350),
        label = "pumpLabel"
    )
    val iconColor by animateColorAsState(
        targetValue = Color.White,
        animationSpec = tween(350),
        label = "pumpIcon"
    )
    val buttonScale by animateFloatAsState(
        targetValue = if (enabled && !isLoading) 1f else 0.94f,
        animationSpec = tween(200),
        label = "pumpScale"
    )

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Outer halo ring ──
        Box(
            modifier = Modifier.size(156.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(156.dp)
                    .clip(CircleShape)
                    .background(bgColor.copy(alpha = 0.12f))
            )

            // ── Main disc: colored glow shadow + double border ──
            Box(
                modifier = Modifier
                    .size(144.dp)
                    .scale(buttonScale)
                    .shadow(
                        elevation = 16.dp,
                        shape = CircleShape,
                        ambientColor = bgColor,
                        spotColor = bgColor
                    )
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(BorderStroke(3.dp, borderColor), CircleShape)
                    .border(BorderStroke(8.dp, borderColor.copy(alpha = 0.25f)), CircleShape)
                    .clickable(
                        enabled = enabled && !isLoading,
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = onClick
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Top-light sheen for a raised, glossy look
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                )
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(44.dp),
                        color = Color.White,
                        strokeWidth = 5.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isOn) Icons.Filled.Power else Icons.Filled.PowerOff,
                        contentDescription = if (isOn) stringResource(R.string.pump_btn_turn_off_desc)
                        else stringResource(R.string.pump_btn_turn_on_desc),
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }
            }
        }

        Text(
            text = when {
                isLoading -> stringResource(R.string.pump_btn_processing)
                isOn -> stringResource(R.string.pump_btn_turn_off_relay)
                else -> stringResource(R.string.pump_btn_turn_on_relay)
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = labelColor
        )
    }
}
