package com.luncher.domain.slideshow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sources' listings read into pictures, from their answers as recorded on 2026-10-21
 * (`src/test/resources/slideshow/`): Bing's, NASA EPIC's (3 of its 13 entries) and Wikimedia's
 * whole. NASA APOD answered placeholders that day (`apod-placeholders.json`), and TMDB needs a key,
 * so `apod.json` and `tmdb.json` are written after their documented format. No test asks the
 * network.
 */
class WebSourcesTest {

    private val sources = WebSources()

    private fun answer(name: String): String = checkNotNull(javaClass.getResource("/slideshow/$name")) { name }.readText()

    @Test
    fun `offers TMDB only with a key, the background's picture first`() {
        assertEquals(
            listOf(SlideshowSource.PICTURE, SlideshowSource.BING, SlideshowSource.NASA_EARTH, SlideshowSource.WIKIMEDIA, SlideshowSource.NASA_APOD),
            WebSources("").offered,
        )
        assertEquals(SlideshowSource.entries, WebSources("key").offered)
        assertNull(WebSources("").listing(SlideshowSource.TMDB))
        assertEquals("https://api.themoviedb.org/3/trending/movie/week?api_key=key", WebSources("key").listing(SlideshowSource.TMDB))
        assertNull(sources.listing(SlideshowSource.PICTURE))
    }

    @Test
    fun `every listing is asked for over https`() {
        for (source in WebSources("key").offered - SlideshowSource.PICTURE) {
            assertTrue(source.name, WebSources("key").listing(source)!!.startsWith("https://"))
        }
    }

    @Test
    fun `reads Bing's eight pictures of the day, the newest first, named by date and title`() {
        val pictures = sources.pictures(SlideshowSource.BING, answer("bing.json"))!!

        assertEquals(8, pictures.size)
        assertEquals(
            WebPicture(
                key = "/th?id=OHR.VietnamBridge_EN-US6907946681",
                url = "https://www.bing.com/th?id=OHR.VietnamBridge_EN-US6907946681_1920x1080.jpg&rf=LaDigue_1920x1080.jpg&pid=hp",
                name = "Bing 2026-10-21 Winging it over the river",
            ),
            pictures.first(),
        )
        assertEquals("Bing 2026-10-14 Spirals through time", pictures.last().name)
        assertEquals("Bing 2026-10-16 'Peak' into Colorado", pictures[5].name)
    }

    @Test
    fun `reads the Earth's pictures, the newest first, each to be drawn on black`() {
        val pictures = sources.pictures(SlideshowSource.NASA_EARTH, answer("epic.json"))!!

        assertEquals(
            listOf("epic_1b_20261018044041", "epic_1b_20261018025238", "epic_1b_20261018010436"),
            pictures.map { it.key },
        )
        assertEquals(
            WebPicture(
                key = "epic_1b_20261018010436",
                url = "https://epic.gsfc.nasa.gov/archive/natural/2026/10/18/jpg/epic_1b_20261018010436.jpg",
                name = "NASA Earth 2026-10-18 00-59",
                framing = WebFraming.ON_BLACK,
            ),
            pictures.last(),
        )
    }

    @Test
    fun `reads Wikimedia's wide pictures of the day only, the newest first, each 1920 px wide`() {
        val pictures = sources.pictures(SlideshowSource.WIKIMEDIA, answer("wikimedia.xml"))!!

        // Of the 10 days: 2 tall, 3 nearly square (at most 1.25 times as wide as tall) left out.
        assertEquals(
            listOf("2026-10-19", "2026-10-16", "2026-10-15", "2026-10-14", "2026-10-13"),
            pictures.map { it.name.split(" ")[1] },
        )
        assertEquals(
            WebPicture(
                key = "Desert_elephant_(Loxodonta_africana)_spraying_sand.jpg",
                url = "https://thumb.wikimedia.org/wikipedia/commons/thumb/5/50/Desert_elephant_%28Loxodonta_africana%29_spraying_sand.jpg/1920px-Desert_elephant_%28Loxodonta_africana%29_spraying_sand.jpg",
                name = "Wikimedia 2026-10-13 Desert elephant (Loxodonta africana) spraying sand",
            ),
            pictures.last(),
        )
    }

    @Test
    fun `asks Wikimedia for a picture less than 1920 px wide at 1280, and leaves out a narrower one`() {
        fun item(width: Int) = """<rss><channel><item><guid>https://commons.wikimedia.org/wiki/Special:FeedItem/potd/20261001000000/en</guid>
            <description>&lt;a href=&quot;/wiki/File:Wide.jpg&quot;&gt;&lt;img src=&quot;https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Wide.jpg/330px-Wide.jpg?utm_source=x&amp;amp;b=c&quot; data-file-width=&quot;$width&quot; data-file-height=&quot;${width / 2}&quot; /&gt;&lt;/a&gt;</description>
            </item></channel></rss>"""

        assertEquals(
            "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Wide.jpg/1280px-Wide.jpg",
            sources.pictures(SlideshowSource.WIKIMEDIA, item(1600))!!.single().url,
        )
        assertEquals(emptyList<WebPicture>(), sources.pictures(SlideshowSource.WIKIMEDIA, item(1200)))
    }

    @Test
    fun `reads NASA APOD's pictures, the newest first, leaving out videos and plain http`() {
        val pictures = sources.pictures(SlideshowSource.NASA_APOD, answer("apod.json"))!!

        assertEquals(
            listOf(
                WebPicture("https://apod.nasa.gov/apod/image/2403/Nebula_1024.jpg", "https://apod.nasa.gov/apod/image/2403/Nebula_1024.jpg", "NASA APOD 2024-03-02 The Lagoon Nebula"),
                WebPicture("https://apod.nasa.gov/apod/image/2106/Moon_1100.jpg", "https://apod.nasa.gov/apod/image/2106/Moon_1100.jpg", "NASA APOD 2021-06-14 Moonrise over the Sea"),
            ),
            pictures,
        )
    }

    @Test
    fun `reads NASA APOD's placeholders as one picture, the same eight times`() {
        // Too small for a slide: refused once downloaded (fitsTheSlideshow), and not asked for again.
        assertEquals(1, sources.pictures(SlideshowSource.NASA_APOD, answer("apod-placeholders.json"))!!.size)
    }

    @Test
    fun `reads TMDB's trending films' backdrops, in its order, leaving out a film without one`() {
        val pictures = WebSources("key").pictures(SlideshowSource.TMDB, answer("tmdb.json"))!!

        assertEquals(
            listOf(
                WebPicture("/kqjL17yufvn9OVLyXYpvtyrFfak.jpg", "https://image.tmdb.org/t/p/w1280/kqjL17yufvn9OVLyXYpvtyrFfak.jpg", "TMDB First Film"),
                WebPicture("/9xjZS2rlVxm8SFx8kPC3aIGCOYQ.jpg", "https://image.tmdb.org/t/p/w1280/9xjZS2rlVxm8SFx8kPC3aIGCOYQ.jpg", "TMDB Second The Sequel"),
            ),
            pictures,
        )
    }

    @Test
    fun `an answer that isn't the source's listing is none`() {
        for (source in SlideshowSource.entries) {
            assertNull(source.name, sources.pictures(source, "<html>Service unavailable</html>"))
            assertNull(source.name, sources.pictures(source, "{\"images\": "))
        }
        assertNull(sources.pictures(SlideshowSource.BING, "{\"error\": \"rate limited\"}"))
        assertNull(sources.pictures(SlideshowSource.NASA_APOD, "{\"error\": {\"code\": \"OVER_RATE_LIMIT\"}}"))
    }

    @Test
    fun `an entry that isn't one is left out, the others kept`() {
        val answer = """{"images": [{"url": "/th?id=A_1920x1080.jpg", "startdate": "20261021", "title": "A"}, {"title": "no url"}, 3, {"url": "/th?id=B.jpg", "startdate": "yesterday"}]}"""

        assertEquals(listOf("Bing 2026-10-21 A"), sources.pictures(SlideshowSource.BING, answer)!!.map { it.name })
    }

    @Test
    fun `a picture listed twice is fetched once`() {
        val answer = """{"images": [{"url": "/th?id=A.jpg", "urlbase": "/th?id=A", "startdate": "20261021"}, {"url": "/th?id=A.jpg", "urlbase": "/th?id=A", "startdate": "20261020"}]}"""

        assertEquals(1, sources.pictures(SlideshowSource.BING, answer)!!.size)
    }

    @Test
    fun `a picture fits the slideshow when it's wide and at least 1000 px wide`() {
        assertTrue(fitsTheSlideshow(1920, 1080))
        assertTrue(fitsTheSlideshow(1300, 1000))
        assertFalse(fitsTheSlideshow(1299, 1000))
        assertFalse(fitsTheSlideshow(1080, 1920))
        assertFalse(fitsTheSlideshow(999, 500))
    }

    @Test
    fun `a name keeps no character a file's name can't have, nor more than 80 characters`() {
        assertEquals("TMDB Mission Impossible", webPictureName("TMDB", "Mission: Impossible"))
        assertEquals("Bing 2026-10-21 A B C", webPictureName("Bing", "2026-10-21", "A/B\\C\n"))
        assertEquals(80, webPictureName("x".repeat(200)).length)
        // Not half of an emoji at the end.
        assertEquals(79, webPictureName("x".repeat(79) + "\uD83D\uDE00").length)
    }

    @Test
    fun `reads JSON's values, escapes and nesting`() {
        assertEquals(
            mapOf("a" to listOf(1.0, -2.5, 3e2), "b" to "é\"\n/é", "c" to true, "d" to false, "e" to null, "f" to emptyMap<String, Any?>(), "g" to emptyList<Any?>()),
            readJson("""{"a": [1, -2.5, 3e2], "b": "\u00e9\"\n\/é", "c": true, "d": false, "e": null, "f": {}, "g": []}"""),
        )
    }

    @Test
    fun `refuses what isn't JSON`() {
        for (text in listOf("", "{", "[1,]", "{\"a\" 1}", "\"open", "tru", "{} x", "{\"a\": 1,}", "\"\\x\"")) {
            try {
                readJson(text)
                throw AssertionError("read: $text")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
