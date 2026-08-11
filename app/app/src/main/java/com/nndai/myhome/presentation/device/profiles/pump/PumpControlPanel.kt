package com.nndai.myhome.presentation.device.profiles.pump

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DeviceThermostat
import androidx.compose.material.icons.filled.ElectricMeter
import androidx.compose.material.icons.filled.ElectricalServices
import androidx.compose.material.icons.filled.EnergySavingsLeaf
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nndai.myhome.R
import com.nndai.myhome.data.model.PumpState
import com.nndai.myhome.data.model.PumpStatus
import com.nndai.myhome.presentation.device.components.ConnectionBanner
import com.nndai.myhome.presentation.device.components.PumpControlButton
import com.nndai.myhome.presentation.device.components.StatusCard
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.GreenOk
import com.nndai.myhome.core.theme.OrangeWarning
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.core.theme.SecondaryText
import com.nndai.myhome.core.utils.formatApparentPower
import com.nndai.myhome.core.utils.formatBytes
import com.nndai.myhome.core.utils.formatCurrent
import com.nndai.myhome.core.utils.formatEnergy
import com.nndai.myhome.core.utils.formatPf
import com.nndai.myhome.core.utils.formatPower
import com.nndai.myhome.core.utils.formatRssi
import com.nndai.myhome.core.utils.formatTemperature
import com.nndai.myhome.core.utils.formatUptime
import com.nndai.myhome.core.utils.formatVoltage

private val PurpleDryRun = Color(0xFF9C27B0)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    snackbarHostState: SnackbarHostState,
    viewModel: DashboardViewModel = viewModel()
) {
    val status by viewModel.pumpStatus.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val isToggling by viewModel.isToggling.collectAsStateWithLifecycle()

    // Snackbar messages
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    DashboardScreenContent(
        status = status,
        connectionState = connectionState,
        isToggling = isToggling,
        onToggle = { viewModel.togglePump() },
        onReconnect = { viewModel.reconnect() },
        onClearFault = { viewModel.clearPumpFault() }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreenContent(
    status: PumpStatus?,
    connectionState: com.nndai.myhome.data.model.ConnectionState,
    isToggling: Boolean,
    onToggle: () -> Unit,
    onReconnect: () -> Unit,
    onClearFault: () -> Unit
) {
    var dismissedFaultState by remember { mutableStateOf<PumpState?>(null) }
    var activeFaultDialogState by remember { mutableStateOf<PumpState?>(null) }

    // Refresh status when screen becomes visible/resumed
//    LaunchedEffect(Unit) {
//        viewModel.refreshStatus()
//    }

    // Show fault modal dialog BOTH on entering screen AND on real-time state changes
    val currentPumpState = status?.pumpState ?: PumpState.OFF
    LaunchedEffect(currentPumpState) {
        if (currentPumpState.isLatchedFault) {
            if (dismissedFaultState != currentPumpState) {
                activeFaultDialogState = currentPumpState
            }
        } else {
            dismissedFaultState = null
            activeFaultDialogState = null
        }
    }

    // Modal Dialog cho 3 trạng thái latch (DRY_RUN, CRITICAL_CURRENT, OVERLOAD)
    activeFaultDialogState?.let { faultState ->
        val (faultTitleRes, faultDescRes) = when (faultState) {
            PumpState.DRY_RUN -> Pair(R.string.fault_dry_run_title, R.string.fault_dry_run_desc)
            PumpState.CRITICAL_CURRENT -> Pair(R.string.fault_critical_current_title, R.string.fault_critical_current_desc)
            PumpState.OVERLOAD -> Pair(R.string.fault_overload_title, R.string.fault_overload_desc)
            else -> Pair(R.string.fault_generic_title, R.string.fault_generic_desc)
        }

        AlertDialog(
            onDismissRequest = {
                dismissedFaultState = currentPumpState
                activeFaultDialogState = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = RedError,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(faultTitleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = RedError
                )
            },
            text = {
                Text(
                    text = stringResource(faultDescRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            dismissedFaultState = currentPumpState
                            activeFaultDialogState = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(stringResource(R.string.close))
                    }

                    Button(
                        onClick = {
                            dismissedFaultState = currentPumpState
                            activeFaultDialogState = null
                            onClearFault()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RedError),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(stringResource(R.string.clear_fault), fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            },
            dismissButton = null
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Connection banner
        ConnectionBanner(
            state = connectionState,
            onReconnect = onReconnect
        )

        // 2. Metrics & Status grid (Aligned in 2-column FlowRow)
        AnimatedVisibility(
            visible = status != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val s = status ?: return@AnimatedVisibility
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxItemsInEachRow = 2
                ) {
                    // Item 1: Relay Status Card
                    val isRelayOn = s.relay
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium),
                        shape = MaterialTheme.shapes.medium,
                        color = if (isRelayOn) GreenOk.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            width = 1.5.dp,
                            color = if (isRelayOn) GreenOk else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = if (isRelayOn) Icons.Filled.Power else Icons.Filled.PowerOff,
                                contentDescription = null,
                                tint = if (isRelayOn) GreenOk else SecondaryText,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = if (isRelayOn) stringResource(R.string.relay_on) else stringResource(R.string.relay_off),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    ),
                                    color = if (isRelayOn) GreenOk else SecondaryText
                                )
                                Text(
                                    text = stringResource(R.string.relay_status),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Item 2: Pump State / Switch State Card
                    val pState = s.pumpState
                    val pStateColor = when (pState) {
                        PumpState.OFF -> SecondaryText
                        PumpState.RUNNING_OK -> GreenOk
                        PumpState.HIGH_CURRENT -> OrangeWarning
                        PumpState.DRY_RUN -> PurpleDryRun
                        PumpState.CRITICAL_CURRENT, PumpState.OVERLOAD -> RedError
                    }
                    val stateTitleRes = if (s.pumpMode) R.string.pump_state_title else R.string.switch_state_title
                    val stateLabelRes = when (pState) {
                        PumpState.OFF -> R.string.pump_state_off
                        PumpState.RUNNING_OK -> R.string.pump_state_running_ok
                        PumpState.HIGH_CURRENT -> R.string.pump_state_high_current
                        PumpState.DRY_RUN -> R.string.pump_state_dry_run
                        PumpState.CRITICAL_CURRENT -> R.string.pump_state_critical_current
                        PumpState.OVERLOAD -> R.string.pump_state_overload
                    }

                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.medium),
                        shape = MaterialTheme.shapes.medium,
                        color = pStateColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.5.dp, pStateColor.copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.WaterDrop,
                                contentDescription = null,
                                tint = pStateColor,
                                modifier = Modifier.size(22.dp)
                            )
                            Column {
                                Text(
                                    text = stringResource(stateLabelRes),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 17.sp
                                    ),
                                    color = pStateColor,
                                    maxLines = 1
                                )
                                Text(
                                    text = stringResource(stateTitleRes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Other Metrics Cards
                    StatusCard(
                        icon = Icons.Filled.Bolt,
                        value = s.current.formatCurrent(),
                        label = stringResource(R.string.metric_current),
                        iconTint = if (s.pumpState == PumpState.OVERLOAD || s.pumpState == PumpState.CRITICAL_CURRENT) RedError else CyanBlue,
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        icon = Icons.Filled.ElectricMeter,
                        value = s.power.formatPower(),
                        label = stringResource(R.string.metric_power),
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        icon = Icons.Filled.Bolt,
                        value = s.voltage.formatVoltage(),
                        label = stringResource(R.string.metric_voltage),
                        iconTint = OrangeWarning,
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        icon = Icons.Filled.FlashOn,
                        value = s.apparent.formatApparentPower(),
                        label = stringResource(R.string.metric_apparent_power),
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        icon = Icons.Filled.Speed,
                        value = s.pf.formatPf(),
                        label = stringResource(R.string.metric_power_factor),
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        icon = Icons.Filled.EnergySavingsLeaf,
                        value = s.dailyEnergy.formatEnergy(),
                        label = stringResource(R.string.metric_energy),
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        icon = Icons.Filled.DeviceThermostat,
                        value = s.temperature.formatTemperature(),
                        label = stringResource(R.string.metric_temperature),
                        iconTint = if (s.temperature > 60) RedError else OrangeWarning,
                        modifier = Modifier.weight(1f)
                    )
                    StatusCard(
                        icon = Icons.Filled.SignalCellularAlt,
                        value = s.rssi.formatRssi(),
                        label = stringResource(R.string.metric_rssi),
                        iconTint = when {
                            s.rssi > -50 -> CyanBlue
                            s.rssi > -70 -> OrangeWarning
                            else -> RedError
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Footer info
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.height(14.dp)
                        )
                        Text(
                            text = s.uptime.formatUptime(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.height(14.dp)
                        )
                        Text(
                            text = s.heap.formatBytes(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 3. Pump Control Section at Bottom
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (status?.pumpMode == false) stringResource(R.string.switch_control) else stringResource(R.string.pump_control),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                PumpControlButton(
                    isOn = status?.relay == true,
                    enabled = !isToggling,
                    isLoading = isToggling,
                    onClick = onToggle
                )
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Light Mode")
@androidx.compose.ui.tooling.preview.Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES, name = "Dark Mode")
@Composable
fun DashboardScreenPreview() {
    com.nndai.myhome.core.theme.RemotePumpTheme {
        val fakeStatus = com.nndai.myhome.data.model.PumpStatus(
            pumpState = PumpState.RUNNING_OK,
            pumpMode = true,
            relay = true,
            voltage = 220.5f,
            current = 5.2f,
            power = 1100f,
            apparent = 1146f,
            pf = 0.96f,
            dailyEnergy = 2.5f,
            temperature = 45f,
            uptime = 3600,
            heap = 40960,
            rssi = -60
        )
        DashboardScreenContent(
            status = fakeStatus,
            connectionState = com.nndai.myhome.data.model.ConnectionState.Connected("Mock"),
            isToggling = false,
            onToggle = {},
            onReconnect = {},
            onClearFault = {}
        )
    }
}
