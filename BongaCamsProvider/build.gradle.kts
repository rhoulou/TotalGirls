// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 3

cloudstream {
    language = "en"
    description = "Live BongaCams cams (girls only). Lists via the open lemoncams.com API - no Cloudflare cookie wall - and passes the bcvcdn HLS master straight to the player."
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

    iconUrl = "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRcqPE4qe0Lwql6tVNmAmrw3Glpxf8BhJupzsdNiRZ6xw&s=10"
}
