package com.example.coomer

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences

/**
 * Per-provider in-app settings for Coomer: which tube sources are active.
 *
 * The Coomer archive (coomer.st) is dead, so the provider scrapes two live,
 * server-rendered mirrors instead:
 *  - IncestFlix (https://incestflix.com.co) - WordPress tube, direct MP4s
 *  - CoomerVideo (https://official.coomer.com.co) - CoomerVideo tube, direct MP4s
 * The user picks one or both (mixed). Mixed is the default: home rows and
 * search results come from every active source, so if one host is down the
 * other still works.
 */
object Settings {
    private const val PREFS = "TotalGirls_settings"
    private const val KEY_SOURCE = "coomer_source"

    const val SRC_MIXED = "MIXED"
    const val SRC_INCESTFLIX = "INCESTFLIX"
    const val SRC_COOMERVIDEO = "COOMERVIDEO"

    const val INCESTFLIX = "https://incestflix.com.co"
    const val COOMERVIDEO = "https://official.coomer.com.co"

    private fun ctx(): Context? = CoomerProviderPlugin.appContext

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun sourceMode(): String {
        val c = ctx() ?: return SRC_MIXED
        return prefs(c).getString(KEY_SOURCE, SRC_MIXED) ?: SRC_MIXED
    }

    /** Active source base URLs, in display order. */
    fun sources(): List<String> = when (sourceMode()) {
        SRC_INCESTFLIX -> listOf(INCESTFLIX)
        SRC_COOMERVIDEO -> listOf(COOMERVIDEO)
        else -> listOf(INCESTFLIX, COOMERVIDEO)
    }

    // ------------------------------------------------------- dialog

    fun showSettings(context: Context) {
        val labels = arrayOf(
            "IncestFlix + CoomerVideo (mixed)",
            "IncestFlix only",
            "CoomerVideo only"
        )
        val values = arrayOf(SRC_MIXED, SRC_INCESTFLIX, SRC_COOMERVIDEO)
        val holder = intArrayOf(values.indexOf(sourceMode()).coerceAtLeast(0))

        AlertDialog.Builder(context)
            .setTitle("Coomer Settings")
            .setMessage("The Coomer archive (coomer.st) is dead, so the provider scrapes IncestFlix and CoomerVideo (mixed = home rows + search from both).")
            .setSingleChoiceItems(labels, holder[0]) { _, which -> holder[0] = which }
            .setPositiveButton("Save") { _, _ ->
                prefs(context).edit().putString(KEY_SOURCE, values[holder[0]]).apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
