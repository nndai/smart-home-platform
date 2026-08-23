package com.nndai.myhome.presentation.profile

import android.app.Activity
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nndai.myhome.R
import com.nndai.myhome.core.theme.CyanBlue
import com.nndai.myhome.core.theme.RedError
import com.nndai.myhome.core.theme.SecondaryText
import com.nndai.myhome.core.utils.LocaleHelper
import io.github.jan.supabase.auth.auth
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.nndai.myhome.data.repository.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun ProfileScreen(
    authRepository: AuthRepository,
    isLoggedIn: Boolean,
    onNavigateToLogin: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showLanguageDialog by remember { mutableStateOf(false) }

    val currentLangCode = remember { LocaleHelper.getLanguageCode(context) }
    val currentLangName = if (currentLangCode == "vi") stringResource(R.string.lang_vi) else stringResource(R.string.lang_en)

    // ── Account identity from Supabase Auth ──
    val account = remember(isLoggedIn) {
        if (!isLoggedIn) null
        else try {
            com.nndai.myhome.data.remote.SupabaseConfig.client.auth.currentSessionOrNull()?.user
        } catch (_: Exception) { null }
    }
    val userEmail = account?.email
    val userName = remember(account) {
        val meta = account?.userMetadata ?: return@remember null
        listOf("full_name", "name", "given_name")
            .firstNotNullOfOrNull { key ->
                (meta[key] as? JsonPrimitive)?.contentOrNull
            }?.takeIf { it.isNotBlank() }
    }
    val avatarLetter = (userName?.firstOrNull() ?: userEmail?.firstOrNull())
        ?.uppercaseChar()?.toString()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header
        Text(
            text = stringResource(R.string.profile_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        // Avatar + Auth section
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Avatar
                Surface(
                    shape = CircleShape,
                    color = if (isLoggedIn) CyanBlue.copy(alpha = 0.12f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(80.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        if (isLoggedIn && avatarLetter != null) {
                            Text(
                                text = avatarLetter,
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = CyanBlue
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Person,
                                contentDescription = null,
                                tint = if (isLoggedIn) CyanBlue else SecondaryText,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }

                if (isLoggedIn) {
                    // Tên hiển thị (Google metadata) hoặc email
                    Text(
                        text = userName ?: userEmail ?: stringResource(R.string.profile_signed_in),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    // Email luôn hiện khi có
                    Text(
                        text = userEmail ?: stringResource(R.string.profile_devices_synced),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )

                    // Sign out button
                    Button(
                        onClick = { scope.launch { authRepository.logout() } },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RedError.copy(alpha = 0.12f),
                            contentColor = RedError
                        ),
                        border = BorderStroke(1.dp, RedError.copy(alpha = 0.3f)),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.profile_sign_out), fontWeight = FontWeight.Medium)
                    }
                } else {
                    Text(
                        text = stringResource(R.string.profile_guest),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.profile_sign_in_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Sign in button
                    Button(
                        onClick = onNavigateToLogin,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ),
                        shape = MaterialTheme.shapes.small
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Login,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(stringResource(R.string.profile_sign_in), fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // App Settings section
        Text(
            text = stringResource(R.string.profile_app_settings),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
        ) {
            Column {
                SettingsRow(
                    icon = Icons.Filled.Language,
                    label = stringResource(R.string.profile_language),
                    value = currentLangName,
                    onClick = { showLanguageDialog = true }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                SettingsRow(
                    icon = Icons.Filled.DarkMode,
                    label = stringResource(R.string.profile_appearance),
                    value = stringResource(R.string.profile_theme_system),
                    onClick = {}
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                SettingsRow(
                    icon = Icons.Filled.Shield,
                    label = stringResource(R.string.profile_privacy),
                    value = null,
                    onClick = {}
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                SettingsRow(
                    icon = Icons.Filled.Info,
                    label = stringResource(R.string.profile_about),
                    value = stringResource(R.string.profile_version, "1.0.0"),
                    onClick = {}
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Language Selector Dialog
    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.profile_select_language),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "vi" to stringResource(R.string.lang_vi),
                        "en" to stringResource(R.string.lang_en)
                    ).forEach { (code, label) ->
                        val isSelected = currentLangCode == code
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .clickable {
                                    showLanguageDialog = false
                                    if (code != currentLangCode) {
                                        LocaleHelper.setLanguageCode(context, code)
                                        (context as? Activity)?.recreate()
                                    }
                                },
                            shape = MaterialTheme.shapes.small,
                            color = if (isSelected) CyanBlue.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant,
                            border = if (isSelected) BorderStroke(1.5.dp, CyanBlue) else null
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) CyanBlue else MaterialTheme.colorScheme.onSurface
                                )
                                if (isSelected) {
                                    Text(
                                        text = "✓",
                                        fontWeight = FontWeight.Bold,
                                        color = CyanBlue
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                Button(
                    onClick = { showLanguageDialog = false },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        if (value != null) {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
