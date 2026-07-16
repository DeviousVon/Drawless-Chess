package com.drawlesschess.ui

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** A locale-neutral UI message that is resolved only at the presentation boundary. */
internal sealed interface UiText {
    data class Resource(@param:StringRes val id: Int, val arguments: List<Any> = emptyList()) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Resource -> stringResource(id, *arguments.toTypedArray())
    }

    fun resolve(context: Context): String = when (this) {
        is Resource -> context.getString(id, *arguments.toTypedArray())
    }
}

internal fun uiText(@StringRes id: Int, vararg arguments: Any): UiText =
    UiText.Resource(id, arguments.toList())
