// Use an integer for version numbers - bump it to trigger updates in CS3.
version = 5

cloudstream {
    language = "en"
    description = "Coomer archive (NSFW). Creator profiles and video episodes from the Coomer archive (OnlyFans/Fansly/Patreon mirror) - creator rows, search, profile metadata, direct video links, switchable domain."
    authors = listOf("rhoulou")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 3 // beta until you are happy with it
    tvTypes = listOf("NSFW")

    iconUrl = "https://raw.githubusercontent.com/rhoulou/TotalGirls/main/coomer-logo.png"
}
