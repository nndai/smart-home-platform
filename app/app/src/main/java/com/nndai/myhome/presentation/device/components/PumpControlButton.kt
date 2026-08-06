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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.DimText
import com.nndai.myhome.core.theme.ElevatedSurface
import com.nndai.myhome.core.theme.GreenOk

/**
 * Toggle button lớn hình tròn cho ON/OFF pump với viền nổi bật (BorderStroke)
 * và vòng quay nạp (CircularProgressIndicator / Spin) khi đang gửi lệnh chờ phản hồi.
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
    val inactiveBgColor = MaterialTheme.colorScheme.surfaceVariant
    val inactiveBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    val bgColor by animateColorAsState(
        targetValue = if (isOn) activeColor else inactiveBgColor,
        animationSpec = tween(350),
        label = "pumpBg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isOn) activeColor.copy(alpha = 0.5f) else inactiveBorderColor,
        animationSpec = tween(350),
        label = "pumpBorder"
    )
    val iconColor by animateColorAsState(
        targetValue = if (isOn) Color.White else DimText,
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
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(144.dp)
                .scale(buttonScale)
                .shadow(
                    elevation = if (isOn) 24.dp else 8.dp,
                    shape = CircleShape,
                    ambientColor = if (isOn) activeColor else Color.Black,
                    spotColor = if (isOn) activeColor else Color.Black.copy(alpha = 0.2f)
                )
                .clip(CircleShape)
                .background(bgColor)
                .background(Color.White.copy(alpha = 0.2f),

                )
                .border(
                    border = BorderStroke(2.dp, borderColor),
                    shape = CircleShape
                )
                .clickable(
                    enabled = enabled && !isLoading,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(40.dp),
                    color = iconColor,
                    strokeWidth = 5.dp
                )
            } else {
                Icon(
                    imageVector = if (isOn) Icons.Filled.Power else Icons.Filled.PowerOff,
                    contentDescription = if (isOn) stringResource(R.string.pump_btn_turn_off_desc) else stringResource(R.string.pump_btn_turn_on_desc),
                    tint = iconColor,
                    modifier = Modifier.size(60.dp)
                )
            }
        }
        Text(
            text = when {
                isLoading -> stringResource(R.string.pump_btn_processing)
                isOn -> stringResource(R.string.pump_btn_turn_off_relay)
                else -> stringResource(R.string.pump_btn_turn_on_relay)
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = if (isOn) activeColor else DimText
        )
    }
}
