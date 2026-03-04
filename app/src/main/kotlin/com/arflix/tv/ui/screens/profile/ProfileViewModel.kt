package com.arflix.tv.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arflix.tv.data.model.Profile
import com.arflix.tv.data.model.ProfileColors
import com.arflix.tv.data.repository.CloudSyncRepository
import com.arflix.tv.data.repository.ProfileManager
import com.arflix.tv.data.repository.ProfileRepository
import com.arflix.tv.data.repository.TraktRepository
import com.arflix.tv.data.repository.WatchlistRepository
import com.arflix.tv.data.repository.IptvRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val profiles: List<Profile> = emptyList(),
    val activeProfile: Profile? = null,
    val isLoading: Boolean = true,
    val isManageMode: Boolean = false,
    // Add profile dialog state
    val showAddDialog: Boolean = false,
    val newProfileName: String = "",
    val selectedColorIndex: Int = 0,
    val selectedAvatarId: Int = 0, // 0 = legacy letter, 1-24 = cartoon avatar
    val isKidsProfile: Boolean = false,
    // Edit profile dialog state
    val editingProfile: Profile? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val profileManager: ProfileManager,
    private val traktRepository: TraktRepository,
    private val watchlistRepository: WatchlistRepository,
    private val iptvRepository: IptvRepository,
    private val cloudSyncRepository: CloudSyncRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        loadProfiles()
        observeProfiles()
    }

    private fun loadProfiles() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val profiles = profileRepository.getProfiles()
            val activeProfile = profileRepository.getActiveProfile()
            _uiState.value = _uiState.value.copy(
                profiles = profiles,
                activeProfile = activeProfile,
                isLoading = false
            )
        }
    }

    private fun observeProfiles() {
        viewModelScope.launch {
            profileRepository.profiles.collect { profiles ->
                _uiState.value = _uiState.value.copy(profiles = profiles)
            }
        }
        viewModelScope.launch {
            profileRepository.activeProfile.collect { profile ->
                _uiState.value = _uiState.value.copy(activeProfile = profile)
            }
        }
    }

    /**
     * Preload Continue Watching data when a profile is focused (before selection).
     * This enables instant display when the user actually selects the profile.
     */
    fun preloadForProfile(profile: Profile) {
        viewModelScope.launch {
            traktRepository.preloadContinueWatchingForProfile(profile.id)
        }
    }

    fun selectProfile(profile: Profile) {
        // CRITICAL: Clear ALL profile caches BEFORE switching to ensure complete isolation
        // This prevents Profile 1's Trakt data from showing in Profile 2
        traktRepository.clearAllProfileCaches()
        watchlistRepository.clearWatchlistCache()
        iptvRepository.invalidateCache()

        // Update ProfileManager's cache with the new profile ID
        // This ensures all profile-scoped keys use the correct prefix immediately
        profileManager.setCurrentProfileId(profile.id)
        profileManager.setCurrentProfileName(profile.name)

        // Activate preloaded cache for instant Continue Watching display
        // This transfers any preloaded data to the active cache before HomeViewModel loads
        traktRepository.activatePreloadedCache(profile.id)

        // Persist the active profile selection
        viewModelScope.launch {
            profileRepository.setActiveProfile(profile.id)
        }

        // Pull latest cloud state shortly after entering Home so profile selection stays fast.
        viewModelScope.launch(Dispatchers.IO) {
            delay(4_000L)
            if (profileRepository.getActiveProfileId() != profile.id) return@launch
            runCatching { cloudSyncRepository.pullFromCloud() }
        }

        // Defer IPTV warmup/network parse to keep initial Home navigation smooth.
        viewModelScope.launch(Dispatchers.IO) {
            delay(6_000L)
            if (profileRepository.getActiveProfileId() != profile.id) return@launch
            runCatching {
                iptvRepository.warmupFromCacheOnly()
                // Trigger a non-forced background refresh after startup settles.
                iptvRepository.loadSnapshot(
                    forcePlaylistReload = false,
                    forceEpgReload = false
                )
            }
        }
    }

    fun switchProfile() {
        // Clear all caches when leaving a profile to prevent data leakage
        traktRepository.clearAllProfileCaches()
        watchlistRepository.clearWatchlistCache()
        iptvRepository.invalidateCache()

        viewModelScope.launch {
            profileRepository.clearActiveProfile()
        }
    }

    // ========== Add Profile ==========

    fun showAddDialog() {
        _uiState.value = _uiState.value.copy(
            showAddDialog = true,
            newProfileName = "",
            selectedColorIndex = (_uiState.value.profiles.size) % ProfileColors.colors.size,
            selectedAvatarId = 0,
            isKidsProfile = false
        )
    }

    fun hideAddDialog() {
        _uiState.value = _uiState.value.copy(showAddDialog = false)
    }

    fun setNewProfileName(name: String) {
        _uiState.value = _uiState.value.copy(newProfileName = name)
    }

    fun setSelectedColorIndex(index: Int) {
        _uiState.value = _uiState.value.copy(selectedColorIndex = index)
    }

    fun setSelectedAvatarId(id: Int) {
        _uiState.value = _uiState.value.copy(selectedAvatarId = id)
    }

    fun createProfile() {
        val state = _uiState.value
        if (state.newProfileName.isBlank()) return

        viewModelScope.launch {
            profileRepository.createProfile(
                name = state.newProfileName.trim(),
                avatarColor = ProfileColors.getByIndex(state.selectedColorIndex),
                avatarId = state.selectedAvatarId,
                isKidsProfile = false
            )
            _uiState.value = _uiState.value.copy(showAddDialog = false)
        }
    }

    // ========== Edit Profile ==========

    fun showEditDialog(profile: Profile) {
        _uiState.value = _uiState.value.copy(
            editingProfile = profile,
            newProfileName = profile.name,
            selectedColorIndex = ProfileColors.colors.indexOf(profile.avatarColor).takeIf { it >= 0 } ?: 0,
            selectedAvatarId = profile.avatarId,
            isKidsProfile = false
        )
    }

    fun hideEditDialog() {
        _uiState.value = _uiState.value.copy(editingProfile = null)
    }

    fun updateProfile() {
        val state = _uiState.value
        val editing = state.editingProfile ?: return
        if (state.newProfileName.isBlank()) return

        viewModelScope.launch {
            profileRepository.updateProfile(
                editing.copy(
                    name = state.newProfileName.trim(),
                    avatarColor = ProfileColors.getByIndex(state.selectedColorIndex),
                    avatarId = state.selectedAvatarId,
                    isKidsProfile = false
                )
            )
            _uiState.value = _uiState.value.copy(editingProfile = null)
        }
    }

    // ========== Manage Mode ==========

    fun toggleManageMode() {
        _uiState.value = _uiState.value.copy(isManageMode = !_uiState.value.isManageMode)
    }

    fun exitManageMode() {
        _uiState.value = _uiState.value.copy(isManageMode = false)
    }

    fun deleteProfile(profile: Profile) {
        viewModelScope.launch {
            val activeId = _uiState.value.activeProfile?.id
            profileRepository.deleteProfile(profile.id)
            if (activeId == profile.id) {
                traktRepository.clearAllProfileCaches()
                watchlistRepository.clearWatchlistCache()
                iptvRepository.invalidateCache()
                profileManager.setCurrentProfileId("default")
                profileManager.setCurrentProfileName("default")
            }
        }
    }
}
