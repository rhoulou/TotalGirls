// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 13

cloudstream {
    language = "en"
    description = "Live CamSoda cams (girls only). Uses the official camsoda.com API through a personal proxy and passes the livemediahost LL-HLS master straight to the player."
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

    iconUrl = "https://webcamstartup.com/wp-content/uploads/2024/05/PPe3W893RXS7AG2MoYGd_St6ScIFr05xhO0zJ.jpg"
}
