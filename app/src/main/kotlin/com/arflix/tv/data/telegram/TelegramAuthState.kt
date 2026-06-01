package com.arflix.tv.data.telegram

sealed class TelegramAuthState {
    object Idle : TelegramAuthState()
    object Initializing : TelegramAuthState()
    object WaitPhone : TelegramAuthState()
    data class WaitQr(val link: String) : TelegramAuthState()
    data class WaitCode(val codeLength: Int = 5) : TelegramAuthState()
    object WaitPassword : TelegramAuthState()
    data class Ready(val firstName: String, val userId: Long) : TelegramAuthState()
    data class Error(val message: String) : TelegramAuthState()
}
