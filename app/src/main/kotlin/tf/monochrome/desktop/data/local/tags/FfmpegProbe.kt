package tf.monochrome.desktop.data.local.tags

import android.util.Log
import java.nio.charset.StandardCharsets
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.PointerPointer

/**
 * Desktop: what Android's `MediaExtractor` (and, for the formats it covered,
 * `MediaMetadataRetriever`) told the scanner about a container, read with
 * libavformat through JavaCV.
 *
 * Three readers use it:
 * - [TagReader], for the elementary-stream codec of a multi-codec container
 *   (`audioTrackMime` on Android: an E-AC-3 track in an .mp4 is not AAC), and
 *   for every format JAudioTagger has no reader for (Opus, WavPack, APE, TAK,
 *   Musepack, Matroska, raw AC-3/E-AC-3/DTS, …): tags, length, format and
 *   embedded picture all come from here then;
 * - the library walker ([tf.monochrome.desktop.data.local.scanner.MediaStoreSource]),
 *   to tell an Atmos music video from a screen recording;
 * - [tf.monochrome.desktop.data.local.coil.AudioFileCoverFetcher], for covers
 *   in those same formats.
 *
 * Only headers and the stream index are read, never the media data (beyond
 * what `avformat_find_stream_info` decodes to learn a format), and every
 * failure is a null result: a file FFmpeg cannot open must skip quietly, not
 * abort a scan. If the FFmpeg natives cannot be loaded at all, the probe turns
 * itself off for the session and the scanner carries on with JAudioTagger and
 * file extensions.
 */
internal object FfmpegProbe {

    private const val TAG = "FfmpegProbe"

    class Result(
        /** The audio track's MIME ("audio/eac3", "audio/eac3-joc", "audio/flac", …), null when unmapped. */
        val audioMime: String?,
        val sampleRate: Int,
        val channels: Int,
        /** Bits per sample of the coded stream; meaningful for lossless and PCM only. */
        val bitsPerSample: Int?,
        /** Bits per second; 0 when unknown. */
        val bitRate: Long,
        /** 0 when unknown. */
        val durationMs: Long,
        /** True when the file carries a real video track (an attached cover does not count). */
        val hasVideo: Boolean,
        /** True when the file has an audio track at all. */
        val hasAudio: Boolean,
        /** Container and audio-stream metadata, keys lower-cased; the container's value wins. */
        val tags: Map<String, String>,
        /** The first attached picture (cover art), when [probe] was asked for it. */
        val picture: ByteArray?,
    )

    @Volatile
    private var unavailable = false

    /**
     * Opens [path] and reports what its header says, or null.
     *
     * [readStreamInfo] always runs `avformat_find_stream_info`, which a
     * transport stream needs before its codecs are known. Without it the pass
     * still runs when the header leaves the audio format open, or names
     * E-AC-3, whose Atmos (JOC) profile the pass settles; an MP4 or Matroska
     * file that names AAC or ALAC in its header costs only the open.
     */
    fun probe(path: String, readStreamInfo: Boolean = false, wantPicture: Boolean = false): Result? {
        if (unavailable) return null
        return try {
            open(path, readStreamInfo, wantPicture)
        } catch (e: LinkageError) {
            // UnsatisfiedLinkError / NoClassDefFoundError / ExceptionInInitializerError:
            // the natives are missing for this platform. Say so once.
            unavailable = true
            Log.w(TAG, "FFmpeg is not available, probing disabled: $e")
            null
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            // A malformed container can make a demuxer over-allocate.
            null
        }
    }

    private fun open(path: String, readStreamInfo: Boolean, wantPicture: Boolean): Result? {
        val ctx = avformat.avformat_alloc_context() ?: return null
        // A user-supplied context is freed by avformat_open_input when it fails.
        if (avformat.avformat_open_input(ctx, path, null, null as AVDictionary?) < 0) return null
        try {
            // A failure of the stream-info pass still leaves whatever the
            // header named, so its result is not checked.
            var infoRead = false
            if (readStreamInfo) {
                avformat.avformat_find_stream_info(ctx, null as PointerPointer<*>?)
                infoRead = true
            }
            var audioIndex = bestAudioStream(ctx)
            // MP4 and Matroska name their codecs in the header, so most files
            // need no stream-info pass. It still runs when the header left the
            // format open, and for E-AC-3, whose Atmos profile the pass settles.
            if (!infoRead && needsStreamInfo(ctx, audioIndex)) {
                avformat.avformat_find_stream_info(ctx, null as PointerPointer<*>?)
                audioIndex = bestAudioStream(ctx)
            }

            var hasVideo = false
            var picture: ByteArray? = null
            for (i in 0 until ctx.nb_streams()) {
                val stream = ctx.streams(i) ?: continue
                val par = stream.codecpar() ?: continue
                if (par.codec_type() != avutil.AVMEDIA_TYPE_VIDEO) continue
                if ((stream.disposition() and avformat.AV_DISPOSITION_ATTACHED_PIC) != 0) {
                    if (wantPicture && picture == null) {
                        val packet = stream.attached_pic()
                        val size = packet?.size() ?: 0
                        val data = packet?.data()
                        if (size > 0 && data != null && !data.isNull) {
                            picture = ByteArray(size).also { data.get(it) }
                        }
                    }
                } else {
                    hasVideo = true
                }
            }

            val tags = LinkedHashMap<String, String>()
            tags.addAll(ctx.metadata())

            if (audioIndex < 0) {
                return Result(
                    audioMime = null, sampleRate = 0, channels = 0, bitsPerSample = null,
                    bitRate = 0, durationMs = durationMs(ctx, null), hasVideo = hasVideo,
                    hasAudio = false, tags = tags, picture = picture,
                )
            }
            val stream = ctx.streams(audioIndex)
            // Ogg keeps its Vorbis comments on the stream rather than the container.
            tags.addAll(stream.metadata())
            val par = stream.codecpar()
            val bits = par.bits_per_raw_sample().takeIf { it > 0 }
                ?: par.bits_per_coded_sample().takeIf { it > 0 }
            return Result(
                audioMime = mimeFor(par),
                sampleRate = par.sample_rate().coerceAtLeast(0),
                channels = par.ch_layout()?.nb_channels()?.coerceAtLeast(0) ?: 0,
                bitsPerSample = bits,
                bitRate = par.bit_rate().takeIf { it > 0 } ?: ctx.bit_rate().coerceAtLeast(0),
                durationMs = durationMs(ctx, stream),
                hasVideo = hasVideo,
                hasAudio = true,
                tags = tags,
                picture = picture,
            )
        } finally {
            avformat.avformat_close_input(ctx)
        }
    }

    private fun bestAudioStream(ctx: AVFormatContext): Int =
        avformat.av_find_best_stream(ctx, avutil.AVMEDIA_TYPE_AUDIO, -1, -1, null as AVCodec?, 0)

    private fun needsStreamInfo(ctx: AVFormatContext, audioIndex: Int): Boolean {
        if (audioIndex < 0) return true
        val par = ctx.streams(audioIndex)?.codecpar() ?: return true
        return par.codec_id() == avcodec.AV_CODEC_ID_NONE ||
            par.codec_id() == avcodec.AV_CODEC_ID_EAC3 ||
            par.sample_rate() <= 0 ||
            (par.ch_layout()?.nb_channels() ?: 0) <= 0
    }

    private fun durationMs(ctx: AVFormatContext, stream: org.bytedeco.ffmpeg.avformat.AVStream?): Long {
        val container = ctx.duration()
        if (container > 0) return container / 1000 // AV_TIME_BASE is microseconds
        if (stream == null) return 0
        val d = stream.duration()
        val tb = stream.time_base() ?: return 0
        if (d <= 0 || tb.den() == 0) return 0
        return d * 1000L * tb.num() / tb.den()
    }

    private fun MutableMap<String, String>.addAll(dict: AVDictionary?) {
        if (dict == null || dict.isNull) return
        var entry: AVDictionaryEntry? = null
        while (true) {
            entry = avutil.av_dict_iterate(dict, entry)
            if (entry == null || entry.isNull) break
            val key = entry.key()?.getString(StandardCharsets.UTF_8)?.lowercase() ?: continue
            val value = entry.value()?.getString(StandardCharsets.UTF_8) ?: continue
            putIfAbsent(key, value)
        }
    }

    /**
     * The MIME Android's extractor reported for the same track. E-AC-3 with the
     * Atmos JOC extension is "audio/eac3-joc" there, and FFmpeg says so through
     * the stream profile — when the demuxer or the stream-info pass got that far.
     */
    private fun mimeFor(par: AVCodecParameters): String? = when (par.codec_id()) {
        avcodec.AV_CODEC_ID_EAC3 ->
            if (par.profile() == avcodec.AV_PROFILE_EAC3_DDP_ATMOS) "audio/eac3-joc" else "audio/eac3"
        avcodec.AV_CODEC_ID_AC3 -> "audio/ac3"
        avcodec.AV_CODEC_ID_AC4 -> "audio/ac4"
        avcodec.AV_CODEC_ID_FLAC -> "audio/flac"
        avcodec.AV_CODEC_ID_MP3 -> "audio/mpeg"
        avcodec.AV_CODEC_ID_AAC -> "audio/mp4a-latm"
        avcodec.AV_CODEC_ID_ALAC -> "audio/alac"
        avcodec.AV_CODEC_ID_OPUS -> "audio/opus"
        avcodec.AV_CODEC_ID_VORBIS -> "audio/vorbis"
        avcodec.AV_CODEC_ID_TRUEHD -> "audio/true-hd"
        avcodec.AV_CODEC_ID_DTS -> "audio/vnd.dts"
        avcodec.AV_CODEC_ID_WMAV2, avcodec.AV_CODEC_ID_WMAPRO, avcodec.AV_CODEC_ID_WMALOSSLESS -> "audio/x-ms-wma"
        // PCM, WavPack, APE, TAK, DSD and the rest have no AudioCodec of their
        // own; the caller falls back to the file extension, as Android did.
        else -> null
    }
}
