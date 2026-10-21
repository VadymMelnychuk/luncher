package com.luncher.domain.slideshow

import com.luncher.domain.appearance.Background
import com.luncher.domain.pictures.PictureId

/** [Slides] the test sets: [slides] is the slide each source shows, none by default; [show] changes one and tells the listeners. */
class FakeSlides(vararg slides: Pair<SlideshowSource, PictureId>) : Slides {

    val slides: MutableMap<SlideshowSource, PictureId> = mutableMapOf(*slides)
    private val listeners = mutableListOf<() -> Unit>()

    /** How many listeners are registered: 0 while the home screen is hidden. */
    val listenerCount: Int get() = listeners.size

    override fun slide(background: Background): PictureId? = slides[background.source]

    override fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    override fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** [source] shows [picture] now, and the listeners are told. */
    fun show(source: SlideshowSource, picture: PictureId) {
        slides[source] = picture
        listeners.toList().forEach { it() }
    }
}
