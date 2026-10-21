package com.luncher.launcher.network

import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import com.luncher.domain.jobs.ManualJobs
import com.luncher.domain.slideshow.Download
import com.luncher.domain.slideshow.WebFraming
import com.luncher.domain.slideshow.WebPicture
import com.luncher.launcher.pictures.TestPictureFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The slideshow's downloads, from `file://` URLs of the test's own files: the same reading,
 * limits and checks as from a server, without the network. A call's thread is the test's own
 * here, so its answer is waiting on the main thread when it returns.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // to make and decode pictures
class HttpWebPicturesTest {

    private val context = RuntimeEnvironment.getApplication()
    private val jobs = ManualJobs()
    private val folder = HttpWebPictures.downloadFolder(context)
    private val served = File(context.filesDir, "served").apply { mkdirs() }

    /** How many calls were started on a thread of their own; [refuse] makes them fail, as with too many threads alive. */
    private var started = 0
    private var refuse = false
    private val web = HttpWebPictures(jobs, { call -> if (refuse) false else true.also { started++; call() } }, folder)

    private fun serve(name: String, bytes: ByteArray): String = File(served, name).apply { writeBytes(bytes) }.toURI().toString()

    private fun missing(name: String): String = File(served, name).toURI().toString()

    /** The main thread's answers, and the jobs they queue, until nothing's left. */
    private fun settle() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            jobs.runAll()
        }
    }

    private fun download(picture: WebPicture): Download<Uri>? {
        var answer: Download<Uri>? = null
        web.download(picture) { answer = it }
        settle()
        return answer
    }

    private fun picture(url: String, framing: WebFraming = WebFraming.AS_IS) = WebPicture(url, url, "Bing 2026-10-21 Bridge", framing)

    private fun downloaded(): List<String> = folder.list().orEmpty().toList()

    private fun isBlack(color: Int) = Color.red(color) < 16 && Color.green(color) < 16 && Color.blue(color) < 16

    private val wide = TestPictureFiles.jpeg(TestPictureFiles.halves(1920, 1080, Color.RED, Color.BLUE), 1)

    @Test
    fun `reads a listing's text on a thread of its own, and answers on the main thread`() {
        var text: String? = "none yet"
        web.read(serve("listing.json", """{"images": []}""".toByteArray())) { text = it }
        assertEquals("none yet", text)

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("""{"images": []}""", text)
        assertEquals(1, started)
    }

    @Test
    fun `a listing that can't be read, is too long, or gets no thread, is none`() {
        val answers = mutableListOf<String?>()
        web.read(missing("missing.json")) { answers += it }
        web.read(serve("long.json", ByteArray(HttpWebPictures.MAX_TEXT + 1) { 'a'.code.toByte() })) { answers += it }
        refuse = true
        web.read(serve("listing.json", "{}".toByteArray())) { answers += it }

        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf<String?>(null, null, null), answers)
    }

    @Test
    fun `a listing cancelled never answers`() {
        var answered = false
        val call = web.read(serve("listing.json", "{}".toByteArray())) { answered = true }
        call.cancel()

        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(answered)
    }

    @Test
    fun `downloads a wide picture into a file of its own, named as the library will name it`() {
        val answer = download(picture(serve("bridge.jpg", wide))) as Download.Done

        val file = File(answer.source.path!!)
        assertEquals(File(folder, "Bing 2026-10-21 Bridge"), file)
        assertTrue(wide.contentEquals(file.readBytes()))

        web.discard(answer.source)
        jobs.runAll()
        assertFalse(file.exists())
    }

    @Test
    fun `a picture that doesn't fit the slideshow is unfit, and leaves nothing`() {
        val tall = TestPictureFiles.png(TestPictureFiles.stacked(600, 1000, Color.RED, Color.BLUE))
        val small = TestPictureFiles.png(TestPictureFiles.halves(400, 200, Color.RED, Color.BLUE))

        assertEquals(Download.Unfit, download(picture(serve("tall.png", tall))))
        assertEquals(Download.Unfit, download(picture(serve("small.png", small))))
        assertEquals(Download.Unfit, download(picture(serve("text.jpg", "not a picture".toByteArray()))))
        assertEquals(emptyList<String>(), downloaded())
    }

    @Test
    fun `a picture over 8 MB is unfit, one that can't be had failed`() {
        assertEquals(Download.Unfit, download(picture(serve("huge.jpg", ByteArray(HttpWebPictures.MAX_PICTURE + 1)))))
        assertEquals(Download.Failed, download(picture(missing("missing.jpg"))))
        refuse = true
        assertEquals(Download.Failed, download(picture(serve("bridge.jpg", wide))))
        assertEquals(emptyList<String>(), downloaded())
    }

    @Test
    fun `draws the Earth on a wide black picture, as tall as it, in its middle`() {
        val globe = TestPictureFiles.jpeg(TestPictureFiles.halves(2048, 2048, Color.RED, Color.BLUE), 1)

        val answer = download(picture(serve("epic.jpg", globe), WebFraming.ON_BLACK)) as Download.Done

        val drawn = BitmapFactory.decodeFile(answer.source.path!!)
        assertEquals(1920 to 1080, drawn.width to drawn.height)
        // The globe 1080 px wide in the middle, from 420 to 1500: black on either side.
        assertTrue(isBlack(drawn.getPixel(200, 540)))
        assertTrue(TestPictureFiles.isRed(drawn.getPixel(700, 540)))
        assertTrue(TestPictureFiles.isBlue(drawn.getPixel(1220, 540)))
        assertTrue(isBlack(drawn.getPixel(1720, 540)))
    }

    @Test
    fun `an Earth cut short is unfit, not drawn whole on black`() {
        val globe = TestPictureFiles.png(TestPictureFiles.halves(2048, 2048, Color.RED, Color.BLUE))

        assertEquals(Download.Unfit, download(picture(serve("epic.png", globe.copyOf(globe.size * 2 / 3)), WebFraming.ON_BLACK)))
        assertEquals(emptyList<String>(), downloaded())
    }

    @Test
    fun `a download cancelled never answers, and leaves nothing`() {
        var answered = false
        val call = web.download(picture(serve("bridge.jpg", wide))) { answered = true }
        shadowOf(Looper.getMainLooper()).idle()
        call.cancel()
        settle()

        assertFalse(answered)
        assertEquals(emptyList<String>(), downloaded())
    }

    @Test
    fun `the first download deletes what a killed process left`() {
        folder.mkdirs()
        val left = File(folder, "Bing 2026-10-01 Left").apply { writeText("half") }

        download(picture(serve("bridge.jpg", wide)))

        assertFalse(left.exists())
    }

    @Test
    fun `discards only its own files`() {
        val other = File(served, "keep.jpg").apply { writeBytes(wide) }

        web.discard(Uri.fromFile(other))
        jobs.runAll()

        assertTrue(other.exists())
    }
}
