// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 13

cloudstream {
    language = "en"
    description = "Live Chaturbate cams (Girls). Scrapes chaturbate.com directly from the phone - roomlist + dossier logic, master HLS passed straight to the player."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3 // beta until you are happy with it
    tvTypes = listOf("Live")

    iconUrl = "https://rhoulou.github.io/cloudstream/chaturbate.png"
}
