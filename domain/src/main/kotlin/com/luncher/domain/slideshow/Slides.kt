package com.luncher.domain.slideshow

import com.luncher.domain.appearance.Background
import com.luncher.domain.pictures.PictureId

/**
 * The slide the wallpaper shows in place of the background's own picture, which changes while the
 * home screen shows: a state you can watch
 * (docs/ARCHITECTURE.md#background-work-and-changes-while-shown). [Slideshow] is the one for the
 * process; while it has listeners, it changes the slide every few minutes, and fetches new
 * pictures; without one, it does nothing.
 */
interface Slides {

    /**
     * The picture [background]'s wallpaper shows now: a slide of its [source][Background.source]
     * that's in the library; null for the background's own picture (no slideshow, or none of its
     * pictures fetched yet). Quick, for the main thread.
     */
    fun slide(background: Background): PictureId?

    /** Calls [listener] on the main thread whenever [slide] may answer otherwise. */
    fun addListener(listener: () -> Unit)

    fun removeListener(listener: () -> Unit)
}
