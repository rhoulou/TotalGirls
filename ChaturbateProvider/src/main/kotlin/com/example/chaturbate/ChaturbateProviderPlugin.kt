package com.example.chaturbate

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ChaturbateProviderPlugin : BasePlugin() {
    override fun load() {
        // One provider per target, mirroring the original configure page
        // (f = Girls, m = Guys, t = Trans).
        registerMainAPI(ChaturbateProvider("f", "Chaturbate Girls"))
        registerMainAPI(ChaturbateProvider("m", "Chaturbate Guys"))
        registerMainAPI(ChaturbateProvider("t", "Chaturbate Trans"))
    }
}
