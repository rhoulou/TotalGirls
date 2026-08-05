// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 1

cloudstream {
    language = "en"
    description = "Live Chaturbate cams (Girls/Guys/Trans). Bridge to the Chaturbate Stremio addon server - set the addon URL in ChaturbateProvider.kt before building."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3 // beta until you are happy with it
    tvTypes = listOf("Others")

    iconUrl = "https://www.google.com/s2/favicons?domain=chaturbate.com&sz=%size%"
}
