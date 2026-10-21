package com.luncher.domain.slideshow

import com.luncher.domain.appearance.AppearanceSettings
import com.luncher.domain.appearance.Background
import com.luncher.domain.clock.Clock
import com.luncher.domain.jobs.Cancellable
import com.luncher.domain.pictures.ImportResult
import com.luncher.domain.pictures.Picture
import com.luncher.domain.pictures.PictureId
import com.luncher.domain.pictures.PictureImport
import com.luncher.domain.pictures.Pictures

/**
 * The wallpaper's slideshow: pictures fetched from the internet, from the background's
 * [source][com.luncher.domain.appearance.Background.source], each showing for its
 * [interval][com.luncher.domain.appearance.Background.interval] in turn. One per process (AppGraph
 * makes it), so the slide shown and a fetch under way outlive a screen; the home screen watches it
 * ([Slides]) while it shows, and only then does it do anything: it follows the [clock] from the
 * first listener to the last, and stops a fetch when the last goes, so a hidden home screen costs
 * nothing, and nothing runs in the background (no service, no alarm).
 *
 * At each minute while it's watched, and when watching starts: once the interval has passed since
 * the slide changed, the next one shows; and once a source's pictures were fetched more than
 * [REFRESH] ago, or none of them are in the library, they're fetched again, at most once in
 * [RETRY] for a source: its listing ([WebSources]), then up to [MOST] of its pictures not fetched
 * before, one at a time, each downloaded ([WebPictures]) and added to the picture library
 * ([PictureImport]), where it shows among the user's own. Then the newest show first, and only the
 * newest [MOST] of a source are kept: the older ones are deleted from the library, unless the
 * background's own picture is one, which the user chose. Its pictures are named in [library].
 *
 * Every failure is silent, as nobody asked for this fetch: no network leaves the pictures fetched
 * before, which go on showing in turn; a source with none shows the background's own picture.
 * Used on the main thread only.
 */
class Slideshow<Source>(
    private val library: SlideshowLibrary,
    private val web: WebPictures<Source>,
    private val import: PictureImport<Source>,
    private val pictures: Pictures,
    private val settings: AppearanceSettings,
    private val clock: Clock,
    private val sources: WebSources,
) : Slides {

    /** One fetch of a source's pictures: what it's doing, what's left, and the file it downloaded, until added. */
    private inner class Fetch(val source: SlideshowSource) {
        private var running: Cancellable? = null
        private var steps = 0
        var file: Source? = null
        val queue = ArrayDeque<WebPicture>()

        /** The listing's keys, the newest first: the order the slides then show in. */
        var listed: List<String> = emptyList()

        /** Whether a picture was added: then the newest shows once the fetch is over. */
        var added = false

        /** Starts the next step, which [start] makes: kept to be cancelled, unless it's over already (answered at once). */
        fun step(start: () -> Cancellable) {
            val step = ++steps
            val running = start()
            if (step == steps) this.running = running
        }

        fun cancel() {
            running?.cancel()
            file?.let(web::discard)
        }
    }

    private val listeners = mutableListOf<() -> Unit>()
    private var fetch: Fetch? = null

    /** When each source's pictures were last asked for, by the [clock], in this process. */
    private val tried = HashMap<SlideshowSource, Long>()

    /** When the slide last changed, or watching first started; null before. */
    private var changed: Long? = null

    private val tick: () -> Unit = { check() }

    override fun slide(background: Background): PictureId? {
        if (background.source == SlideshowSource.PICTURE) return null
        val kept = library.read(background.source)
        val slides = inLibrary(kept.slides)
        return slides.firstOrNull { it.picture == kept.current }?.picture ?: slides.firstOrNull()?.picture
    }

    override fun addListener(listener: () -> Unit) {
        val first = listeners.isEmpty()
        listeners += listener
        if (!first) return
        clock.addListener(tick)
        check()
    }

    override fun removeListener(listener: () -> Unit) {
        if (!listeners.remove(listener) || listeners.isNotEmpty()) return
        clock.removeListener(tick)
        stopFetching()
    }

    /** Shows the next slide if it's time, and fetches if that's due. */
    private fun check() {
        val background = settings.read().background
        val source = background.source
        if (fetch?.source != source) stopFetching()
        if (source == SlideshowSource.PICTURE) return
        val now = clock.read().epochMillis
        val last = changed
        // Minutes tick on the minute, so a slide may be due up to a minute after it started.
        if (last == null || now < last) {
            changed = now
        } else if (now - last >= background.interval.millis - HALF_A_MINUTE) {
            changed = now
            next(source)
        }
        if (fetch == null && due(source, now)) fetch(source, now)
    }

    /** The slide after the one shown, around. */
    private fun next(source: SlideshowSource) {
        val kept = library.read(source)
        val slides = inLibrary(kept.slides)
        if (slides.size < 2) return
        val shown = slides.indexOfFirst { it.picture == kept.current }.coerceAtLeast(0)
        library.write(source, kept.copy(current = slides[(shown + 1) % slides.size].picture))
        changed()
    }

    private fun due(source: SlideshowSource, now: Long): Boolean {
        val kept = library.read(source)
        val stale = now - kept.fetched >= REFRESH || now < kept.fetched || inLibrary(kept.slides).isEmpty()
        val tried = tried[source]
        return stale && (tried == null || now - tried >= RETRY || now < tried)
    }

    private fun fetch(source: SlideshowSource, now: Long) {
        val url = sources.listing(source) ?: return
        tried[source] = now
        val fetch = Fetch(source).also { this.fetch = it }
        fetch.step { web.read(url) { text -> if (this.fetch === fetch) listed(fetch, text) } }
    }

    /** The source's listing came, as [text]: the pictures it has that weren't fetched, the newest first. */
    private fun listed(fetch: Fetch, text: String?) {
        val listed = text?.let { sources.pictures(fetch.source, it) } ?: return end(fetch, succeeded = false)
        val kept = library.read(fetch.source)
        // A slide the user deleted from the library is fetched again, if it's still listed.
        val slides = inLibrary(kept.slides)
        val keys = listed.mapTo(HashSet()) { it.key }
        val unfit = kept.unfit.filterTo(HashSet()) { it in keys }
        library.write(fetch.source, kept.copy(slides = slides, unfit = unfit))
        val known = slides.mapTo(HashSet()) { it.key } + unfit
        fetch.listed = listed.map { it.key }
        fetch.queue.addAll(listed.filter { it.key !in known }.take(MOST))
        downloadNext(fetch)
    }

    private fun downloadNext(fetch: Fetch) {
        val picture = fetch.queue.removeFirstOrNull() ?: return end(fetch, succeeded = true)
        fetch.step {
            web.download(picture) { download ->
                if (this.fetch !== fetch) return@download
                when (download) {
                    is Download.Done -> add(fetch, picture, download.source)
                    Download.Unfit -> unfit(fetch, picture)
                    Download.Failed -> end(fetch, succeeded = false)
                }
            }
        }
    }

    private fun add(fetch: Fetch, picture: WebPicture, file: Source) {
        fetch.file = file
        fetch.step {
            import.add(file) { result ->
                web.discard(file)
                fetch.file = null
                if (this.fetch !== fetch) return@add
                when (result) {
                    is ImportResult.Added -> added(fetch, picture, result.picture)
                    is ImportResult.AlreadyThere -> added(fetch, picture, result.picture)
                    is ImportResult.SameFile -> added(fetch, picture, result.picture)
                    // Another picture of its name, the user's say: this one isn't added, not now, nor later.
                    // Not a picture includes one cut short or damaged, which the library refuses before decoding it.
                    // A folder never comes from a download; were it one, it's no picture either.
                    is ImportResult.NameTaken, ImportResult.NotAPicture, is ImportResult.UnsupportedFormat, is ImportResult.TooLarge,
                    ImportResult.Folder -> unfit(fetch, picture)
                    ImportResult.Unreadable, ImportResult.NoSpace -> end(fetch, succeeded = false)
                }
            }
        }
    }

    /** [picture] is in the library as [added]: a slide of the source's, the first to show if the source had none. */
    private fun added(fetch: Fetch, picture: WebPicture, added: Picture) {
        val kept = library.read(fetch.source)
        val before = inLibrary(kept.slides)
        val slides = listOf(Slide(picture.key, added.id)) + kept.slides.filter { it.picture != added.id && it.key != picture.key }
        fetch.added = true
        if (before.isEmpty()) {
            library.write(fetch.source, kept.copy(slides = slides, current = added.id))
            changed = clock.read().epochMillis
            changed()
        } else {
            library.write(fetch.source, kept.copy(slides = slides))
        }
        downloadNext(fetch)
    }

    private fun unfit(fetch: Fetch, picture: WebPicture) {
        val kept = library.read(fetch.source)
        library.write(fetch.source, kept.copy(unfit = kept.unfit + picture.key))
        downloadNext(fetch)
    }

    /**
     * The fetch is over: [succeeded] if the listing came and each of its pictures was either added
     * or isn't for a slideshow. Then the slides are in the listing's order, the newest first, the
     * newest [MOST] kept, the others deleted; with new ones, the newest shows.
     */
    private fun end(fetch: Fetch, succeeded: Boolean) {
        this.fetch = null
        if (!succeeded) return
        val source = fetch.source
        val kept = library.read(source)
        val order = fetch.listed.withIndex().associate { (index, key) -> key to index }
        // Stable: those the listing doesn't name any more keep their order, after the others.
        val sorted = kept.slides.sortedBy { order[it.key] ?: Int.MAX_VALUE }
        val slides = sorted.take(MOST)
        val shown = kept.current.takeIf { current -> slides.any { it.picture == current } }
        val current = if (fetch.added) slides.firstOrNull()?.picture else shown
        library.write(source, kept.copy(slides = slides, current = current, fetched = clock.read().epochMillis))
        delete(sorted.drop(MOST).mapTo(HashSet()) { it.picture }, source)
        if (current != kept.current) {
            changed = clock.read().epochMillis
            changed()
        }
    }

    /**
     * Deletes the pictures [dropped] from [source]'s slides, but the background's own and another
     * source's, which stay in the library.
     */
    private fun delete(dropped: Set<PictureId>, source: SlideshowSource) {
        if (dropped.isEmpty()) return
        val kept = HashSet<PictureId>()
        settings.read().background.picture?.let(kept::add)
        for (other in SlideshowSource.entries) {
            if (other != source && other != SlideshowSource.PICTURE) library.read(other).slides.mapTo(kept) { it.picture }
        }
        val deleted = dropped - kept
        if (deleted.isNotEmpty()) pictures.delete(deleted)
    }

    private fun stopFetching() {
        fetch?.cancel()
        fetch = null
    }

    /** [slides] whose pictures are in the library still: the user may have deleted one. */
    private fun inLibrary(slides: List<Slide>): List<Slide> {
        if (slides.isEmpty()) return slides
        val ids = pictures.read().mapTo(HashSet()) { it.id }
        return slides.filter { it.picture in ids }
    }

    private fun changed() = listeners.toList().forEach { it() }

    companion object {
        /** The most pictures of a source fetched at once, and kept. */
        const val MOST = 8

        /** How old a source's pictures may be before they're fetched again: 12 hours. */
        const val REFRESH = 12 * 60 * 60_000L

        /** How long after a fetch that failed it's tried again, while the home screen shows: an hour. */
        const val RETRY = 60 * 60_000L

        private const val HALF_A_MINUTE = 30_000L
    }
}
