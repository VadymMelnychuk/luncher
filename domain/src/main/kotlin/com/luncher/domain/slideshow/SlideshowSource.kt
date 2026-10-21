package com.luncher.domain.slideshow

/**
 * Where the home screen's wallpaper comes from: the background's own picture, of the library, or
 * a slideshow of pictures from a site on the internet. Each screen names it in its language, in an
 * exhaustive `when`. Which ones are offered: [WebSources.offered].
 */
enum class SlideshowSource {
    /** The background's picture, or none: no slideshow, nothing fetched. */
    PICTURE,

    /** Bing's picture of the day, for the last 8 days. */
    BING,

    /** NASA's EPIC camera: the whole Earth as seen from space, on a black screen. */
    NASA_EARTH,

    /** Wikimedia Commons' picture of the day, the wide ones only. */
    WIKIMEDIA,

    /** NASA's Astronomy Picture of the Day, the wide pictures only (no videos). */
    NASA_APOD,

    /** The week's trending films on TMDB, their backdrops; only with an API key built in. */
    TMDB,
}

/** How long each picture of a slideshow shows before the next one. */
enum class SlideshowInterval(val minutes: Int) {
    FIVE(5),
    FIFTEEN(15),
    THIRTY(30),
    SIXTY(60),
    ;

    val millis: Long get() = minutes * 60_000L
}
