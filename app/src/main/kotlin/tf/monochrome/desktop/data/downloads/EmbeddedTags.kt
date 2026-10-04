package tf.monochrome.desktop.data.downloads

/**
 * The pure decisions behind the tags [TrackDownloader] embeds in a download —
 * kept apart from the file I/O so they can be unit-tested on the JVM.
 */
internal object EmbeddedTags {

    /**
     * Largest cover worth embedding. The FLAC picture block's length field is
     * 24 bits (16 MiB hard cap), but every track of an album carries its own
     * copy and players load the block whole, so a huge "max" scan costs far
     * more than it shows.
     */
    const val MAX_EMBEDDED_ART_BYTES = 4 * 1024 * 1024

    private val DATE_PREFIX = Regex("""^\d{4}(-\d{2}(-\d{2})?)?""")

    /**
     * The Vorbis DATE value for a catalogue release date: its leading
     * `yyyy`, `yyyy-MM` or `yyyy-MM-dd`. Anything after that (a time, a zone)
     * is dropped, since players parse DATE strictly. Null when there is no
     * usable year — an absent DATE sorts better than a wrong one.
     */
    fun releaseDate(raw: String?): String? {
        val date = raw?.trim()?.let { DATE_PREFIX.find(it)?.value } ?: return null
        return date.takeUnless { it.startsWith("0000") }
    }

    /**
     * The download's title without the " — <version>" suffix the app adds for
     * display; the version travels in its own VERSION tag instead.
     */
    fun baseTitle(title: String, version: String?): String =
        if (version.isNullOrBlank()) title else title.removeSuffix(" — $version").trim()

    /**
     * MIME type of a cover image from its magic bytes, or null for anything
     * other than JPEG or PNG — the only two picture formats players reliably
     * decode out of a FLAC.
     */
    fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "image/jpeg"
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() ->
            "image/png"
        else -> null
    }

    /**
     * Pixel width and height of a JPEG or PNG from its header alone, or null
     * when the header does not say. The FLAC picture block records the
     * dimensions next to the bytes; Android read them with
     * `BitmapFactory.Options.inJustDecodeBounds`, and this is the same read
     * without an image decoder — a PNG keeps them in its IHDR chunk, a JPEG in
     * the first start-of-frame segment.
     */
    fun imageDimensions(bytes: ByteArray): Pair<Int, Int>? = when (imageMime(bytes)) {
        "image/png" -> pngDimensions(bytes)
        "image/jpeg" -> jpegDimensions(bytes)
        else -> null
    }

    private fun pngDimensions(b: ByteArray): Pair<Int, Int>? {
        // Signature (8) + IHDR length (4) + "IHDR" (4), then width and height as
        // big-endian 32-bit integers.
        if (b.size < 24) return null
        if (!(b[12] == 'I'.code.toByte() && b[13] == 'H'.code.toByte() &&
                b[14] == 'D'.code.toByte() && b[15] == 'R'.code.toByte())
        ) return null
        val w = be32(b, 16)
        val h = be32(b, 20)
        return if (w > 0 && h > 0) w to h else null
    }

    private fun jpegDimensions(b: ByteArray): Pair<Int, Int>? {
        // Walk the marker segments to the first SOFn (C0..CF, except the
        // DHT/JPG/DAC markers C4, C8, CC), whose payload is
        // precision(1) height(2) width(2).
        var i = 2
        while (i + 3 < b.size) {
            if ((b[i].toInt() and 0xFF) != 0xFF) return null
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }           // fill byte
            if (marker == 0xD8 || (marker in 0xD0..0xD7) || marker == 0x01) { i += 2; continue } // standalone
            if (i + 3 >= b.size) return null
            val length = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (length < 2) return null
            val isSof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                if (i + 8 >= b.size) return null
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return if (w > 0 && h > 0) w to h else null
            }
            if (marker == 0xD9 || marker == 0xDA) return null // EOI / SOS before any SOF
            i += 2 + length
        }
        return null
    }

    private fun be32(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
}
