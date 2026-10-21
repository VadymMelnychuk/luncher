package com.luncher.launcher.slideshow

import android.content.Context
import com.luncher.domain.pictures.PictureId
import com.luncher.domain.slideshow.Slide
import com.luncher.domain.slideshow.SlideshowLibrary
import com.luncher.domain.slideshow.SlideshowSource
import com.luncher.domain.slideshow.SourceSlides
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * [SlideshowLibrary] in SharedPreferences ([FILE]): each source's slideshow as one JSON string,
 * under the key [key] assigns it, `{"slides": [{"key", "picture"}, …], "current", "fetched",
 * "unfit": […]}`. One that can't be read is an empty slideshow, fetched again; its pictures stay
 * in the library, the user's to delete. Read from storage once per process, then kept; a write
 * stores in the background (`apply`).
 */
class PreferencesSlideshowLibrary(context: Context) : SlideshowLibrary {

    private val preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val kept = HashMap<SlideshowSource, SourceSlides>()

    override fun read(source: SlideshowSource): SourceSlides =
        kept.getOrPut(source) { key(source)?.let { preferences.getString(it, null) }?.let(::decode) ?: SourceSlides() }

    override fun write(source: SlideshowSource, slides: SourceSlides) {
        val key = key(source) ?: return
        if (kept[source] == slides) return
        kept[source] = slides
        preferences.edit().putString(key, encode(slides)).apply()
    }

    private fun encode(slides: SourceSlides): String =
        JSONObject()
            .put("slides", JSONArray().apply { slides.slides.forEach { put(JSONObject().put("key", it.key).put("picture", it.picture.value)) } })
            .put("current", slides.current?.value)
            .put("fetched", slides.fetched)
            .put("unfit", JSONArray(slides.unfit.toList()))
            .toString()

    private fun decode(text: String): SourceSlides? =
        try {
            val json = JSONObject(text)
            val slides = json.getJSONArray("slides")
            val unfit = json.optJSONArray("unfit") ?: JSONArray()
            SourceSlides(
                slides = (0 until slides.length()).map { slides.getJSONObject(it) }.map { Slide(it.getString("key"), PictureId(it.getString("picture"))) },
                current = if (json.isNull("current")) null else PictureId(json.getString("current")),
                fetched = json.optLong("fetched"),
                unfit = (0 until unfit.length()).mapTo(HashSet()) { unfit.getString(it) },
            )
        } catch (e: JSONException) {
            null
        }

    /** Where each source's slideshow is kept; none for the background's own picture. A key, once released, is never given to another source. */
    private fun key(source: SlideshowSource): String? = when (source) {
        SlideshowSource.PICTURE -> null
        SlideshowSource.BING -> "bing"
        SlideshowSource.NASA_EARTH -> "nasa_earth"
        SlideshowSource.WIKIMEDIA -> "wikimedia"
        SlideshowSource.NASA_APOD -> "nasa_apod"
        SlideshowSource.TMDB -> "tmdb"
    }

    private companion object {
        const val FILE = "slideshow"
    }
}
