package com.arflix.tv.ui.screens.settings.telegram

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    val chats by viewModel.chats.collectAsState()
    val isLoadingChats by viewModel.isLoadingChats.collectAsState()
    val excludedChatIds by viewModel.excludedChatIds.collectAsState(initial = emptySet())

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(appBackgroundDark())
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
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
                    chats = chats,
                    isLoadingChats = isLoadingChats,
                    excludedChatIds = excludedChatIds,
                    onToggleChat = { chatId, exclude -> viewModel.toggleChatExclusion(chatId, exclude) },
                    onDisconnect = { viewModel.disconnect() }
                )
                is TelegramAuthState.Error -> ErrorContent(
                    message = state.message,
                    onRetry = { viewModel.startAuth() }
                )
            }
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
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { if (phone.length > 4) onSubmit(phone) }),
            singleLine = true,
            colors = inputColors(),
            modifier = Modifier.fillMaxWidth(0.75f)
        )
        Spacer(modifier = Modifier.height(20.dp))
        ActionButton(label = "SEND CODE") { if (phone.length > 4) onSubmit(phone) }
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
        Text(
            text = "Enter Code",
            style = ArflixTypography.cardTitle.copy(fontSize = 22.sp),
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Telegram sent a $codeLength-digit code to your Telegram app.",
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
    chats: List<com.arflix.tv.data.telegram.TelegramChat>,
    isLoadingChats: Boolean,
    excludedChatIds: Set<Long>,
    onToggleChat: (Long, Boolean) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
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

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "Channels & Groups",
            style = ArflixTypography.sectionTitle.copy(fontSize = 15.sp),
            color = TextPrimary
        )
        Text(
            text = "All are searched by default — toggle to exclude",
            style = ArflixTypography.caption.copy(fontSize = 12.sp),
            color = TextSecondary,
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp)
        )

        if (isLoadingChats) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoadingIndicator(color = Pink, size = 20.dp, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Loading channels...",
                    style = ArflixTypography.caption.copy(fontSize = 13.sp),
                    color = TextSecondary
                )
            }
        } else if (chats.isEmpty()) {
            Text(
                text = "No channels or groups found",
                style = ArflixTypography.caption.copy(fontSize = 13.sp),
                color = TextSecondary
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(chats, key = { it.id }) { chat ->
                    val isIncluded = chat.id !in excludedChatIds
                    var isFocused by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                            .background(
                                if (isFocused) Pink.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.05f),
                                RoundedCornerShape(10.dp)
                            )
                            .border(
                                width = if (isFocused) 1.5.dp else 0.dp,
                                color = if (isFocused) Pink.copy(alpha = 0.7f) else Color.Transparent,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = chat.title,
                                style = ArflixTypography.cardTitle.copy(fontSize = 14.sp),
                                color = if (isIncluded) TextPrimary else TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = buildString {
                                append(if (chat.isChannel) "Channel" else "Group")
                                if (chat.memberCount > 0) append(" · ${chat.memberCount} members")
                            }
                            Text(
                                text = subtitle,
                                style = ArflixTypography.caption.copy(fontSize = 12.sp),
                                color = TextSecondary
                            )
                        }
                        Switch(
                            checked = isIncluded,
                            onCheckedChange = { onToggleChat(chat.id, !it) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Pink,
                                uncheckedThumbColor = TextSecondary,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                    }
                }
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

@Composable
private fun inputColors() = TextFieldDefaults.colors(
    focusedContainerColor = BackgroundElevated,
    unfocusedContainerColor = BackgroundElevated,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = Pink,
    unfocusedLabelColor = TextSecondary
)
