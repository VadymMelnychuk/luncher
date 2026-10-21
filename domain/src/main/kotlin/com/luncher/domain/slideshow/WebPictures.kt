package com.luncher.domain.slideshow

import com.luncher.domain.jobs.Cancellable

/**
 * Port: the internet, for the slideshow: a source's listing, and its pictures, each downloaded
 * into a file the library adds from, as a [Source] (a Uri on Android, which this module can't
 * name). Slow, and it may never answer (no network, a server that hangs): each call answers later,
 * on the main thread, or never once it's cancelled, and gives up by itself after a time limit.
 * Implemented in :platform on HttpURLConnection, its waits on threads of their own, never on the
 * jobs' thread (docs/ARCHITECTURE.md#background-work-and-changes-while-shown, point 6).
 */
interface WebPictures<Source> {

    /**
     * The text at [url] (a source's listing), then [done] with it, or with null if it couldn't be
     * had (no answer, an error, too long). The handle cancels it: then [done] never runs.
     */
    fun read(url: String, done: (String?) -> Unit): Cancellable

    /**
     * Downloads [picture] into a file of its own, framed as it says, then [done] with how that
     * went. The handle cancels it: then [done] never runs, and nothing is left.
     */
    fun download(picture: WebPicture, done: (Download<Source>) -> Unit): Cancellable

    /** Deletes the file [download] gave, once the library has added it, or refused it. */
    fun discard(source: Source)
}

/** How downloading a picture went. */
sealed interface Download<out Source> {

    /** Downloaded into [source], ready for the library to add. */
    data class Done<Source>(val source: Source) : Download<Source>

    /**
     * The server answered, but not with a picture for the slideshow: an error for that picture,
     * not a picture, too large, or one that doesn't [fit][fitsTheSlideshow]. Not asked for again.
     */
    data object Unfit : Download<Nothing>

    /** No answer: no network, or a server that hangs. The fetch ends, to be tried again later. */
    data object Failed : Download<Nothing>
}

/** A picture a source lists: where it's downloaded from, and what it's called in the library. */
data class WebPicture(
    /** What tells it from the source's others, from one listing to the next: its file at the source, say. */
    val key: String,
    /** Where it's downloaded from: `https://` only. */
    val url: String,
    /** Its name in the library ([webPictureName]): the source, its date, its title. */
    val name: String,
    val framing: WebFraming = WebFraming.AS_IS,
)

/** How a downloaded picture is made a slide. */
enum class WebFraming {
    /** Kept as it is: a wide picture, which the background's options frame as any. */
    AS_IS,

    /** Drawn whole onto a black 16:9 picture, as tall as it: the Earth, round, on black. */
    ON_BLACK,
}

/**
 * Whether a picture of [width] x [height] px makes a slide: wide (at least [WIDE] times as wide as
 * it's tall), as the screen is, so filling it cuts little, and at least [MIN_WIDTH] px wide, so it
 * isn't blurred on a TV. A source that doesn't say a picture's size is checked once it's
 * downloaded.
 */
fun fitsTheSlideshow(width: Int, height: Int): Boolean = width >= MIN_WIDTH && width >= height * WIDE

/** How much wider than tall a slide is, at least. */
const val WIDE = 1.3

/** The narrowest slide, in px. */
const val MIN_WIDTH = 1000

/**
 * A picture's name in the library, from [parts] (the source, its date, its title): a label only,
 * so without the characters a file's name can't have, which the download's file is named, and at
 * most [MAX_NAME] characters, cut where a character ends.
 */
fun webPictureName(vararg parts: String): String {
    val joined = parts.joinToString(" ") { part -> part.map { if (it.isISOControl() || it in NOT_IN_NAMES) ' ' else it }.joinToString("") }
    val name = joined.trim().replace(SPACES, " ")
    if (name.length <= MAX_NAME) return name
    val end = if (name[MAX_NAME - 1].isHighSurrogate()) MAX_NAME - 1 else MAX_NAME
    return name.substring(0, end).trimEnd()
}

private const val NOT_IN_NAMES = "/\\:*?\"<>|"
private val SPACES = Regex("\\s+")

/** The longest name of a downloaded picture: within a file name's 255 bytes in UTF-8, 3 bytes a character at most. */
private const val MAX_NAME = 80
