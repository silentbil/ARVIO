package com.arflix.tv.ui.screens.login

import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.repository.AuthRepository
import com.arflix.tv.data.repository.AuthState
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.StreamRepository
import com.arflix.tv.util.AuthEmailValidator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LoginUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val authState: AuthState = AuthState.Loading,
    val googleSignInRequest: GetCredentialRequest? = null,
    val loginReady: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val streamRepository: StreamRepository,
    private val cloudSyncRepository: CloudSyncRepository
) : ViewModel() {
    private var lastSignUpAttemptMs: Long = 0L
    
    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()
    
    init {
        // Observe auth state
        viewModelScope.launch {
            authRepository.authState.collect { authState ->
                _uiState.update { it.copy(authState = authState) }
            }
        }
    }
    
    fun signIn(email: String, password: String) {
        val normalizedEmail = AuthEmailValidator.normalize(email)
        AuthEmailValidator.validate(normalizedEmail, rejectDisposable = false)?.let { message ->
            _uiState.update { it.copy(error = message) }
            return
        }
        if (password.isBlank()) {
            _uiState.update { it.copy(error = "Please enter your password") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = authRepository.signIn(normalizedEmail, password)

            // Full cloud restore after successful login — not just addons.
            // The previous flow only called syncAddonsFromCloud(), so catalogs,
            // IPTV favorites, watchlist, and other cloud-backed state never came
            // down on a fresh login. This is why TV-side changes weren't visible
            // on the phone even after logout/login.
            if (result.isSuccess) {
                runCatching { cloudSyncRepository.pullFromCloud() }
                runCatching { streamRepository.syncAddonsFromCloud() }
            }

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message,
                    loginReady = result.isSuccess
                )
            }
        }
    }
    
    fun signUp(email: String, password: String) {
        val normalizedEmail = AuthEmailValidator.normalize(email)
        AuthEmailValidator.validate(normalizedEmail)?.let { message ->
            _uiState.update { it.copy(error = message) }
            return
        }
        if (password.isBlank()) {
            _uiState.update { it.copy(error = "Please enter your password") }
            return
        }

        if (password.length < 6) {
            _uiState.update { it.copy(error = "Password must be at least 6 characters") }
            return
        }
        val now = System.currentTimeMillis()
        val remainingCooldownMs = 60_000L - (now - lastSignUpAttemptMs)
        if (remainingCooldownMs > 0L) {
            val seconds = ((remainingCooldownMs + 999L) / 1000L).coerceAtLeast(1L)
            _uiState.update { it.copy(error = "Please wait ${seconds}s before creating another account") }
            return
        }
        lastSignUpAttemptMs = now

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val result = authRepository.signUp(normalizedEmail, password)

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    /**
     * Initiate Google Sign-In - returns the request for the Activity to handle
     */
    fun getGoogleSignInRequest(): GetCredentialRequest {
        return authRepository.getGoogleSignInRequest()
    }

    /**
     * Handle Google Sign-In result from the Activity
     */
    fun handleGoogleSignInResult(result: GetCredentialResponse) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val authResult = authRepository.handleGoogleSignInResult(result)

            if (authResult.isSuccess) {
                runCatching { cloudSyncRepository.pullFromCloud() }
                runCatching { streamRepository.syncAddonsFromCloud() }
            }

            _uiState.update { state ->
                state.copy(
                    isLoading = false,
                    error = authResult.exceptionOrNull()?.message,
                    loginReady = authResult.isSuccess
                )
            }
        }
    }

    fun onLoginNavigationHandled() {
        _uiState.update { it.copy(loginReady = false) }
    }

    /**
     * Handle Google Sign-In error
     */
    fun handleGoogleSignInError(error: String) {
        _uiState.update { it.copy(isLoading = false, error = error) }
    }
}


