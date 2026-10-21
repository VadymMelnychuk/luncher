package com.luncher.domain.slideshow

/**
 * The sites a slideshow fetches from: where each one's listing is, and the pictures a listing
 * names, the newest first, those that can't [fit the slideshow][fitsTheSlideshow] left out as far
 * as the listing tells their size. [tmdbKey] is TMDB's API key, built in; without one, TMDB isn't
 * [offered].
 *
 * Each listing's format was read from the site's answers on 2026-10-21; an answer that isn't
 * that format is no listing (null), and an entry that isn't is left out, the others kept.
 */
class WebSources(private val tmdbKey: String = "") {

    /** The sources the background editor offers, in order: the background's picture first. */
    val offered: List<SlideshowSource> =
        SlideshowSource.entries.filter { it != SlideshowSource.TMDB || tmdbKey.isNotEmpty() }

    /** Where [source]'s listing is; null for [SlideshowSource.PICTURE], and for one not [offered]. */
    fun listing(source: SlideshowSource): String? = when (source) {
        SlideshowSource.PICTURE -> null
        SlideshowSource.BING -> "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-US"
        SlideshowSource.NASA_EARTH -> "https://epic.gsfc.nasa.gov/api/natural"
        SlideshowSource.WIKIMEDIA -> "https://commons.wikimedia.org/w/api.php?action=featuredfeed&feed=potd&feedformat=rss&language=en"
        SlideshowSource.NASA_APOD -> "https://api.nasa.gov/planetary/apod?api_key=DEMO_KEY&count=8"
        SlideshowSource.TMDB -> if (tmdbKey.isEmpty()) null else "https://api.themoviedb.org/3/trending/movie/week?api_key=$tmdbKey"
    }

    /** The pictures [listing], [source]'s answer, names, the newest first; null if it isn't a listing of that source. */
    fun pictures(source: SlideshowSource, listing: String): List<WebPicture>? = when (source) {
        SlideshowSource.PICTURE -> null
        SlideshowSource.BING -> bingPictures(listing)
        SlideshowSource.NASA_EARTH -> earthPictures(listing)
        SlideshowSource.WIKIMEDIA -> wikimediaPictures(listing)
        SlideshowSource.NASA_APOD -> apodPictures(listing)
        SlideshowSource.TMDB -> tmdbPictures(listing)
    }?.filter { it.url.startsWith(HTTPS) }?.distinctBy { it.key }
}

private const val HTTPS = "https://"

/** The JSON [text], or null if it isn't JSON. */
private fun jsonOrNull(text: String): Any? =
    try {
        readJson(text)
    } catch (e: IllegalArgumentException) {
        null
    }

/**
 * Bing's pictures of the day, the newest first: `{"images": [{"url": "/th?id=…_1920x1080.jpg&…",
 * "urlbase", "startdate": "20261021", "title"}, …]}`, each 1920 x 1080 px.
 */
internal fun bingPictures(text: String): List<WebPicture>? {
    val json = jsonOrNull(text) as? Map<*, *> ?: return null
    if (json["images"] !is List<*>) return null
    return json.array("images").mapNotNull { image ->
        val url = image.string("url") ?: return@mapNotNull null
        val date = image.string("startdate")?.let(::compactDate) ?: return@mapNotNull null
        WebPicture(
            key = image.string("urlbase") ?: url,
            url = if (url.startsWith("/")) "https://www.bing.com$url" else url,
            name = webPictureName("Bing", date, image.string("title").orEmpty()),
        )
    }
}

/**
 * The Earth from NASA's EPIC camera, the newest first: `[{"image": "epic_1b_20261018010436",
 * "date": "2026-10-18 00:59:48"}, …]`, the last day's pictures, each 2048 x 2048 px, the globe on
 * black, drawn on a wide black picture ([WebFraming.ON_BLACK]).
 */
internal fun earthPictures(text: String): List<WebPicture>? {
    val json = jsonOrNull(text) as? List<*> ?: return null
    return json.mapNotNull { entry ->
        val image = entry.string("image")?.takeIf { IMAGE_NAME.matches(it) } ?: return@mapNotNull null
        val date = entry.string("date")?.let { DATE_TIME.matchEntire(it) } ?: return@mapNotNull null
        val (year, month, day, hour, minute) = date.destructured
        date.value to WebPicture(
            key = image,
            url = "https://epic.gsfc.nasa.gov/archive/natural/$year/$month/$day/jpg/$image.jpg",
            name = webPictureName("NASA Earth", "$year-$month-$day", "$hour-$minute"),
            framing = WebFraming.ON_BLACK,
        )
    }.sortedByDescending { it.first }.map { it.second }
}

private val IMAGE_NAME = Regex("[A-Za-z0-9_]+")
private val DATE_TIME = Regex("""(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):\d{2}""")

/**
 * Wikimedia Commons' pictures of the day, the newest first: an RSS feed of the last 10 days, each
 * item's description the day's HTML, its picture an `<img src=".../thumb/…/330px-Name.jpg"
 * data-file-width="5139" data-file-height="3426">` linked to `/wiki/File:Name.jpg`. Only the wide
 * ones, as their size says, each as a thumbnail 1920 px wide (1280 for one less wide); one
 * narrower than that is left out.
 */
internal fun wikimediaPictures(text: String): List<WebPicture>? {
    if (!text.contains("<rss")) return null
    return ITEM.findAll(text).mapNotNull { item ->
        val day = POTD_DAY.find(item.value)?.destructured ?: return@mapNotNull null
        val (year, month, date) = day
        val html = unescapeXml(DESCRIPTION.find(item.value)?.groupValues?.get(1) ?: return@mapNotNull null)
        val image = IMG.find(html)?.value ?: return@mapNotNull null
        val src = attribute(image, "src")?.let { unescapeXml(it).substringBefore('?') } ?: return@mapNotNull null
        val width = attribute(image, "data-file-width")?.toIntOrNull()
        val height = attribute(image, "data-file-height")?.toIntOrNull()
        if (width != null && height != null && !fitsTheSlideshow(width, height)) return@mapNotNull null
        val wanted = when {
            width == null || width >= FULL_HD -> FULL_HD
            width >= HD -> HD
            else -> return@mapNotNull null
        }
        val thumbnail = THUMBNAIL_WIDTH.find(src) ?: return@mapNotNull null
        val file = FILE.find(html)?.groupValues?.get(1)?.let(::percentDecoded) ?: src.substringAfterLast("px-")
        "$year$month$date" to WebPicture(
            key = file,
            url = src.replaceRange(thumbnail.range, "/${wanted}px-"),
            name = webPictureName("Wikimedia", "$year-$month-$date", file.substringBeforeLast('.').replace('_', ' ')),
        )
    }.sortedByDescending { it.first }.map { it.second }.toList()
}

private val ITEM = Regex("<item>.*?</item>", RegexOption.DOT_MATCHES_ALL)
private val POTD_DAY = Regex("""potd/(\d{4})(\d{2})(\d{2})""")
private val DESCRIPTION = Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL)
private val IMG = Regex("<img\\s[^>]*>")
private val FILE = Regex("href=\"/wiki/File:([^\"]+)\"")
private val THUMBNAIL_WIDTH = Regex("/\\d+px-")
private const val FULL_HD = 1920
private const val HD = 1280

/** The value of [name] in the HTML tag [tag]; null if it hasn't it. */
private fun attribute(tag: String, name: String): String? =
    Regex("\\s${Regex.escape(name)}=\"([^\"]*)\"").find(tag)?.groupValues?.get(1)

/** XML's escapes undone, once: `&lt;` is `<`, `&amp;lt;` is `&lt;`. */
private fun unescapeXml(text: String): String =
    ENTITY.replace(text) { entity ->
        when (val name = entity.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            else -> when {
                name.startsWith("#x") -> name.drop(2).toIntOrNull(16)?.let(::character)
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::character)
                else -> null
            } ?: entity.value
        }
    }

private val ENTITY = Regex("&(#x?[0-9A-Fa-f]+|[a-z]+);")

private fun character(code: Int): String? = if (Character.isValidCodePoint(code)) String(Character.toChars(code)) else null

/** A URL's `%28` as `(`; the text as it is if it isn't percent-encoded UTF-8. */
private fun percentDecoded(text: String): String {
    if ('%' !in text) return text
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%' && i + 2 < text.length) {
            val byte = text.substring(i + 1, i + 3).toIntOrNull(16) ?: return text
            bytes.write(byte)
            i += 3
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

/**
 * NASA's Astronomy Pictures of the Day, the newest first: `[{"date": "2026-07-20", "media_type":
 * "image", "url", "hdurl", "title"}, …]`, 8 days at random. Only the pictures, not the videos, by
 * their `url` (`hdurl` can be very large); their size isn't said, so it's checked once
 * downloaded.
 */
internal fun apodPictures(text: String): List<WebPicture>? {
    val json = jsonOrNull(text) as? List<*> ?: return null
    return json.mapNotNull { entry ->
        if (entry.string("media_type") != "image") return@mapNotNull null
        val url = entry.string("url") ?: return@mapNotNull null
        val date = entry.string("date")?.takeIf { DATE.matches(it) } ?: return@mapNotNull null
        date to WebPicture(key = url, url = url, name = webPictureName("NASA APOD", date, entry.string("title").orEmpty()))
    }.sortedByDescending { it.first }.map { it.second }
}

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * The week's trending films on TMDB, in its order: `{"results": [{"title", "backdrop_path":
 * "/abc.jpg"}, …]}`, each backdrop 16:9, as a picture 1280 px wide. A film without one is left out.
 */
internal fun tmdbPictures(text: String): List<WebPicture>? {
    val json = jsonOrNull(text) as? Map<*, *> ?: return null
    if (json["results"] !is List<*>) return null
    return json.array("results").mapNotNull { film ->
        val backdrop = film.string("backdrop_path")?.takeIf { it.startsWith("/") } ?: return@mapNotNull null
        WebPicture(key = backdrop, url = "https://image.tmdb.org/t/p/w1280$backdrop", name = webPictureName("TMDB", film.string("title").orEmpty()))
    }
}

/** "20261021" as "2026-10-21"; null if it isn't a date. */
private fun compactDate(text: String): String? =
    COMPACT_DATE.matchEntire(text)?.destructured?.let { (year, month, day) -> "$year-$month-$day" }

private val COMPACT_DATE = Regex("""(\d{4})(\d{2})(\d{2})""")
