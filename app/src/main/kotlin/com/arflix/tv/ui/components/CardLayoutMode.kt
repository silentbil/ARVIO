package com.arflix.tv.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.arflix.tv.data.repository.CloudSyncScope
import com.arflix.tv.di.RepositoryAccessEntryPoint
import com.arflix.tv.ui.focus.arvioDpadFocusGroup
import com.arflix.tv.ui.skin.ArvioFocusableSurface
import com.arflix.tv.ui.skin.ArvioSkin
import com.arflix.tv.ui.skin.rememberArvioCardShape
import com.arflix.tv.util.profilesDataStore
import com.arflix.tv.util.settingsDataStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

enum class CardLayoutMode {
    LANDSCAPE,
    POSTER
}

const val CARD_LAYOUT_MODE_LANDSCAPE = "Landscape"
const val CARD_LAYOUT_MODE_POSTER = "Poster"

private val cardLayoutModeKey = stringPreferencesKey("card_layout_mode")
private val activeProfileIdKey = stringPreferencesKey("active_profile_id")
private const val CATALOGUE_ROW_LAYOUT_PREFIX = "catalogue_row_layout_"

private fun profileCardLayoutModeKey(profileId: String): Preferences.Key<String> {
    return stringPreferencesKey("profile_${profileId}_card_layout_mode")
}

fun catalogueRowLayoutPreferenceName(rowKey: String): String {
    val normalizedRowKey = normalizeCatalogueRowLayoutKey(rowKey)
    return "$CATALOGUE_ROW_LAYOUT_PREFIX$normalizedRowKey"
}

fun profileCatalogueRowLayoutPreferenceName(profileId: String, rowKey: String): String {
    return "profile_${profileId}_${catalogueRowLayoutPreferenceName(rowKey)}"
}

fun catalogueRowLayoutPreferencePrefixFor(profileId: String): String {
    return "profile_${profileId}_$CATALOGUE_ROW_LAYOUT_PREFIX"
}

fun catalogueRowLayoutKeyFromPreferenceName(profileId: String, preferenceName: String): String? {
    val prefix = catalogueRowLayoutPreferencePrefixFor(profileId)
    return preferenceName.removePrefix(prefix).takeIf { it != preferenceName && it.isNotBlank() }
}

fun profileCatalogueRowLayoutModeKey(
    profileId: String,
    rowKey: String
): Preferences.Key<String> {
    return stringPreferencesKey(profileCatalogueRowLayoutPreferenceName(profileId, rowKey))
}

fun normalizeCatalogueRowLayoutKey(rowKey: String): String {
    return rowKey
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_.:-]+"), "_")
        .trim('_')
        .ifBlank { "default" }
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

fun toggledCardLayoutMode(mode: CardLayoutMode): String {
    return if (mode == CardLayoutMode.POSTER) {
        CARD_LAYOUT_MODE_LANDSCAPE
    } else {
        CARD_LAYOUT_MODE_POSTER
    }
}

suspend fun toggleCatalogueRowLayoutMode(
    context: Context,
    rowKey: String
) {
    val normalizedRowKey = normalizeCatalogueRowLayoutKey(rowKey)
    val entryPoint = EntryPointAccessors.fromApplication(
        context,
        RepositoryAccessEntryPoint::class.java
    )
    val profileId = entryPoint.profileManager().getProfileId()
    val key = profileCatalogueRowLayoutModeKey(profileId, normalizedRowKey)
    context.settingsDataStore.edit { prefs ->
        val fallback = normalizeCardLayoutMode(
            prefs[profileCardLayoutModeKey(profileId)] ?: prefs[cardLayoutModeKey]
        )
        val current = parseCardLayoutMode(prefs[key] ?: fallback)
        prefs[key] = toggledCardLayoutMode(current)
    }
    entryPoint.cloudSyncInvalidationBus().markDirty(
        CloudSyncScope.PROFILE_SETTINGS,
        profileId,
        "catalogue row layout"
    )
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
    val mode by modeFlow.collectAsStateWithLifecycle(initialValue = CardLayoutMode.LANDSCAPE)
    return mode
}

@Composable
fun rememberCatalogueRowLayoutMode(rowKey: String): CardLayoutMode {
    val context = LocalContext.current
    val normalizedRowKey = remember(rowKey) { normalizeCatalogueRowLayoutKey(rowKey) }
    val rowModeFlow = remember(context, normalizedRowKey) {
        combine(context.profilesDataStore.data, context.settingsDataStore.data) { profilePrefs, settingsPrefs ->
            val profileId = profilePrefs[activeProfileIdKey].orEmpty().ifBlank { "default" }
            val rowKey = profileCatalogueRowLayoutModeKey(profileId, normalizedRowKey)
            val profileValue = settingsPrefs[profileCardLayoutModeKey(profileId)]
            val legacyValue = settingsPrefs[cardLayoutModeKey]
            val fallbackValue = normalizeCardLayoutMode(profileValue ?: legacyValue)
            val rowValue = settingsPrefs[rowKey]
            RowLayoutSnapshot(
                profileId = profileId,
                rowKey = rowKey,
                fallbackValue = fallbackValue,
                mode = parseCardLayoutMode(rowValue ?: fallbackValue),
                shouldSeed = rowValue == null
            )
        }.distinctUntilChanged()
    }
    val snapshot by rowModeFlow.collectAsStateWithLifecycle(
        initialValue = RowLayoutSnapshot(
            profileId = "default",
            rowKey = profileCatalogueRowLayoutModeKey("default", normalizedRowKey),
            fallbackValue = CARD_LAYOUT_MODE_LANDSCAPE,
            mode = CardLayoutMode.LANDSCAPE,
            shouldSeed = false
        )
    )

    LaunchedEffect(snapshot.profileId, snapshot.rowKey, snapshot.fallbackValue, snapshot.shouldSeed) {
        if (snapshot.shouldSeed) {
            context.settingsDataStore.edit { prefs ->
                if (prefs[snapshot.rowKey] == null) {
                    prefs[snapshot.rowKey] = normalizeCardLayoutMode(snapshot.fallbackValue)
                }
            }
        }
    }

    return snapshot.mode
}

@Composable
fun CatalogueRowLayoutToggleButton(
    rowKey: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    forceFocused: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val normalizedRowKey = remember(rowKey) { normalizeCatalogueRowLayoutKey(rowKey) }
    val mode = rememberCatalogueRowLayoutMode(normalizedRowKey)
    val shape = rememberArvioCardShape(6.dp)
    val label = if (mode == CardLayoutMode.POSTER) "P" else "L"

    ArvioFocusableSurface(
        modifier = modifier
            .size(28.dp)
            .arvioDpadFocusGroup(enableFocusRestorer = false),
        shape = shape,
        backgroundColor = Color.White.copy(alpha = if (enabled) 0.08f else 0.03f),
        outlineColor = ArvioSkin.colors.focusOutline,
        outlineWidth = 2.dp,
        focusedScale = 1.08f,
        pressedScale = 0.95f,
        enableSystemFocus = enabled,
        onClick = {
            if (!enabled) return@ArvioFocusableSurface
            scope.launch {
                toggleCatalogueRowLayoutMode(context, normalizedRowKey)
            }
        }
    ) { isFocused ->
        val visualFocused = isFocused || forceFocused
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    color = Color.White.copy(alpha = if (visualFocused) 0.14f else 0.04f),
                    shape = RoundedCornerShape(6.dp)
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = if (visualFocused) 0.32f else 0.14f),
                    shape = RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = if (enabled) 0.92f else 0.42f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private data class RowLayoutSnapshot(
    val profileId: String,
    val rowKey: Preferences.Key<String>,
    val fallbackValue: String,
    val mode: CardLayoutMode,
    val shouldSeed: Boolean
)
