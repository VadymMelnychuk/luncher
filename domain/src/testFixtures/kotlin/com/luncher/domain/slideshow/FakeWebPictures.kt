package com.luncher.domain.slideshow

import com.luncher.domain.jobs.Cancellable

/**
 * [WebPictures] that answer when the test says: each [read] and [download] waits in [waiting]
 * until the test gives its answer ([answerRead], [answerDownload]), as the network answers later.
 * A picture downloaded is its [WebPicture.url] as its source, so the test's library knows it by
 * that. Cancelled before its answer, a call never answers.
 */
class FakeWebPictures : WebPictures<String> {

    /** A call not answered yet. */
    sealed interface Call {
        data class Read(val url: String, val done: (String?) -> Unit) : Call
        data class Get(val picture: WebPicture, val done: (Download<String>) -> Unit) : Call
    }

    val waiting = mutableListOf<Call>()

    /** Every url read and picture downloaded, in order, answered or not. */
    val asked = mutableListOf<String>()

    /** Every source discarded, in order. */
    val discarded = mutableListOf<String>()

    /** Every call cancelled before its answer. */
    val cancelled = mutableListOf<Call>()

    override fun read(url: String, done: (String?) -> Unit): Cancellable = wait(Call.Read(url, done), url)

    override fun download(picture: WebPicture, done: (Download<String>) -> Unit): Cancellable = wait(Call.Get(picture, done), picture.url)

    override fun discard(source: String) {
        discarded += source
    }

    private fun wait(call: Call, what: String): Cancellable {
        asked += what
        waiting += call
        return Cancellable { if (waiting.remove(call)) cancelled += call }
    }

    /** Answers the read waiting, the oldest, with [text]. */
    fun answerRead(text: String?) {
        val call = waiting.filterIsInstance<Call.Read>().first()
        waiting.remove(call)
        call.done(text)
    }

    /** Answers the download waiting, the oldest: [Download.Done] with its url by default. */
    fun answerDownload(answer: (WebPicture) -> Download<String> = { Download.Done(it.url) }) {
        val call = waiting.filterIsInstance<Call.Get>().first()
        waiting.remove(call)
        call.done(answer(call.picture))
    }

    /** The picture of the download waiting, if one is. */
    val downloading: WebPicture? get() = waiting.filterIsInstance<Call.Get>().firstOrNull()?.picture
}
