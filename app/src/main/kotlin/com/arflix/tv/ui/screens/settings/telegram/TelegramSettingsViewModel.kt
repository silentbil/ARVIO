package com.arflix.tv.ui.screens.settings.telegram

import androidx.lifecycle.ViewModel
import com.arflix.tv.data.telegram.TelegramAuthState
import com.arflix.tv.data.telegram.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class TelegramSettingsViewModel @Inject constructor(
    private val repository: TelegramRepository
) : ViewModel() {

    val authState: StateFlow<TelegramAuthState> = repository.authState

    fun startAuth() = repository.startAuth()
    fun startQrAuth() = repository.requestQrCode()
    fun submitPhone(phone: String) = repository.submitPhone(phone)
    fun submitCode(code: String) = repository.submitCode(code)
    fun submitPassword(password: String) = repository.submitPassword(password)

    fun disconnect() {
        repository.disconnect()
    }
}
