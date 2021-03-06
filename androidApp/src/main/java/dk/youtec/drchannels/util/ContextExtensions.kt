package dk.youtec.drchannels.util

import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.preference.PreferenceManager

fun Context.isTv(): Boolean = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

fun Context.toast(@StringRes message: Int): Toast = Toast
    .makeText(this, message, Toast.LENGTH_SHORT)
    .apply {
        show()
    }

fun Context.toast(message: CharSequence): Toast = Toast
    .makeText(this, message, Toast.LENGTH_SHORT)
    .apply {
        show()
    }

inline val Context.displayMetrics: android.util.DisplayMetrics
    get() = resources.displayMetrics

inline val Context.defaultSharedPreferences: android.content.SharedPreferences
    get() = PreferenceManager.getDefaultSharedPreferences(this)
