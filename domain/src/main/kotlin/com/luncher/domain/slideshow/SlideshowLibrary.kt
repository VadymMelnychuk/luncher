package com.luncher.domain.slideshow

import com.luncher.domain.pictures.PictureId

/**
 * Port: which pictures of the library each source's slideshow fetched, and what else it keeps of
 * it. The pictures themselves are the library's ([com.luncher.domain.pictures.Pictures]); this
 * names them. Implemented in :platform on SharedPreferences. Quick: read and written on the main
 * thread.
 */
interface SlideshowLibrary {

    /** What's kept of [source]'s slideshow; empty if nothing is. */
    fun read(source: SlideshowSource): SourceSlides

    /** Keeps [slides] for [source], in place of what was. */
    fun write(source: SlideshowSource, slides: SourceSlides)
}

/** What's kept of one source's slideshow. */
data class SourceSlides(
    /** Its pictures, the newest first: the order they show in. */
    val slides: List<Slide> = emptyList(),
    /** The one the wallpaper shows; null for the first. */
    val current: PictureId? = null,
    /** When its pictures were last fetched, in ms since 1970 by the device's clock; 0 for never. */
    val fetched: Long = 0,
    /** What its listing has that isn't for a slideshow ([Download.Unfit]): not fetched again. */
    val unfit: Set<String> = emptySet(),
)

/** A picture of the library a source gave, by [the key][WebPicture.key] the source lists it under. */
data class Slide(val key: String, val picture: PictureId)
