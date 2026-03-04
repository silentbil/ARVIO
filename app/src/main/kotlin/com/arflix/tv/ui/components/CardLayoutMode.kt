package com.arflix.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.util.profilesDataStore
import com.arflix.tv.util.settingsDataStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

enum class CardLayoutMode {
    LANDSCAPE,
    POSTER
}

const val CARD_LAYOUT_MODE_LANDSCAPE = "Landscape"
const val CARD_LAYOUT_MODE_POSTER = "Poster"

private val cardLayoutModeKey = stringPreferencesKey("card_layout_mode")
private val activeProfileIdKey = stringPreferencesKey("active_profile_id")

private fun profileCardLayoutModeKey(profileId: String): Preferences.Key<String> {
    return stringPreferencesKey("profile_${profileId}_card_layout_mode")
}

fun normalizeCardLayoutMode(raw: String?): String {
    return if (raw?.trim()?.equals(CARD_LAYOUT_MODE_POSTER, ignoreCase = true) == true) {
        CARD_LAYOUT_MODE_POSTER
    } else {
        CARD_LAYOUT_MODE_LANDSCAPE
    }
}

fun parseCardLayoutMode(raw: String?): CardLayoutMode {
    return if (normalizeCardLayoutMode(raw) == CARD_LAYOUT_MODE_POSTER) {
        CardLayoutMode.POSTER
    } else {
        CardLayoutMode.LANDSCAPE
    }
}

@Composable
fun rememberCardLayoutMode(): CardLayoutMode {
    val context = LocalContext.current
    val modeFlow = remember(context) {
        combine(context.profilesDataStore.data, context.settingsDataStore.data) { profilePrefs, settingsPrefs ->
            val profileId = profilePrefs[activeProfileIdKey].orEmpty().ifBlank { "default" }
            val profileValue = settingsPrefs[profileCardLayoutModeKey(profileId)]
            val legacyValue = settingsPrefs[cardLayoutModeKey]
            parseCardLayoutMode(profileValue ?: legacyValue)
        }
            .distinctUntilChanged()
    }
    val mode by modeFlow.collectAsState(initial = CardLayoutMode.LANDSCAPE)
    return mode
}
