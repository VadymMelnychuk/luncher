package com.luncher.domain.appearance

import com.luncher.domain.pictures.Blur
import com.luncher.domain.pictures.Fit
import com.luncher.domain.pictures.Framing
import com.luncher.domain.pictures.PictureId
import com.luncher.domain.pictures.PicturePosition
import com.luncher.domain.pictures.PictureStyle
import com.luncher.domain.slideshow.SlideshowInterval
import com.luncher.domain.slideshow.SlideshowSource

/**
 * The home screen's background: a picture of the library behind everything, or none (the home
 * screen's plain color, the default), and how it's drawn; or a slideshow of pictures from the
 * internet ([source]), each drawn the same way, the picture showing until the first of them is
 * fetched. A new value draws the wallpaper again, and nothing else.
 */
data class Background(
    /** The picture; null for none, as when it was deleted. */
    val picture: PictureId? = null,
    val fit: Fit = Fit.FILL,
    /** Only with [Fit.FILL]. */
    val position: PicturePosition = PicturePosition.CENTER,
    /** How much darker the picture is drawn, in percent, so the tiles stand out. */
    val dim: Int = 0,
    val blur: Blur = Blur.OFF,
    /** Where the wallpaper's pictures come from: [picture], or a site's ([com.luncher.domain.slideshow.Slideshow]). Not an option of the catalog: which sources there are depends on the build. */
    val source: SlideshowSource = SlideshowSource.PICTURE,
    /** How long each picture of a slideshow shows. Not an option of the catalog either: it changes no drawing. */
    val interval: SlideshowInterval = SlideshowInterval.THIRTY,
) {
    /** How the picture is drawn into the screen. */
    val style: PictureStyle get() = PictureStyle(Framing(fit, position), dim, blur)

    /** Whether a picture may show: the background's own, or a slideshow's. */
    val showsPictures: Boolean get() = picture != null || source != SlideshowSource.PICTURE

    /**
     * This background with only values that change anything in [context]: a position that doesn't
     * move the picture, as the top of a picture cut at the sides, is the middle, which draws the
     * same, so the next change starts there. A fit is kept: its other value draws otherwise as soon
     * as a picture of another shape is chosen.
     */
    fun offeredIn(context: OptionContext): Background {
        val positions = AppearanceOption.BackgroundPosition.values(context)
        return if (position in positions) this else copy(position = AppearanceOption.BackgroundPosition.default)
    }

    /**
     * This background with [picture] chosen in it: [stored] if that's its picture, so the stored
     * one comes back as it's saved; else the position, the dim and the blur as by default, so
     * another picture shows as it is, and its options are set for it. The fit is kept, as in
     * [offeredIn]. The picture it has already changes nothing. Either way the [source] and the
     * [interval] are this one's: they're not about the picture, and choosing the one that shows
     * until a site's first pictures come doesn't undo the slideshow chosen.
     */
    fun choosing(picture: PictureId?, stored: Background): Background = when (picture) {
        this.picture -> this
        stored.picture -> stored.copy(source = source, interval = interval)
        else -> Background(picture, fit, source = source, interval = interval)
    }
}
