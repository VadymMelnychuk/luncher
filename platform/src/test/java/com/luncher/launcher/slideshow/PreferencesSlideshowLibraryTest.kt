package com.luncher.launcher.slideshow

import android.content.Context
import com.luncher.domain.pictures.PictureId
import com.luncher.domain.slideshow.Slide
import com.luncher.domain.slideshow.SlideshowSource
import com.luncher.domain.slideshow.SourceSlides
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PreferencesSlideshowLibraryTest {

    private val context = RuntimeEnvironment.getApplication()

    // The file and format, written out: a TV keeps them across updates.
    private val preferences = context.getSharedPreferences("slideshow", Context.MODE_PRIVATE)

    private val bing = SourceSlides(
        slides = listOf(Slide("/th?id=A", PictureId("aaaa")), Slide("/th?id=B", PictureId("bbbb"))),
        current = PictureId("bbbb"),
        fetched = 1_790_000_000_000,
        unfit = setOf("/th?id=C"),
    )
    private val stored = """{"slides":[{"key":"\/th?id=A","picture":"aaaa"},{"key":"\/th?id=B","picture":"bbbb"}],"current":"bbbb","fetched":1790000000000,"unfit":["\/th?id=C"]}"""

    @Test
    fun `reads the stored format`() {
        preferences.edit().putString("bing", stored).commit()

        assertEquals(bing, PreferencesSlideshowLibrary(context).read(SlideshowSource.BING))
    }

    @Test
    fun `writes each source under its own key, and reads it back after the process restarts`() {
        PreferencesSlideshowLibrary(context).write(SlideshowSource.BING, bing)
        PreferencesSlideshowLibrary(context).write(SlideshowSource.NASA_EARTH, SourceSlides(fetched = 5))

        assertEquals(setOf("bing", "nasa_earth"), preferences.all.keys)
        assertEquals(bing, PreferencesSlideshowLibrary(context).read(SlideshowSource.BING))
        assertEquals(SourceSlides(fetched = 5), PreferencesSlideshowLibrary(context).read(SlideshowSource.NASA_EARTH))
    }

    @Test
    fun `a source never fetched, or kept in a form it can't read, has nothing`() {
        preferences.edit().putString("wikimedia", "{not json").commit()

        assertEquals(SourceSlides(), PreferencesSlideshowLibrary(context).read(SlideshowSource.WIKIMEDIA))
        assertEquals(SourceSlides(), PreferencesSlideshowLibrary(context).read(SlideshowSource.TMDB))
    }

    @Test
    fun `keeps nothing for the background's own picture`() {
        PreferencesSlideshowLibrary(context).write(SlideshowSource.PICTURE, bing)

        assertEquals(emptyMap<String, Any?>(), preferences.all)
    }
}
