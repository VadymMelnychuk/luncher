package com.luncher.launcher.home

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import com.luncher.domain.appearance.Background
import com.luncher.domain.slideshow.Slides
import com.luncher.domain.wallpaper.DrawingCause
import com.luncher.domain.wallpaper.Wallpaper
import com.luncher.launcher.ui.WorkingDialog
import com.luncher.launcher.ui.color
import com.luncher.launcher.ui.screenSize
import com.luncher.launcher.ui.R as UiR

/**
 * The home screen's wallpaper: the process's [Wallpaper], at the screen's size, as [activity]'s
 * window background, followed from [start] to [stop]. The picture once it's ready, the home
 * screen's plain color without one. While it's drawn (saved in the background editor; again, as
 * the screen changed size or Luncher was updated), the plain color, and a [WorkingDialog] over the home screen says why, taking every key
 * until the picture shows: a wait the user is told of, not a screen that seems stuck.
 *
 * A start reads a wallpaper kept on the main thread (in [Wallpaper.show]), so the first frame has
 * it, as the tiles' images. A drawing goes on when the home screen stops, and shows when it's back.
 *
 * With a slideshow, it watches [slides] from [start] to [stop], which only then changes slides and
 * fetches pictures, and asks for the background's wallpaper again at each change: the next slide
 * comes in silently ([Wallpaper]), and fades in over the one before for [FADE_MS], no window over
 * it. Both images are drawn only while it fades; then the one before is let go, so only one is
 * kept.
 */
internal class HomeWallpaper(private val activity: Activity, private val wallpaper: Wallpaper<Bitmap>, private val slides: Slides) {

    private val plain = ColorDrawable(activity.color(UiR.color.background))

    /** Made the first time the wallpaper is drawn while the home screen shows. */
    private var working: WorkingDialog? = null

    /** The image the window shows, null for the plain color, once [applied]. */
    private var shown: Bitmap? = null
    private var applied = false

    /** The background last asked for, which the slideshow's next slide is asked for again with. */
    private var background: Background? = null

    /** The image fading in over the one before, while it does. */
    private var fading: Drawable? = null
    private val faded = Runnable(::endFade)

    private val follow: () -> Unit = { apply(wallpaper.state) }
    private val nextSlide: () -> Unit = { background?.let(::show) }

    fun start() {
        wallpaper.addListener(follow)
        apply(wallpaper.state)
        slides.addListener(nextSlide)
    }

    fun stop() {
        slides.removeListener(nextSlide)
        wallpaper.removeListener(follow)
        working?.dismiss()
        endFade()
    }

    /** Asks for [background]'s wallpaper, which shows as [Wallpaper] tells, between [start] and [stop]. */
    fun show(background: Background) {
        this.background = background
        val screen = activity.screenSize()
        wallpaper.show(background, screen.width, screen.height)
    }

    private fun apply(state: Wallpaper.State<Bitmap>) {
        when (state) {
            is Wallpaper.State.Ready -> {
                setImage(state.image)
                working?.close()
            }
            Wallpaper.State.None -> {
                setImage(null)
                working?.close()
            }
            is Wallpaper.State.Drawing -> {
                setImage(null)
                val working = working ?: WorkingDialog(activity).also { working = it }
                working.show(activity.getString(message(state.cause)))
            }
        }
    }

    private fun message(cause: DrawingCause): Int = when (cause) {
        DrawingCause.SAVING -> R.string.home_wallpaper_saving
        DrawingCause.SCREEN_CHANGED -> R.string.home_wallpaper_screen_changed
        DrawingCause.OUTDATED -> R.string.home_wallpaper_outdated
    }

    /** Shows [image], or the plain color: faded in over the image before, if there's one, else at once. */
    private fun setImage(image: Bitmap?) {
        if (applied && image === shown) return
        val before = shown
        applied = true
        shown = image
        val window = activity.window
        window.decorView.removeCallbacks(faded)
        fading = null
        if (image == null) return window.setBackgroundDrawable(plain)
        val drawable = BitmapDrawable(activity.resources, image)
        if (before == null) return window.setBackgroundDrawable(drawable)
        val fade = TransitionDrawable(arrayOf(BitmapDrawable(activity.resources, before), drawable))
        fading = drawable
        window.setBackgroundDrawable(fade)
        fade.startTransition(FADE_MS)
        window.decorView.postDelayed(faded, FADE_MS.toLong())
    }

    /** The fade over: the image alone, the one before let go. */
    private fun endFade() {
        val drawable = fading ?: return
        fading = null
        activity.window.decorView.removeCallbacks(faded)
        activity.window.setBackgroundDrawable(drawable)
    }

    companion object {
        /** How long a slide fades in over the one before, in ms. */
        const val FADE_MS = 400
    }
}
