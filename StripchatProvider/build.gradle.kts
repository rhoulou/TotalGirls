// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 9

cloudstream {
    language = "en"
    description = "Live Stripchat cams (Girls). Scrapes stripchat.com directly from the phone - guest hash + roomlist logic, saawsedge HLS master passed to the player."
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

    iconUrl = "https://rhoulou.github.io/cloudstream/stripchat.png"
}
