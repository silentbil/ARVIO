package com.arflix.tv.ui.screens.settings.telegram

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.telegram.TelegramAuthState
import com.arflix.tv.data.telegram.TelegramChat
import com.arflix.tv.data.telegram.TelegramRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TelegramSettingsViewModel @Inject constructor(
    private val repository: TelegramRepository
) : ViewModel() {

    val authState: StateFlow<TelegramAuthState> = repository.authState
    val excludedChatIds: Flow<Set<Long>> = repository.getExcludedChatIds()

    private val _chats = MutableStateFlow<List<TelegramChat>>(emptyList())
    val chats: StateFlow<List<TelegramChat>> = _chats.asStateFlow()

    private val _isLoadingChats = MutableStateFlow(false)
    val isLoadingChats: StateFlow<Boolean> = _isLoadingChats.asStateFlow()

    init {
        viewModelScope.launch {
            repository.authState.collect { state ->
                if (state is TelegramAuthState.Ready) {
                    loadChats()
                } else if (state is TelegramAuthState.Idle) {
                    _chats.value = emptyList()
                }
            }
        }
    }

    fun startAuth() {
        repository.startAuth()
    }

    fun startQrAuth() {
        repository.requestQrCode()
    }

    fun submitPhone(phone: String) {
        repository.submitPhone(phone)
    }

    fun submitCode(code: String) {
        repository.submitCode(code)
    }

    fun submitPassword(password: String) {
        repository.submitPassword(password)
    }

    fun disconnect() {
        repository.disconnect()
        _chats.value = emptyList()
    }

    fun toggleChatExclusion(chatId: Long, exclude: Boolean) {
        viewModelScope.launch {
            repository.toggleChatExclusion(chatId, exclude)
        }
    }

    private fun loadChats() {
        viewModelScope.launch {
            _isLoadingChats.value = true
            try {
                _chats.value = repository.getChats()
            } finally {
                _isLoadingChats.value = false
            }
        }
    }
}
