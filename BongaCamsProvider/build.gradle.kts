// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 1

cloudstream {
    language = "en"
    description = "Live BongaCams cams. Scrapes bongacams.com directly from the phone - listing_v3.php categories (female only), bcvcdn HLS master passed to the player."
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

    iconUrl = "https://rhoulou.github.io/cloudstream/bongacams.png"
}
