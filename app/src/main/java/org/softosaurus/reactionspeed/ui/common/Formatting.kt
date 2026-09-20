package org.softosaurus.reactionspeed.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.util.Locale
import org.softosaurus.reactionspeed.R

/** `"231 ms"`, or an em dash when the value is unknown. */
@Composable
fun msOrDash(value: Int?): String =
    if (value == null || value <= 0) {
        stringResource(R.string.value_placeholder)
    } else {
        stringResource(R.string.ms_format, value)
    }

/** `"24.7 ms"` — one decimal, for the standard deviation. */
@Composable
fun msWithDecimal(value: Double): String =
    stringResource(R.string.ms_decimal_format, String.format(Locale.getDefault(), "%.1f", value))
