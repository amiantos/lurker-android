// Copyright (c) 2026 Brad Root
// SPDX-License-Identifier: MPL-2.0

package net.amiantos.lurker.platform

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * The activity behind a composition's `Context` — what tells a configuration change (the activity
 * coming straight back) from the screen going away for good.
 */
tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
