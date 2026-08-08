package com.example.freecams

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
 * Per-provider in-app settings for FreeCams: optional proxy prefix and the
 * cookie string used for the room page (freecams.me sets a short-lived
 * cf_clearance cookie, so the default goes stale and must be refreshed or the
 * request falls back to the proxy).
 */
object Settings {
    private const val PREFS = "TotalGirls_settings"

    private const val KEY_PROXY = "freecams_proxy"
    private const val KEY_COOKIE = "freecams_cookie"

    const val DEFAULT_PROXY = ""

    // Cookie seen in bobs/freecams/hls.php. cf_clearance expires quickly; the
    // room-page fetch falls back to the proxy when this cookie is stale.
    const val DEFAULT_COOKIE = "csrftoken=FGv98JDh8jbjcHDEWkrRcaCIySj3pijl; sbr=sec:sbr5d310c53-783d-4946-8b25-d7dd07ae8698:1wGjMT:phYIQ4etgsRnxRldMej7KuS-X8uKl3cV6TS8VPCA_44; ag={\"teen-cams\":136,\"18to21-cams\":221,\"20to30-cams\":86,\"30to50-cams\":1}; agreeterms=1; fromaffiliate=1"

    private fun ctx(): Context? = FreecamsProviderPlugin.appContext

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Proxy prefix (already includes ?url=). Blank means direct (no proxy). */
    fun proxy(): String {
        val c = ctx() ?: return DEFAULT_PROXY
        return prefs(c).getString(KEY_PROXY, DEFAULT_PROXY)?.trim()
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_PROXY
    }

    /** Cookie header value for the room page request. */
    fun cookie(): String {
        val c = ctx() ?: return DEFAULT_COOKIE
        return prefs(c).getString(KEY_COOKIE, DEFAULT_COOKIE)?.trim()
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_COOKIE
    }

    // ------------------------------------------------------- dialog

    fun showSettings(context: Context) {
        val prefs = prefs(context)

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), 0)
        }

        fun label(text: String) = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(context, 16), 0, dp(context, 4))
        }

        root.addView(label("Proxy prefix (blank = direct, then auto proxy fallback)"))
        val proxyInput = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(KEY_PROXY, DEFAULT_PROXY) ?: DEFAULT_PROXY)
        }
        root.addView(proxyInput)

        root.addView(label("Room page cookie (incl. cf_clearance; stale cookies fall back to proxy)"))
        val cookieInput = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs.getString(KEY_COOKIE, DEFAULT_COOKIE) ?: DEFAULT_COOKIE)
        }
        root.addView(cookieInput)

        val scroll = ScrollView(context).apply { addView(root) }

        AlertDialog.Builder(context)
            .setTitle("FreeCams Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                prefs.edit()
                    .putString(KEY_PROXY, proxyInput.text.toString().trim())
                    .putString(KEY_COOKIE, cookieInput.text.toString().trim())
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
