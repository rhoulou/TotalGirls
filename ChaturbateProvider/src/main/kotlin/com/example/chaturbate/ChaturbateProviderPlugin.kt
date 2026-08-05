package com.example.chaturbate

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class ChaturbateProviderPlugin : Plugin() {
    override fun load(context: Context) {
        // One provider per target, mirroring the original configure page
        // (f = Girls, m = Guys, t = Trans).
        registerMainAPI(ChaturbateProvider("f", "Chaturbate Girls"))
        registerMainAPI(ChaturbateProvider("m", "Chaturbate Guys"))
        registerMainAPI(ChaturbateProvider("t", "Chaturbate Trans"))
    }
}
