package com.nndai.myhome.presentation.device.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Draws a WiFi signal strength icon with 4 levels (dot + 3 arcs).
 * Levels are determined by RSSI thresholds:
 *   >= -55 dBm -> 4 bars (excellent)
 *   >= -67 dBm -> 3 bars (good)
 *   >= -78 dBm -> 2 bars (fair)
 *   else       -> 1 bar  (weak)
 */
@Composable
fun WifiSignalBars(
    rssi: Int,
    modifier: Modifier = Modifier
) {
    val activeLevel = when {
        rssi >= -55 -> 4
        rssi >= -67 -> 3
        rssi >= -78 -> 2
        else -> 1
    }

    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)

    Canvas(
        modifier = modifier.size(20.dp)
    ) {
        val strokeWidth = 2.dp.toPx()
        val centerX = size.width / 2f
        val centerY = size.height - strokeWidth / 2f

        // Center dot (level 1)
        val dotRadius = strokeWidth * 0.9f
        drawCircle(
            color = if (activeLevel >= 1) activeColor else inactiveColor,
            radius = dotRadius,
            center = Offset(centerX, centerY - dotRadius)
        )

        // Concentric arcs (levels 2-4)
        val radii = listOf(
            strokeWidth * 2.6f,
            strokeWidth * 4.4f,
            strokeWidth * 6.2f
        )

        for (i in 0 until 3) {
            val level = i + 2
            val r = radii[i]
            val color = if (activeLevel >= level) activeColor else inactiveColor

            drawArc(
                color = color,
                startAngle = 225f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(centerX - r, (centerY - dotRadius) - r),
                size = Size(r * 2f, r * 2f),
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round
                )
            )
        }
    }
}
