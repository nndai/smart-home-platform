package com.nndai.myhome.presentation.device.common.history

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.data.repository.LogRepository
import java.util.Calendar
import java.util.Locale

/**
 * Visual Day/Calendar Picker Dialog with month navigation, available date indicators (dots),
 * and quick "Today" shortcut.
 */
@Composable
fun CustomVisualCalendarDialog(
    title: String,
    selectedDateStr: String,
    availableDates: List<String>,
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val todayStr = remember { LogRepository.getTodayDateStr() }

    val initialDateParts = remember(selectedDateStr) {
        val parts = selectedDateStr.split("-")
        if (parts.size >= 3) {
            Triple(
                parts[0].toIntOrNull() ?: 1,
                parts[1].toIntOrNull() ?: (Calendar.getInstance().get(Calendar.MONTH) + 1),
                parts[2].toIntOrNull() ?: Calendar.getInstance().get(Calendar.YEAR)
            )
        } else {
            val cal = Calendar.getInstance()
            Triple(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR))
        }
    }

    var displayYear by remember { mutableStateOf(initialDateParts.third) }
    var displayMonth by remember { mutableStateOf(initialDateParts.second) }

    val cal = remember(displayYear, displayMonth) {
        Calendar.getInstance().apply {
            set(Calendar.YEAR, displayYear)
            set(Calendar.MONTH, displayMonth - 1)
            set(Calendar.DAY_OF_MONTH, 1)
        }
    }

    val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
    val offset = (firstDayOfWeek + 5) % 7

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurface,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    modifier = Modifier.clickable {
                        onDateSelected(todayStr)
                        onDismiss()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = CyanBlue.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, CyanBlue.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = stringResource(R.string.history_today),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = CyanBlue,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp)
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            if (displayMonth == 1) {
                                displayMonth = 12
                                displayYear -= 1
                            } else {
                                displayMonth -= 1
                            }
                        }
                    ) {
                        Text("<", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyanBlue)
                    }

                    Text(
                        text = String.format(Locale.US, stringResource(R.string.history_month_header), displayMonth, displayYear),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    IconButton(
                        onClick = {
                            if (displayMonth == 12) {
                                displayMonth = 1
                                displayYear += 1
                            } else {
                                displayMonth += 1
                            }
                        }
                    ) {
                        Text(">", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CyanBlue)
                    }
                }

                val weekDays = LocalContext.current.resources.getStringArray(R.array.history_week_days)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    weekDays.forEachIndexed { idx, dayName ->
                        Text(
                            text = dayName,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (idx == 6) RedError else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val totalCells = offset + daysInMonth
                val totalRows = (totalCells + 6) / 7

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in 0 until totalRows) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            for (c in 0..6) {
                                val cellIdx = r * 7 + c
                                val dayNum = cellIdx - offset + 1

                                if (cellIdx < offset || dayNum > daysInMonth) {
                                    Spacer(modifier = Modifier.weight(1f).height(36.dp))
                                } else {
                                    val dateStr = String.format(Locale.US, "%02d-%02d-%04d", dayNum, displayMonth, displayYear)
                                    val isSelected = (dateStr == selectedDateStr)
                                    val isToday = (dateStr == todayStr)
                                    val hasData = availableDates.contains(dateStr)

                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(39.dp)
                                            .clip(CircleShape)
                                            .clickable {
                                                onDateSelected(dateStr)
                                                onDismiss()
                                            },
                                        shape = CircleShape,
                                        color = when {
                                            isSelected -> CyanBlue
                                            else -> Color.Transparent
                                        },
                                        border = when {
                                            !isSelected && isToday -> BorderStroke(1.5.dp, CyanBlue)
                                            else -> null
                                        }
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize(),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Text(
                                                text = "$dayNum",
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                color = when {
                                                    isSelected -> Color.White
                                                    c == 6 -> RedError
                                                    else -> MaterialTheme.colorScheme.onSurface
                                                }
                                            )
                                            if (hasData && !isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(4.dp)
                                                        .clip(RoundedCornerShape(2.dp))
                                                        .background(GreenOk)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {}
    )
}
