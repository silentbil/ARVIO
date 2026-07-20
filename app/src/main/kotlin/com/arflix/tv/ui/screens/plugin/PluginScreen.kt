package com.arflix.tv.ui.screens.plugin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import androidx.compose.material3.Icon
import com.arflix.tv.R
import com.arflix.tv.ui.components.SettingsRow
import com.arflix.tv.ui.components.SettingsToggleRow
import com.arflix.tv.ui.skin.resolveAccentColor
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun PluginScreen(
    viewModel: PluginViewModel = hiltViewModel(),
    onBackPressed: () -> Unit,
    onNavigateToSection: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var showResetDialog by remember { mutableStateOf(false) }
    val addButtonFocusRequester = remember { FocusRequester() }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sectionNavKey = if (isRtl) Key.DirectionRight else Key.DirectionLeft

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        try {
            addButtonFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Column(
        modifier = Modifier
            .padding(bottom = 80.dp)
            .fillMaxWidth()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        sectionNavKey -> {
                            onNavigateToSection?.invoke()
                            return@onPreviewKeyEvent onNavigateToSection != null
                        }
                        Key.Back, Key.Escape -> {
                            onBackPressed()
                            return@onPreviewKeyEvent true
                        }
                        else -> {}
                    }
                }
                false
            }
    ) {
        Text(
            text = stringResource(R.string.plugin_screen_title),
            style = ArflixTypography.sectionTitle.copy(fontSize = 24.sp),
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 20.dp)
        )

        uiState.errorMessage?.let { msg ->
            Text(msg, color = Color.Red, style = ArflixTypography.body)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Full width Add Button styled exactly like Settings Row actions
        var isAddFocused by remember { mutableStateOf(false) }
        val accentColor = resolveAccentColor(fallback = Pink)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(addButtonFocusRequester)
                .onFocusChanged { isAddFocused = it.isFocused }
                .clickable { showAddDialog = true }
                .background(
                    if (isAddFocused) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                    RoundedCornerShape(12.dp)
                )
                .border(
                    width = if (isAddFocused) 2.dp else 0.dp,
                    color = if (isAddFocused) accentColor else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.plugin_screen_add_repo),
                style = ArflixTypography.button,
                color = accentColor
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.repositories.isNotEmpty()) {
            Text(
                text = stringResource(R.string.plugin_screen_installed_repos),
                style = ArflixTypography.caption.copy(fontSize = 12.sp, letterSpacing = 1.sp),
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 10.dp)
            )

            uiState.repositories.forEach { repo ->
                FocusableSettingsRow(
                    icon = Icons.Default.Delete,
                    title = repo.name,
                    subtitle = repo.url,
                    value = stringResource(R.string.delete),
                    onClick = { viewModel.onEvent(PluginUiEvent.RemoveRepository(repo.id)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Text(
            text = stringResource(R.string.plugin_screen_installed_scrapers),
            style = ArflixTypography.caption.copy(fontSize = 12.sp, letterSpacing = 1.sp),
            color = TextSecondary,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        if (uiState.scrapers.isEmpty()) {
            Text(
                text = stringResource(R.string.plugin_screen_no_scrapers),
                style = ArflixTypography.body,
                color = TextSecondary
            )
        } else {
            uiState.scrapers.forEach { scraper ->
                FocusableSettingsToggleRow(
                    title = scraper.name,
                    subtitle = scraper.id,
                    isEnabled = scraper.enabled,
                    onToggle = { enabled -> viewModel.onEvent(PluginUiEvent.ToggleScraper(scraper.id, enabled)) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        var isResetFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isResetFocused = it.isFocused }
                .clickable { showResetDialog = true }
                .background(
                    if (isResetFocused) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                    RoundedCornerShape(12.dp)
                )
                .border(
                    width = if (isResetFocused) 2.dp else 0.dp,
                    color = if (isResetFocused) Color.Red else Color.Transparent,
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                tint = Color.Red,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Reset Plugins & Extensions",
                style = ArflixTypography.button,
                color = Color.Red
            )
        }
    }

    if (showAddDialog) {
        AddRepoDialog(
            onSave = { url ->
                viewModel.onEvent(PluginUiEvent.AddRepository(url))
                showAddDialog = false
                try { addButtonFocusRequester.requestFocus() } catch (_: Exception) {}
            },
            onDismiss = {
                showAddDialog = false
                try { addButtonFocusRequester.requestFocus() } catch (_: Exception) {}
            }
        )
    }

    if (showResetDialog) {
        WarningDialog(
            title = "Warning",
            message = "Are you sure you want to delete all plugins, scrapers, and local code data? This action cannot be undone.",
            cancelText = stringResource(R.string.cancel),
            confirmText = stringResource(R.string.delete),
            onConfirm = {
                viewModel.onEvent(PluginUiEvent.ResetAllPlugins)
                showResetDialog = false
            },
            onDismiss = { showResetDialog = false }
        )
    }

    uiState.pendingScraperEnable?.let { pending ->
        WarningDialog(
            title = stringResource(R.string.plugin_risky_enable_title),
            message = stringResource(R.string.plugin_risky_enable_message),
            cancelText = stringResource(R.string.plugin_risky_enable_cancel),
            confirmText = stringResource(R.string.plugin_risky_enable_confirm),
            onConfirm = { viewModel.onEvent(PluginUiEvent.ConfirmPendingScraperEnable) },
            onDismiss = { viewModel.onEvent(PluginUiEvent.DismissPendingScraperEnable) }
        )
    }
}

@Composable
fun FocusableSettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String = "",
    value: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    SettingsRow(
        icon = icon,
        title = title,
        subtitle = subtitle,
        value = value,
        isFocused = isFocused,
        onClick = onClick,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused }
    )
}

@Composable
fun FocusableSettingsToggleRow(
    title: String,
    subtitle: String = "",
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    SettingsToggleRow(
        title = title,
        subtitle = subtitle,
        isEnabled = isEnabled,
        isFocused = isFocused,
        onToggle = onToggle,
        modifier = modifier.onFocusChanged { isFocused = it.isFocused }
    )
}

@Composable
fun AddRepoDialog(
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf("") }
    val inputFocusRequester = remember { FocusRequester() }
    val saveFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        try { inputFocusRequester.requestFocus() } catch (_: Exception) {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.key == Key.Escape)) {
                        onDismiss()
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(520.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .clickable { /* absorb clicks */ }
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                    Text(
                        text = stringResource(R.string.plugin_screen_add_repo_dialog_title),
                        style = ArflixTypography.cardTitle.copy(fontSize = 18.sp),
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    androidx.compose.material3.OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        label = { androidx.compose.material3.Text(stringResource(R.string.plugin_screen_repo_url)) },
                        modifier = Modifier.fillMaxWidth().focusRequester(inputFocusRequester),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFE91E63),
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = Color(0xFFE91E63),
                            unfocusedLabelColor = Color.Gray
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        androidx.tv.material3.Surface(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF2B2B2B),
                                focusedContainerColor = Color(0xFF3B3B3B)
                            ),
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = stringResource(R.string.cancel),
                                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                color = Color.White,
                                style = ArflixTypography.button
                            )
                        }

                        androidx.tv.material3.Surface(
                            onClick = { onSave(value) },
                            modifier = Modifier.weight(1f).focusRequester(saveFocusRequester),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFFE91E63),
                                focusedContainerColor = Color(0xFFFF4081)
                            ),
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = stringResource(R.string.add),
                                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                color = Color.White,
                                style = ArflixTypography.button
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun WarningDialog(
    title: String,
    message: String,
    cancelText: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        try { cancelFocusRequester.requestFocus() } catch (_: Exception) {}
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = true, usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown &&
                        (event.key == Key.Back || event.key == Key.Escape)) {
                        onDismiss()
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .width(420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xFF1E1E1E))
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = title,
                        style = ArflixTypography.cardTitle.copy(fontSize = 18.sp),
                        color = Color.Red,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = message,
                        style = ArflixTypography.body,
                        color = TextSecondary,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.tv.material3.Surface(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).focusRequester(cancelFocusRequester),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFF2B2B2B),
                                focusedContainerColor = Color(0xFF3B3B3B)
                            ),
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = cancelText,
                                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                color = Color.White,
                                style = ArflixTypography.button
                            )
                        }

                        androidx.tv.material3.Surface(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f),
                            colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFFE91E63),
                                focusedContainerColor = Color(0xFFFF4081)
                            ),
                            shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp))
                        ) {
                            Text(
                                text = confirmText,
                                modifier = Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                color = Color.White,
                                style = ArflixTypography.button
                            )
                        }
                    }
                }
            }
        }
    }
}

