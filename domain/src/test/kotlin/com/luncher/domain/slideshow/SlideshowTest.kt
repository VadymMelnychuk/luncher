package com.luncher.domain.slideshow

import com.luncher.domain.appearance.Appearance
import com.luncher.domain.appearance.Background
import com.luncher.domain.appearance.FakeAppearanceSettings
import com.luncher.domain.clock.ClockReading
import com.luncher.domain.clock.FakeClock
import com.luncher.domain.pictures.FakePictures
import com.luncher.domain.pictures.ImportResult
import com.luncher.domain.pictures.Picture
import com.luncher.domain.pictures.PictureId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowTest {

    private val start = FakeClock.at(2026, 10, 21, 20, 0)
    private val clock = FakeClock(start)
    private val library = FakeSlideshowLibrary()
    private val web = FakeWebPictures()
    private val own = Picture(PictureId("own"), "earth.jpg", 1920, 1080, 1, 0)
    private val pictures = FakePictures<String>(own)
    private val bing = Background(picture = own.id, source = SlideshowSource.BING, interval = SlideshowInterval.FIVE)
    private val settings = FakeAppearanceSettings(Appearance(background = bing))
    private val slideshow = Slideshow(library, web, pictures, pictures, settings, clock, WebSources())

    /** How often the listener was told the slide may have changed. */
    private var told = 0
    private val listener: () -> Unit = { told++ }

    /** Bing's listing of [days], the newest first, each a picture named after its day. */
    private fun listing(vararg days: Int): String =
        days.joinToString(",", """{"images": [""", "]}") { """{"url": "/th?id=$it.jpg", "urlbase": "/th?id=$it", "startdate": "202610${"%02d".format(it)}", "title": "Day $it"}""" }

    private fun url(day: Int) = "https://www.bing.com/th?id=$day.jpg"

    private fun id(day: Int) = PictureId("day-$day")

    /** The library adds each of [days]' downloads as a picture of its own. */
    private fun addable(vararg days: Int) {
        for (day in days) pictures.results[url(day)] = ImportResult.Added(Picture(id(day), "Bing $day", 1920, 1080, 1, 0))
    }

    private fun minutesLater(minutes: Int) {
        val now = clock.read()
        clock.set(ClockReading(now.epochMillis + minutes * 60_000L, now.timeZone, now.uses24Hour))
    }

    /** The listing answered with [days], and each of them downloaded and added, in turn. */
    private fun fetch(vararg days: Int) {
        addable(*days)
        web.answerRead(listing(*days))
        while (web.downloading != null) web.answerDownload()
    }

    private fun shown() = slideshow.slide(settings.read().background)

    private fun kept() = library.read(SlideshowSource.BING)

    @Test
    fun `without a slideshow shows the background's own picture, and fetches nothing`() {
        settings.appearance = Appearance(background = Background(own.id))

        slideshow.addListener(listener)
        minutesLater(60)

        assertNull(shown())
        assertEquals(emptyList<String>(), web.asked)
    }

    @Test
    fun `fetches the source's pictures once watched, one at a time, the newest first, and shows the first at once`() {
        slideshow.addListener(listener)
        assertEquals(listOf(WebSources().listing(SlideshowSource.BING)), web.asked)
        assertNull("nothing fetched yet: the background's picture", shown())

        addable(21, 20)
        web.answerRead(listing(21, 20))
        assertEquals(url(21), web.downloading?.url)
        assertEquals("one at a time", 1, web.waiting.size)
        web.answerDownload()
        assertEquals(id(21), shown())
        assertEquals(1, told)
        assertEquals(listOf(url(21)), web.discarded)

        web.answerDownload()
        assertEquals(listOf(Slide("/th?id=21", id(21)), Slide("/th?id=20", id(20))), kept().slides)
        assertEquals(clock.read().epochMillis, kept().fetched)
        assertEquals(id(21), shown())
        assertEquals("the newest stays", 1, told)
    }

    @Test
    fun `shows the next slide once its interval passed, around, while watched`() {
        slideshow.addListener(listener)
        fetch(21, 20, 19)
        told = 0

        minutesLater(4)
        assertEquals(id(21), shown())
        minutesLater(1)
        assertEquals(id(20), shown())
        minutesLater(5)
        assertEquals(id(19), shown())
        minutesLater(5)
        assertEquals(id(21), shown())
        assertEquals(3, told)
    }

    @Test
    fun `a slide changes only while watched, and the next one shows as watching starts again, once it's due`() {
        slideshow.addListener(listener)
        fetch(21, 20)
        slideshow.removeListener(listener)
        assertEquals(0, clock.listenerCount)

        minutesLater(120)
        assertEquals(id(21), shown())

        slideshow.addListener(listener)
        assertEquals(id(20), shown())
    }

    @Test
    fun `the interval is the background's`() {
        settings.appearance = Appearance(background = bing.copy(interval = SlideshowInterval.THIRTY))
        slideshow.addListener(listener)
        fetch(21, 20)

        minutesLater(29)
        assertEquals(id(21), shown())
        minutesLater(1)
        assertEquals(id(20), shown())
    }

    @Test
    fun `offline, shows the background's own picture, and tries again an hour later`() {
        slideshow.addListener(listener)
        web.answerRead(null)
        assertNull(shown())

        minutesLater(59)
        assertEquals(1, web.asked.size)
        minutesLater(1)
        assertEquals(2, web.asked.size)
    }

    @Test
    fun `offline, the pictures fetched before go on showing in turn`() {
        slideshow.addListener(listener)
        fetch(21, 20)
        minutesLater(12 * 60)
        assertEquals("fetched again after 12 hours", 2, web.asked.count { it.startsWith("https://www.bing.com/HP") })

        web.answerRead(null)
        val shown = shown()
        minutesLater(5)

        assertTrue(shown() != shown && shown() in setOf(id(21), id(20)))
    }

    @Test
    fun `a download that gets no answer ends the fetch, which is tried again, the picture added kept`() {
        slideshow.addListener(listener)
        addable(21, 20)
        web.answerRead(listing(21, 20))
        web.answerDownload()
        web.answerDownload { Download.Failed }

        assertEquals(listOf(id(21)), kept().slides.map { it.picture })
        assertEquals("not fetched", 0L, kept().fetched)

        minutesLater(60)
        web.answerRead(listing(21, 20))
        assertEquals("only the one missing", url(20), web.downloading?.url)
    }

    @Test
    fun `a picture that isn't for a slideshow is skipped, and not asked for again`() {
        slideshow.addListener(listener)
        addable(21, 20)
        web.answerRead(listing(21, 20))
        web.answerDownload { Download.Unfit }
        web.answerDownload()

        assertEquals(listOf(id(20)), kept().slides.map { it.picture })
        assertEquals(setOf("/th?id=21"), kept().unfit)

        minutesLater(12 * 60)
        web.answerRead(listing(21, 20))
        assertNull(web.downloading)
    }

    @Test
    fun `a picture the library refuses is skipped too, one it has already is a slide`() {
        slideshow.addListener(listener)
        pictures.results[url(21)] = ImportResult.NameTaken(own)
        pictures.results[url(20)] = ImportResult.AlreadyThere(own)
        web.answerRead(listing(21, 20))
        web.answerDownload()
        web.answerDownload()

        assertEquals(listOf(Slide("/th?id=20", own.id)), kept().slides)
        assertEquals(setOf("/th?id=21"), kept().unfit)
        assertEquals(listOf(url(21), url(20)), web.discarded)
    }

    @Test
    fun `fetches again after 12 hours only what it hasn't, then keeps the newest 8 and deletes the others`() {
        slideshow.addListener(listener)
        fetch(8, 7, 6, 5, 4, 3, 2, 1)

        minutesLater(11 * 60 + 59)
        assertEquals(1, web.asked.count { it.startsWith("https://www.bing.com/HP") })
        minutesLater(1)
        fetch(10, 9, 8, 7, 6, 5, 4, 3)

        assertEquals(listOf(10, 9, 8, 7, 6, 5, 4, 3).map(::id), kept().slides.map { it.picture })
        assertEquals(id(10), shown())
        assertEquals(setOf(own.id) + (3..10).map(::id), pictures.pictures.mapTo(HashSet()) { it.id })
        assertEquals("downloaded: 8, then the 2 new", 10, web.asked.count { !it.startsWith("https://www.bing.com/HP") })
    }

    @Test
    fun `an old slide the user chose as the background's picture stays in the library`() {
        slideshow.addListener(listener)
        fetch(8, 7, 6, 5, 4, 3, 2, 1)
        settings.appearance = Appearance(background = bing.copy(picture = id(1)))

        minutesLater(12 * 60)
        fetch(9, 8, 7, 6, 5, 4, 3, 2)

        assertTrue(pictures.pictures.any { it.id == id(1) })
        assertTrue(kept().slides.none { it.picture == id(1) })
    }

    @Test
    fun `a slide the user deleted from the library isn't shown, and is fetched again`() {
        slideshow.addListener(listener)
        fetch(21, 20)
        pictures.delete(setOf(id(21)))

        assertEquals(id(20), shown())

        minutesLater(12 * 60)
        web.answerRead(listing(21, 20))
        assertEquals(url(21), web.downloading?.url)
    }

    @Test
    fun `with every slide deleted, shows the background's picture, and fetches at once`() {
        slideshow.addListener(listener)
        fetch(21)
        pictures.delete(setOf(id(21)))
        slideshow.removeListener(listener)
        minutesLater(60)

        slideshow.addListener(listener)

        assertNull(shown())
        assertEquals(2, web.asked.count { it.startsWith("https://www.bing.com/HP") })
    }

    @Test
    fun `stops fetching when the last listener goes, nothing left`() {
        slideshow.addListener(listener)
        addable(21, 20)
        pictures.answerLater = true
        web.answerRead(listing(21, 20))
        web.answerDownload()
        assertTrue(pictures.adding)

        slideshow.removeListener(listener)

        assertEquals(listOf(url(21)), pictures.cancelled)
        assertEquals("its download deleted", listOf(url(21)), web.discarded)
        assertEquals(0, web.waiting.size)
    }

    @Test
    fun `a download under way is cancelled when watching stops`() {
        slideshow.addListener(listener)
        web.answerRead(listing(21))
        slideshow.removeListener(listener)

        assertEquals(1, web.cancelled.size)
    }

    @Test
    fun `another source's slideshow has slides of its own, fetched when it's chosen`() {
        slideshow.addListener(listener)
        fetch(21)

        settings.appearance = Appearance(background = bing.copy(source = SlideshowSource.NASA_EARTH))
        assertNull(shown())
        minutesLater(1)

        assertEquals(WebSources().listing(SlideshowSource.NASA_EARTH), web.asked.last())
    }

    @Test
    fun `a fetch of a source no longer chosen stops`() {
        slideshow.addListener(listener)
        settings.appearance = Appearance(background = bing.copy(source = SlideshowSource.PICTURE))
        minutesLater(1)

        assertEquals(1, web.cancelled.size)
    }

    @Test
    fun `the slide shown is kept, for the next process`() {
        slideshow.addListener(listener)
        fetch(21, 20)
        minutesLater(5)

        val next = Slideshow(library, web, pictures, pictures, settings, clock, WebSources())

        assertEquals(id(20), next.slide(bing))
    }
}
