package com.arflix.tv.ui.screens.watchlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.MediaRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.WatchlistRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ToastType {
    SUCCESS, ERROR, INFO
}

data class WatchlistUiState(
    val isLoading: Boolean = true,
    val items: List<MediaItem> = emptyList(),
    val error: String? = null,
    // Toast
    val toastMessage: String? = null,
    val toastType: ToastType = ToastType.INFO
)

@HiltViewModel
class WatchlistViewModel @Inject constructor(
    private val watchlistRepository: WatchlistRepository,
    private val cloudSyncRepository: CloudSyncRepository,
    private val traktRepository: TraktRepository,
    private val mediaRepository: MediaRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(WatchlistUiState())
    val uiState: StateFlow<WatchlistUiState> = _uiState.asStateFlow()

    private val _logoUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val logoUrls: StateFlow<Map<String, String>> = _logoUrls.asStateFlow()
    private var traktSyncInFlight = false

    init {
        observeWatchlistChanges()
        loadWatchlistInstant()
    }

    private fun observeWatchlistChanges() {
        viewModelScope.launch {
            watchlistRepository.watchlistItems.collect { items ->
                if (traktSyncInFlight) return@collect
                if (items.isNotEmpty() || _uiState.value.items.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        items = items,
                        isLoading = false
                    )
                    fetchLogos(items)
                }
            }
        }
    }

    private fun fetchLogos(items: List<MediaItem>) {
        viewModelScope.launch {
            val currentLogos = _logoUrls.value.toMutableMap()
            for (item in items) {
                val key = "${item.mediaType}_${item.id}"
                if (key in currentLogos) continue
                val url = runCatching { mediaRepository.getLogoUrl(item.mediaType, item.id) }.getOrNull()
                if (url != null) {
                    currentLogos[key] = url
                    _logoUrls.value = currentLogos.toMap()
                }
            }
        }
    }

    private fun loadWatchlistInstant() {
        viewModelScope.launch {
            val traktConnected = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
            if (traktConnected) {
                // For Trakt profiles, Trakt is the source of truth. Showing the
                // local mirror before a fresh pull can resurrect old bad matches.
                _uiState.value = WatchlistUiState(isLoading = true)
            } else {
                val cachedItems = watchlistRepository.getCachedItems()
                if (cachedItems.isNotEmpty()) {
                    _uiState.value = WatchlistUiState(
                        isLoading = false,
                        items = cachedItems
                    )
                } else {
                    _uiState.value = WatchlistUiState(isLoading = true)
                }
            }

            // Trakt must win over stale local cache when the profile is connected.
            try {
                val syncedFromTrakt = syncTraktWatchlistSuspend()
                if (!syncedFromTrakt && !traktConnected) {
                    val items = watchlistRepository.getWatchlistItems()
                    _uiState.value = WatchlistUiState(
                        isLoading = false,
                        items = items
                    )
                } else if (!syncedFromTrakt) {
                    _uiState.value = WatchlistUiState(
                        isLoading = false,
                        error = "Failed to load Trakt watchlist"
                    )
                }
            } catch (e: Exception) {
                if (traktConnected) {
                    _uiState.value = WatchlistUiState(
                        isLoading = false,
                        error = e.message ?: "Failed to load Trakt watchlist"
                    )
                } else if (_uiState.value.items.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.message
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val syncedFromTrakt = syncTraktWatchlistSuspend()
                val traktConnected = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
                if (!syncedFromTrakt && !traktConnected) {
                    val items = watchlistRepository.refreshWatchlistItems()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        items = items
                    )
                } else if (!syncedFromTrakt) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Failed to load Trakt watchlist"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    toastMessage = "Failed to refresh",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    fun removeFromWatchlist(item: MediaItem) {
        viewModelScope.launch {
            try {
                // Optimistic update - remove from local state immediately
                val updatedItems = _uiState.value.items.filter { it.id != item.id || it.mediaType != item.mediaType }
                _uiState.value = _uiState.value.copy(
                    items = updatedItems,
                    toastMessage = "Removed from watchlist",
                    toastType = ToastType.SUCCESS
                )
                // Then sync to backend
                watchlistRepository.removeFromWatchlist(item.mediaType, item.id)
                // Also remove from Trakt if connected
                runCatching { traktRepository.removeFromWatchlist(item.mediaType, item.id) }
                runCatching { cloudSyncRepository.pushToCloud() }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    toastMessage = "Failed to remove from watchlist",
                    toastType = ToastType.ERROR
                )
            }
        }
    }

    /**
     * Pull Trakt watchlist and mirror it locally. Trakt is the source of truth
     * for both order and IDs when connected.
     */
    private suspend fun syncTraktWatchlistSuspend(): Boolean {
        traktSyncInFlight = true
        return try {
            val (hasTraktAuth, traktItems) = traktRepository.getWatchlistWithAuthState()
            if (!hasTraktAuth) {
                false
            } else {
                _uiState.value = WatchlistUiState(isLoading = false, items = traktItems)
                fetchLogos(traktItems)

                watchlistRepository.syncFromTraktOrder(traktItems)
                _uiState.value = WatchlistUiState(isLoading = false, items = traktItems)
                runCatching { cloudSyncRepository.pushToCloud() }
                true
            }
        } catch (_: Exception) {
            false
        } finally {
            traktSyncInFlight = false
        }
    }

    fun dismissToast() {
        _uiState.value = _uiState.value.copy(toastMessage = null)
    }
}


