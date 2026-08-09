package com.example.porntube

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.text.InputType
import android.util.TypedValue
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Per-provider in-app settings for PornTube: the addon API base URL.
 *
 * Defaults to the public PornTube Stremio addon (dirty-pink.ers.pw) which only
 * serves torrent streams. Paste a tokenized base URL (ptube.ers.pw/<config>)
 * from a debrid-enabled configuration to also get direct playback links.
 */
object Settings {
    private const val PREFS = "TotalGirls_settings"

    private const val KEY_BASE = "porntube_base"

    const val DEFAULT_BASE = "https://dirty-pink.ers.pw"

    private fun ctx(): Context? = PornTubeProviderPlugin.appContext

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** API base URL, already stripped of any trailing slash or /manifest.json. */
    fun base(): String {
        val c = ctx() ?: return DEFAULT_BASE
        return normalize(prefs(c).getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE)
    }

    fun normalize(raw: String): String {
        var base = raw.trim().trimEnd('/')
        if (base.endsWith("/manifest.json", ignoreCase = true)) {
            base = base.substring(0, base.length - "/manifest.json".length).trimEnd('/')
        }
        return base.ifEmpty { DEFAULT_BASE }
    }

    // ------------------------------------------------------- dialog

    fun showSettings(context: Context) {
        val prefs = prefs(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }

        val label = TextView(context).apply {
            text = "API base URL (default: dirty-pink.ers.pw). Paste a tokenized URL (ptube.ers.pw/<config>) to enable debrid direct streams."
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, 0, 0, dp(context, 8))
        }
        root.addView(label)

        val baseInput = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(KEY_BASE, DEFAULT_BASE) ?: DEFAULT_BASE)
        }
        root.addView(baseInput)

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("PornTube Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(KEY_BASE, baseInput.text.toString().trim())
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
