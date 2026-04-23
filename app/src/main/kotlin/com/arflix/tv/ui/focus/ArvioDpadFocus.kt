package com.arflix.tv.ui.focus

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
fun Modifier.arvioDpadFocusGroup(
    restoreFocusRequester: FocusRequester? = null
): Modifier {
    val restorer = if (restoreFocusRequester != null) {
        Modifier.focusRestorer { restoreFocusRequester }
    } else {
        Modifier.focusRestorer()
    }
    return this.then(restorer).focusGroup()
}
