package com.arflix.tv.ui.screens.settings.telegram

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.arflix.tv.data.telegram.TelegramAuthState
import com.arflix.tv.ui.components.LoadingIndicator
import com.arflix.tv.ui.theme.ArflixTypography
import com.arflix.tv.ui.theme.BackgroundElevated
import com.arflix.tv.ui.theme.Pink
import com.arflix.tv.ui.theme.SuccessGreen
import com.arflix.tv.ui.theme.TextPrimary
import com.arflix.tv.ui.theme.TextSecondary
import com.arflix.tv.ui.theme.appBackgroundDark
import com.arflix.tv.util.LocalDeviceType
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TelegramSettingsScreen(
    onBack: () -> Unit,
    viewModel: TelegramSettingsViewModel = hiltViewModel()
) {
    val authState by viewModel.authState.collectAsState()
    val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsState()
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 24.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "Telegram",
                    style = ArflixTypography.sectionTitle,
                    color = TextPrimary
                )
            }

            val isMobile = LocalDeviceType.current.isTouchDevice()

            when (val state = authState) {
                is TelegramAuthState.Idle -> IdleContent(onConnect = { viewModel.startAuth() })
                is TelegramAuthState.Initializing -> LoadingContent("Connecting...")
                is TelegramAuthState.WaitPhone -> {
                    if (isMobile) {
                        PhoneContent(onSubmit = { viewModel.submitPhone(it) })
                    } else {
                        LaunchedEffect(Unit) { viewModel.startQrAuth() }
                        LoadingContent("Preparing QR code...")
                    }
                }
                is TelegramAuthState.WaitQr -> QrContent(link = state.link)
                is TelegramAuthState.WaitCode -> CodeContent(
                    codeLength = state.codeLength,
                    onSubmit = { viewModel.submitCode(it) }
                )
                is TelegramAuthState.WaitPassword -> PasswordContent(
                    onSubmit = { viewModel.submitPassword(it) }
                )
                is TelegramAuthState.Ready -> ConnectedContent(
                    firstName = state.firstName,
                    cacheSizeBytes = cacheSizeBytes,
                    onDisconnect = { showDisconnectConfirm = true },
                    onClearCache = { viewModel.clearCache() }
                )
                is TelegramAuthState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.startAuth() }
                )
            }
        }
        } // inner padded Box

        if (showDisconnectConfirm) {
            DisconnectConfirmDialog(
                onConfirm = {
                    showDisconnectConfirm = false
                    viewModel.disconnect()
                },
                onDismiss = { showDisconnectConfirm = false }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun IdleContent(onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = "Connect Telegram",
            style = ArflixTypography.cardTitle.copy(fontSize = 22.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Search your channels and groups for video files whenever you open a movie or show.",
            style = ArflixTypography.caption.copy(fontSize = 14.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Arvio is not responsible for the content streamed through this feature. " +
                    "Only connect your own account and use it fairly — respect copyright and applicable laws.",
                style = ArflixTypography.caption.copy(fontSize = 11.sp),
                color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        ActionButton(label = "CONNECT", onClick = onConnect)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LoadingContent(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(56.dp))
        LoadingIndicator(color = Pink, size = 40.dp, strokeWidth = 3.dp)
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = message, style = ArflixTypography.caption.copy(fontSize = 14.sp), color = TextSecondary)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun QrContent(link: String) {
    val qrBitmap = remember(link) { generateQrBitmap(link, 400) }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Scan with Telegram",
            style = ArflixTypography.cardTitle.copy(fontSize = 20.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Open Telegram on your phone → Settings → Devices → Link Desktop Device",
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.6f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        if (qrBitmap != null) {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .background(Color.White, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Telegram QR code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            LoadingIndicator(color = Pink, size = 40.dp, strokeWidth = 3.dp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "QR code expires in 30 seconds — refreshes automatically",
            style = ArflixTypography.caption.copy(fontSize = 11.sp),
            color = TextSecondary.copy(alpha = 0.6f)
        )
    }
}

private fun generateQrBitmap(content: String, size: Int): Bitmap? = try {
    val bitMatrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp
} catch (e: Exception) {
    null
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PhoneContent(onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("+") }
    var isSubmitting by remember { mutableStateOf(false) }
    var showValidation by remember { mutableStateOf(false) }

    val digits = phone.filter { it.isDigit() }
    val isValid = phone.startsWith("+") && digits.length >= 7
    val showError = showValidation && !isValid

    fun trySubmit() {
        showValidation = true
        if (isValid && !isSubmitting) {
            isSubmitting = true
            onSubmit(phone)
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = "Enter Phone Number",
            style = ArflixTypography.cardTitle.copy(fontSize = 22.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Use the same number registered with your Telegram account.",
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone number") },
            placeholder = { Text("+1 650 555 1234", color = TextSecondary.copy(alpha = 0.35f)) },
            supportingText = {
                if (showError) {
                    Text("Must start with + and include country code, e.g. +1 for US, +44 for UK", color = Pink)
                } else {
                    Text("International format: +[country code][number]", color = TextSecondary.copy(alpha = 0.5f))
                }
            },
            isError = showError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { trySubmit() }),
            singleLine = true,
            colors = inputColors(),
            modifier = Modifier.fillMaxWidth(0.75f)
        )
        Spacer(modifier = Modifier.height(20.dp))
        if (isSubmitting) {
            LoadingIndicator(color = Pink, size = 32.dp, strokeWidth = 2.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Sending code...",
                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                color = TextSecondary
            )
        } else {
            ActionButton(label = "SEND CODE", onClick = ::trySubmit)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CodeContent(codeLength: Int, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Row(
            modifier = Modifier
                .background(SuccessGreen.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                .border(1.dp, SuccessGreen.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(text = "✓  ", style = ArflixTypography.caption.copy(fontSize = 14.sp), color = SuccessGreen)
            Text(
                text = "Code sent to your Telegram app",
                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                color = SuccessGreen
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Enter Code",
            style = ArflixTypography.cardTitle.copy(fontSize = 22.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Check your Telegram app for a $codeLength-digit code.",
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextField(
            value = code,
            onValueChange = { if (it.length <= codeLength) code = it },
            label = { Text("Verification code") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { if (code.length == codeLength) onSubmit(code) }),
            singleLine = true,
            colors = inputColors(),
            modifier = Modifier.fillMaxWidth(0.55f)
        )
        Spacer(modifier = Modifier.height(20.dp))
        ActionButton(label = "CONFIRM") { if (code.isNotBlank()) onSubmit(code) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PasswordContent(onSubmit: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = "Two-Step Verification",
            style = ArflixTypography.cardTitle.copy(fontSize = 20.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Your account has an additional password.",
            style = ArflixTypography.caption.copy(fontSize = 13.sp),
            color = TextSecondary
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit(password) }),
            singleLine = true,
            colors = inputColors(),
            modifier = Modifier.fillMaxWidth(0.55f)
        )
        Spacer(modifier = Modifier.height(20.dp))
        ActionButton(label = "CONFIRM", onClick = { onSubmit(password) })
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ConnectedContent(
    firstName: String,
    cacheSizeBytes: Long,
    onDisconnect: () -> Unit,
    onClearCache: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SuccessGreen.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .border(1.dp, SuccessGreen.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Connected",
                    style = ArflixTypography.cardTitle.copy(fontSize = 15.sp),
                    color = SuccessGreen
                )
                Text(
                    text = "Signed in as $firstName",
                    style = ArflixTypography.caption.copy(fontSize = 13.sp),
                    color = TextSecondary
                )
            }
            Box(
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                    .clickable { onDisconnect() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "DISCONNECT",
                    style = ArflixTypography.label.copy(fontSize = 11.sp),
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        var cacheFocused by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (cacheSizeBytes > 0L) onClearCache() }
                .onFocusChanged { cacheFocused = it.isFocused }
                .background(
                    if (cacheFocused) Pink.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.04f),
                    RoundedCornerShape(12.dp)
                )
                .border(
                    width = if (cacheFocused) 2.dp else 1.dp,
                    color = if (cacheFocused) Pink else Color.White.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Video Cache",
                    style = ArflixTypography.cardTitle.copy(fontSize = 14.sp),
                    color = if (cacheFocused) Pink else TextPrimary
                )
                Text(
                    text = formatCacheSize(cacheSizeBytes),
                    style = ArflixTypography.caption.copy(fontSize = 13.sp),
                    color = TextSecondary
                )
            }
            if (cacheSizeBytes > 0L) {
                Text(
                    text = "CLEAR",
                    style = ArflixTypography.label.copy(fontSize = 11.sp),
                    color = Pink
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))
        Icon(imageVector = Icons.Default.Close, contentDescription = null, tint = Pink, modifier = Modifier.size(40.dp))
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Connection Failed", style = ArflixTypography.cardTitle.copy(fontSize = 18.sp), color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, style = ArflixTypography.caption.copy(fontSize = 13.sp), color = TextSecondary)
        Spacer(modifier = Modifier.height(24.dp))
        ActionButton(label = "TRY AGAIN", onClick = onRetry)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActionButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Pink.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
            .border(1.dp, Pink.copy(alpha = 0.4f), RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = ArflixTypography.label.copy(fontSize = 13.sp, letterSpacing = 0.8.sp),
            color = Pink
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DisconnectConfirmDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focusedButton by remember { mutableIntStateOf(0) } // 0 = cancel, 1 = disconnect
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .background(BackgroundElevated, RoundedCornerShape(16.dp))
                .clickable(enabled = false) {}
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.Back, Key.Escape -> { onDismiss(); true }
                        Key.DirectionLeft  -> { focusedButton = if (isRtl) 1 else 0; true }
                        Key.DirectionRight -> { focusedButton = if (isRtl) 0 else 1; true }
                        Key.Enter, Key.DirectionCenter -> {
                            if (focusedButton == 0) onDismiss() else onConfirm()
                            true
                        }
                        else -> false
                    }
                }
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Disconnect Telegram?",
                style = ArflixTypography.cardTitle.copy(fontSize = 18.sp),
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You'll need to sign in again to use Telegram as a source.",
                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (focusedButton == 0) Color.White.copy(alpha = 0.15f) else Color.White.copy(alpha = 0.06f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (focusedButton == 0) 2.dp else 0.dp,
                            color = if (focusedButton == 0) Color.White.copy(alpha = 0.5f) else Color.Transparent,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onDismiss() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "CANCEL",
                        style = ArflixTypography.label.copy(fontSize = 12.sp),
                        color = TextPrimary
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (focusedButton == 1) Pink.copy(alpha = 0.3f) else Pink.copy(alpha = 0.12f),
                            RoundedCornerShape(8.dp)
                        )
                        .border(
                            width = if (focusedButton == 1) 2.dp else 1.dp,
                            color = if (focusedButton == 1) Pink else Pink.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { onConfirm() }
                        .padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "DISCONNECT",
                        style = ArflixTypography.label.copy(fontSize = 12.sp),
                        color = Pink
                    )
                }
            }
        }
    }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes <= 0 -> "Empty"
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}

@Composable
private fun inputColors() = TextFieldDefaults.colors(
    focusedContainerColor = BackgroundElevated,
    unfocusedContainerColor = BackgroundElevated,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = Pink,
    unfocusedLabelColor = TextSecondary
)
