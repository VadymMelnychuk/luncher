package com.luncher.launcher.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.luncher.domain.jobs.Cancellable
import com.luncher.domain.jobs.JobPriority
import com.luncher.domain.jobs.Jobs
import com.luncher.domain.slideshow.Download
import com.luncher.domain.slideshow.WebFraming
import com.luncher.domain.slideshow.WebPicture
import com.luncher.domain.slideshow.WebPictures
import com.luncher.domain.slideshow.fitsTheSlideshow
import com.luncher.launcher.outside.OutsideCalls
import com.luncher.launcher.outside.Stop
import com.luncher.launcher.pictures.PictureFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/**
 * [WebPictures] on [URLConnection] (HttpURLConnection for `https://`), no library: the
 * slideshow's listings and pictures. Each call is a wait on a server that may never answer, so it
 * runs on a short-lived thread of its own ([start], [OutsideCalls.start] in the app), never on the
 * jobs' thread, which the screens wait for (docs/ARCHITECTURE.md#background-work-and-changes-while-shown,
 * point 6). That thread ends by itself: connecting and each read give up after [TIMEOUT_MS]
 * without an answer, and it stops reading once its call is cancelled. Every request names
 * Luncher's fork ([USER_AGENT]), as Wikimedia asks of every client.
 *
 * A listing is read whole, up to [MAX_TEXT] bytes; a picture is written to a file of its own in
 * [folder], up to [MAX_PICTURE] bytes, and then, in a job of [JobPriority.SLIDESHOW], checked to
 * [fit the slideshow][fitsTheSlideshow] by its size, or drawn on black ([WebFraming.ON_BLACK]),
 * so the library adds it as any picture of the drop folders: [folder] is one of FilesPictures'
 * own. A server's error for a picture, or what isn't a picture for a slideshow, is
 * [Download.Unfit]; no answer is [Download.Failed]. Its file is deleted when the call is
 * cancelled, when it's unfit, or once the library has it ([discard]); [folder] is emptied before
 * the first download of a process too, of what a killed process left.
 */
class HttpWebPictures(
    private val jobs: Jobs,
    private val start: (() -> Unit) -> Boolean,
    val folder: File,
) : WebPictures<Uri> {

    /** In the app: on [outsideCalls]' threads, in [context]'s [downloadFolder]. */
    constructor(context: Context, jobs: Jobs, outsideCalls: OutsideCalls) : this(jobs, outsideCalls::start, downloadFolder(context))

    private val main = Handler(Looper.getMainLooper())
    private var swept = false

    override fun read(url: String, done: (String?) -> Unit): Cancellable {
        val stop = Stop()
        val started = start {
            val text = try {
                fetch(url, stop) { input -> String(readUpTo(input, MAX_TEXT, stop), Charsets.UTF_8) }
            } catch (e: IOException) {
                null
            }
            main.post { if (!stop.isStopped) done(text) }
        }
        if (!started) main.post { if (!stop.isStopped) done(null) }
        return Cancellable { stop.stop() }
    }

    override fun download(picture: WebPicture, done: (Download<Uri>) -> Unit): Cancellable {
        val sweep = !swept
        swept = true
        val stop = Stop()
        val file = File(folder, picture.name.ifEmpty { "picture" })
        var queued: Cancellable? = null
        val started = start {
            val got = try {
                // Once per process, before the first download: what a killed process left. The
                // downloads come one at a time, so none is being written.
                if (sweep) folder.listFiles()?.forEach { it.delete() }
                folder.mkdirs()
                fetch(picture.url, stop) { input -> FileOutputStream(file).use { copyUpTo(input, it, MAX_PICTURE, stop) } }
                if (stop.isStopped) Download.Failed else null
            } catch (e: Refused) {
                Download.Unfit
            } catch (e: IOException) {
                Download.Failed
            }
            if (got != null) {
                file.delete()
                main.post { if (!stop.isStopped) done(got) }
                return@start
            }
            // Its decoding is Luncher's own slow work: a job, not this thread's.
            main.post {
                if (stop.isStopped) {
                    jobs.run(JobPriority.SLIDESHOW) { file.delete() }
                    return@post
                }
                queued = jobs.run(JobPriority.SLIDESHOW) {
                    val ready = !stop.isStopped && ready(file, picture.framing)
                    if (!ready) file.delete()
                    main.post {
                        if (stop.isStopped) {
                            jobs.run(JobPriority.SLIDESHOW) { file.delete() }
                        } else {
                            done(if (ready) Download.Done(Uri.fromFile(file)) else Download.Unfit)
                        }
                    }
                }
            }
        }
        if (!started) main.post { if (!stop.isStopped) done(Download.Failed) }
        return Cancellable {
            stop.stop()
            queued?.cancel()
            jobs.run(JobPriority.SLIDESHOW) { file.delete() }
        }
    }

    override fun discard(source: Uri) {
        val file = File(source.path ?: return)
        if (file.parentFile == folder) jobs.run(JobPriority.SLIDESHOW) { file.delete() }
    }

    /** An answer that isn't what was asked for: an error, or too long. */
    private class Refused : IOException()

    /**
     * Asks for [url], and gives [read] its answer, on this thread; [Refused] for an error's answer.
     * Each wait gives up after [TIMEOUT_MS]; redirects are followed, but not from `https` to `http`.
     */
    private fun <T> fetch(url: String, stop: Stop, read: (InputStream) -> T): T {
        val connection = URL(url).openConnection()
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (connection is HttpURLConnection && connection.responseCode !in 200..299) throw Refused()
            if (stop.isStopped) throw IOException("stopped")
            return connection.getInputStream().use(read)
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    /** [input] whole: [Refused] past [most] bytes, an IOException once [stop] is set. */
    private fun readUpTo(input: InputStream, most: Int, stop: Stop): ByteArray =
        ByteArrayOutputStream().also { copyUpTo(input, it, most, stop) }.toByteArray()

    /** [input] into [output]: [Refused] past [most] bytes, an IOException once [stop] is set. */
    private fun copyUpTo(input: InputStream, output: OutputStream, most: Int, stop: Stop) {
        val buffer = ByteArray(BUFFER)
        var total = 0
        while (true) {
            if (stop.isStopped) throw IOException("stopped")
            val n = input.read(buffer)
            if (n < 0) return
            total += n
            if (total > most) throw Refused()
            output.write(buffer, 0, n)
        }
    }

    /**
     * A job: whether [file] is a picture for the slideshow, as [framing] says: one that fits, as
     * it is, or the Earth, then drawn on a black [CANVAS_WIDTH] x [CANVAS_HEIGHT] picture in its
     * place, as tall as it and in its middle. The Earth only if it's whole ([PictureFiles.whole]):
     * cut short or damaged, its decoder would draw it in part, and the new JPEG would be whole.
     * One as it is the library checks as it adds it, as any picture.
     */
    private fun ready(file: File, framing: WebFraming): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        decode(file, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return false
        return when (framing) {
            WebFraming.AS_IS -> fitsTheSlideshow(width, height)
            WebFraming.ON_BLACK -> PictureFiles.whole(file) && onBlack(file, height)
        }
    }

    /** [file], [height] px high, drawn on black, in its place: decoded no larger than about the canvas' height needs. */
    private fun onBlack(file: File, height: Int): Boolean {
        var sample = 1
        while (height / (sample * 2) >= CANVAS_HEIGHT * 9 / 10) sample *= 2
        val picture = decode(file, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false
        val canvas = try {
            Bitmap.createBitmap(CANVAS_WIDTH, CANVAS_HEIGHT, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            picture.recycle()
            return false
        }
        val side = CANVAS_HEIGHT * picture.width / picture.height
        val left = (CANVAS_WIDTH - side) / 2
        Canvas(canvas).apply {
            drawColor(Color.BLACK)
            drawBitmap(picture, null, Rect(left, 0, left + side, CANVAS_HEIGHT), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        }
        picture.recycle()
        return try {
            FileOutputStream(file).use { canvas.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        } catch (e: IOException) {
            false
        } finally {
            canvas.recycle()
        }
    }

    /** [file] decoded with [options], read through a stream closed at once, so the file can be replaced or deleted after. */
    private fun decode(file: File, options: BitmapFactory.Options): Bitmap? =
        try {
            FileInputStream(file).use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: IOException) {
            null
        }

    companion object {
        /** Where the slideshow's pictures are downloaded to, one at a time, before the library adds them. */
        fun downloadFolder(context: Context): File = File(context.cacheDir, "slideshow")

        /** What every request says it comes from. */
        const val USER_AGENT = "Luncher-fork/0.3 (https://github.com/VadymMelnychuk/luncher)"

        /** How long connecting, and each read, waits for the server. */
        const val TIMEOUT_MS = 15_000

        /** The longest listing read: 2 MB. */
        const val MAX_TEXT = 2_000_000

        /** The largest picture downloaded: 8 MB. */
        const val MAX_PICTURE = 8_000_000

        const val CANVAS_WIDTH = 1920
        const val CANVAS_HEIGHT = 1080
        const val JPEG_QUALITY = 90
        private const val BUFFER = 64 * 1024
    }
}
