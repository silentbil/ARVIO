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

    private fun List<MediaItem>.watchlistDisplayOrder(): List<MediaItem> {
        return sortedWith(
            compareBy<MediaItem> { it.sourceOrder }
                .thenByDescending { it.addedAt }
        )
    }

    init {
        observeWatchlistChanges()
        loadWatchlistInstant()
    }

    private fun observeWatchlistChanges() {
        viewModelScope.launch {
            watchlistRepository.watchlistItems.collect { items ->
                if (traktSyncInFlight) return@collect
                val current = _uiState.value
                if (items.isNotEmpty() || (!current.isLoading && current.items.isEmpty())) {
                    val orderedItems = items.watchlistDisplayOrder()
                    _uiState.value = current.copy(
                        items = orderedItems,
                        isLoading = false
                    )
                    fetchLogos(orderedItems)
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
            if (watchlistRepository.getCachedItems().isEmpty()) {
                runCatching { cloudSyncRepository.pullFromCloud() }
            }
            val traktConnected = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
            if (traktConnected) {
                val cachedItems = (watchlistRepository.getCachedItems().ifEmpty {
                    watchlistRepository.getWatchlistItems()
                }).watchlistDisplayOrder()
                _uiState.value = WatchlistUiState(
                    isLoading = cachedItems.isEmpty(),
                    items = cachedItems
                )
                if (cachedItems.isNotEmpty()) fetchLogos(cachedItems)
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
                    val items = watchlistRepository.getWatchlistItems().watchlistDisplayOrder()
                    _uiState.value = WatchlistUiState(
                        isLoading = false,
                        items = items
                    )
                } else if (!syncedFromTrakt) {
                    showLocalWatchlistOrError("Failed to load Trakt watchlist")
                }
            } catch (e: Exception) {
                if (traktConnected) {
                    showLocalWatchlistOrError(e.message ?: "Failed to load Trakt watchlist")
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
                    val items = watchlistRepository.refreshWatchlistItems().watchlistDisplayOrder()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        items = items
                    )
                } else if (!syncedFromTrakt) {
                    showLocalWatchlistOrError("Failed to load Trakt watchlist")
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

    private suspend fun showLocalWatchlistOrError(message: String) {
        val cachedItems = watchlistRepository.getWatchlistItems().watchlistDisplayOrder()
        if (cachedItems.isNotEmpty()) {
            _uiState.value = WatchlistUiState(isLoading = false, items = cachedItems)
            fetchLogos(cachedItems)
        } else {
            _uiState.value = WatchlistUiState(isLoading = false, error = message)
        }
    }

    fun removeFromWatchlist(item: MediaItem) {
        viewModelScope.launch {
            try {
                val traktConnected = runCatching { traktRepository.isAuthenticated.first() }.getOrDefault(false)
                if (traktConnected && !traktRepository.removeFromWatchlist(item.mediaType, item.id)) {
                    throw IllegalStateException("Failed to remove from Trakt watchlist")
                }

                watchlistRepository.removeFromWatchlist(item.mediaType, item.id)

                // Optimistic update - remove from local state immediately
                val updatedItems = _uiState.value.items.filter { it.id != item.id || it.mediaType != item.mediaType }
                _uiState.value = _uiState.value.copy(
                    items = updatedItems,
                    toastMessage = "Removed from watchlist",
                    toastType = ToastType.SUCCESS
                )
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
        if (traktSyncInFlight) return true
        traktSyncInFlight = true
        return try {
            val (hasTraktAuth, syncResult) = traktRepository.getWatchlistSyncResultWithAuthState()
            if (!hasTraktAuth) {
                false
            } else {
                val traktItems = syncResult?.items.orEmpty()
                val rawCount = syncResult?.rawCount ?: 0
                if (traktItems.isNotEmpty()) {
                    watchlistRepository.clearWatchlistCache()
                    val orderedTraktItems = traktItems.watchlistDisplayOrder()
                    _uiState.value = WatchlistUiState(isLoading = false, items = orderedTraktItems)
                    fetchLogos(orderedTraktItems)

                    watchlistRepository.syncFromTraktOrder(orderedTraktItems)
                    _uiState.value = WatchlistUiState(isLoading = false, items = orderedTraktItems)
                    runCatching { cloudSyncRepository.pushToCloud() }
                } else if (rawCount == 0) {
                    val cachedItems = (watchlistRepository.getCachedItems().ifEmpty {
                        watchlistRepository.getWatchlistItems()
                    }).watchlistDisplayOrder()
                    _uiState.value = WatchlistUiState(isLoading = false, items = cachedItems)
                    if (cachedItems.isNotEmpty()) {
                        fetchLogos(cachedItems)
                    } else {
                        _uiState.value = WatchlistUiState(isLoading = false, items = emptyList())
                    }
                } else {
                    val cachedItems = watchlistRepository.getWatchlistItems().watchlistDisplayOrder()
                    _uiState.value = WatchlistUiState(isLoading = false, items = cachedItems)
                    fetchLogos(cachedItems)
                }
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


