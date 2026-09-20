package com.devfahim00.sdr2hdr

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** Persists the day / night choice made with the header toggle. */
object ThemePrefs {
    private const val FILE = "sdr2hdr_prefs"
    private const val KEY_NIGHT_MODE = "night_mode"

    fun mode(context: Context): Int =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun save(context: Context, mode: Int) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_NIGHT_MODE, mode)
            .apply()
    }
}

class SdrApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(ThemePrefs.mode(this))
    }
}
