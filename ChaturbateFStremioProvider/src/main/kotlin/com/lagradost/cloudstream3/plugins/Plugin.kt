package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * Compile-time stand-in for the host app's com.lagradost.cloudstream3.plugins.Plugin.
 *
 * The host app ships the Plugin class only inside its APK (the jitpack `app`
 * artifact is not built), so plugins cannot depend on it. The host loads plugin
 * dex files with PathClassLoader(file, context.classLoader), i.e. parent-first:
 * at runtime the app's real Plugin is always resolved and this copy is inert.
 */
abstract class Plugin : BasePlugin() {
    open fun load(context: Context) {
        load()
    }

    var resources: Resources? = null

    var openSettings: ((context: Context) -> Unit)? = null
}
