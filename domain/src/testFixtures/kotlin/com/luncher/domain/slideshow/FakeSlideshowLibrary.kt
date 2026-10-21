package com.luncher.domain.slideshow

/** [SlideshowLibrary] in memory: [kept] is what's stored, by source, which a test sets and checks. */
class FakeSlideshowLibrary(vararg kept: Pair<SlideshowSource, SourceSlides>) : SlideshowLibrary {

    val kept: MutableMap<SlideshowSource, SourceSlides> = mutableMapOf(*kept)

    override fun read(source: SlideshowSource): SourceSlides = kept[source] ?: SourceSlides()

    override fun write(source: SlideshowSource, slides: SourceSlides) {
        kept[source] = slides
    }
}
