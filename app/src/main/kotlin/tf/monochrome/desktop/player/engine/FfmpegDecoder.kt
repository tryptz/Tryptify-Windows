package tf.monochrome.desktop.player.engine

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.avutil.AVRational
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

/** What the decoder learned about the stream; feeds the Audio Pipeline panel. */
data class DecodedStreamInfo(
    val codecName: String,
    val mimeType: String?,
    val sampleRate: Int,
    val channelCount: Int,
    val bitrate: Int?,
    val bitsPerRawSample: Int?,
    val outputIsFloat: Boolean,
    val durationUs: Long,
    val isEac3: Boolean,
    val containerName: String?,
)

/**
 * Decodes one audio stream with FFmpeg (libavformat + libavcodec + libswresample
 * through JavaCV) into interleaved PCM for the processor chain.
 *
 * Replaces Media3's renderers: on Android the FFmpeg extension produced 16-bit
 * or float PCM and ExoPlayer drove the chain; here [decode] produces the same
 * two formats — 16-bit when the codec's native format is 16-bit integer (so a
 * CD-quality FLAC stays bit-exact), float for everything else — and the engine
 * drives the chain.
 *
 * Input is either a URL libavformat opens itself (files, http/https with the
 * app's headers, Icecast/HLS radio, DASH) or one of the app's [DataSource]
 * caches (Qobuz/Deezer partial caches, decrypting sources) through a custom
 * AVIO context, so [tf.monochrome.desktop.player.StreamResolver]'s routing is
 * unchanged.
 *
 * Gapless: libavcodec applies the container's encoder delay and padding
 * (skip_samples side data) itself, so MP3/AAC tracks join without clicks.
 *
 * Single-threaded: the engine owns one decode thread per open stream.
 */
class FfmpegDecoder private constructor(
    private val format: AVFormatContext,
    private val codecCtx: AVCodecContext,
    private val stream: AVStream,
    private val streamIndex: Int,
    private val swr: SwrContext?,
    val outputFormat: AudioFormat,
    val info: DecodedStreamInfo,
    private val avio: AvioBridge?,
) : AutoCloseable {

    /** Receives every compressed packet before decoding (the Atmos JOC side-data tap). */
    var packetTap: ((ptsUs: Long, bytes: ByteArray) -> Unit)? = null

    /** Presentation time of the first frame in the last [decode] call, microseconds, or C.TIME_UNSET. */
    var lastPtsUs: Long = C.TIME_UNSET
        private set

    private val packet: AVPacket = avcodec.av_packet_alloc()
    private val frame: AVFrame = avutil.av_frame_alloc()
    private val bytesPerFrame = outputFormat.bytesPerFrame
    private var outBuf: BytePointer = BytePointer(0L)
    private var outCapacityFrames = 0
    private var scratch = ByteArray(0)
    private var eofSent = false
    private var ended = false
    private var skipFrames = 0L          // samples to drop after a seek, before the target
    private var pendingFrameAvailable = false
    private var closed = false
    private val timeBaseUs = AVRational().num(1).den(avutil.AV_TIME_BASE)

    /**
     * Fills [dst] with as many whole frames as fit (from its position) and
     * returns the frame count; 0 means end of stream. Blocks on I/O.
     */
    fun decode(dst: ByteBuffer): Int {
        check(!closed) { "decoder closed" }
        if (ended) return 0
        var produced = 0
        lastPtsUs = C.TIME_UNSET
        val roomFrames = dst.remaining() / bytesPerFrame
        while (produced < roomFrames) {
            if (!pendingFrameAvailable && !receiveFrame()) {
                if (produced == 0) { ended = true }
                break
            }
            val n = convertFrame(dst, roomFrames - produced)
            if (n < 0) break      // frame fully consumed but nothing fit yet; keep it pending
            produced += n
            if (pendingFrameAvailable) break   // dst is full, rest of the frame stays pending
        }
        return produced
    }

    /** Seeks to [positionUs]; the next decode starts at (or just before) it, trimmed to the sample. */
    fun seek(positionUs: Long) {
        check(!closed)
        val ts = avutil.av_rescale_q(positionUs.coerceAtLeast(0), timeBaseUs, stream.time_base())
        val rc = avformat.avformat_seek_file(format, streamIndex, Long.MIN_VALUE, ts, ts, avformat.AVSEEK_FLAG_BACKWARD)
        if (rc < 0) {
            // Live streams and some demuxers cannot seek: keep playing from where we are.
            Log.w(TAG, "seek to ${positionUs / 1000} ms refused: ${errorString(rc)}")
            return
        }
        avcodec.avcodec_flush_buffers(codecCtx)
        if (swr != null) {
            // Drop the resampler's delay line so no samples from before the seek leak out.
            swresample.swr_convert(swr, null as PointerPointer<*>?, 0, null as PointerPointer<*>?, 0)
        }
        avutil.av_frame_unref(frame)
        pendingFrameAvailable = false
        pendingOffsetFrames = 0
        eofSent = false
        ended = false
        skipTargetUs = positionUs
        skipFrames = -1
    }

    private var skipTargetUs = C.TIME_UNSET
    private var pendingOffsetFrames = 0   // frames of the pending AVFrame already delivered
    private var pendingFrameCount = 0
    private var pendingFrameConverted = false

    /** Pulls the next decoded frame into [frame]; false at end of stream. */
    private fun receiveFrame(): Boolean {
        while (true) {
            val rc = avcodec.avcodec_receive_frame(codecCtx, frame)
            if (rc == 0) {
                pendingFrameAvailable = true
                pendingOffsetFrames = 0
                pendingFrameConverted = false
                if (skipFrames < 0 && skipTargetUs != C.TIME_UNSET) {
                    val ptsUs = framePtsUs()
                    skipFrames = if (ptsUs != C.TIME_UNSET && ptsUs < skipTargetUs) {
                        (skipTargetUs - ptsUs) * info.sampleRate / C.MICROS_PER_SECOND
                    } else 0
                    skipTargetUs = C.TIME_UNSET
                }
                return true
            }
            if (rc == avutil.AVERROR_EOF) return false
            if (rc != averror(EAGAIN)) throw IOException("avcodec_receive_frame: ${errorString(rc)}")
            // Needs more input.
            if (eofSent) return false
            val read = avformat.av_read_frame(format, packet)
            if (read < 0) {
                if (read == avutil.AVERROR_EOF || avio?.ended == true) {
                    eofSent = true
                    avcodec.avcodec_send_packet(codecCtx, null)
                    continue
                }
                throw IOException("av_read_frame: ${errorString(read)}")
            }
            try {
                if (packet.stream_index() != streamIndex) continue
                packetTap?.let { tap ->
                    val size = packet.size()
                    if (size > 0) {
                        val bytes = ByteArray(size)
                        packet.data().get(bytes, 0, size)
                        val pts = packet.pts()
                        tap(if (pts == avutil.AV_NOPTS_VALUE) C.TIME_UNSET else avutil.av_rescale_q(pts, stream.time_base(), timeBaseUs), bytes)
                    }
                }
                val sent = avcodec.avcodec_send_packet(codecCtx, packet)
                if (sent < 0 && sent != averror(EAGAIN)) {
                    // A damaged packet: skip it rather than stopping the track.
                    Log.w(TAG, "avcodec_send_packet: ${errorString(sent)}")
                }
            } finally {
                avcodec.av_packet_unref(packet)
            }
        }
    }

    private fun framePtsUs(): Long {
        val pts = frame.best_effort_timestamp().let { if (it == avutil.AV_NOPTS_VALUE) frame.pts() else it }
        return if (pts == avutil.AV_NOPTS_VALUE) C.TIME_UNSET else avutil.av_rescale_q(pts, stream.time_base(), timeBaseUs)
    }

    /**
     * Converts the pending frame (or the rest of it) into [dst], at most [maxFrames].
     * Returns frames written, or -1 when the pending frame was consumed by a skip.
     */
    private fun convertFrame(dst: ByteBuffer, maxFrames: Int): Int {
        val nbSamples = frame.nb_samples()
        if (!pendingFrameConverted) {
            ensureOut(nbSamples)
            val converted = if (swr != null) {
                val outPtrs = PointerPointer<BytePointer>(1L).put(0, outBuf)
                val n = swresample.swr_convert(swr, outPtrs, nbSamples, frame.extended_data(), nbSamples)
                outPtrs.deallocate()
                if (n < 0) throw IOException("swr_convert: ${errorString(n)}")
                n
            } else {
                // Native format already matches: copy the interleaved plane.
                val bytes = nbSamples * bytesPerFrame
                Pointer.memcpy(outBuf, frame.data(0), bytes.toLong())
                nbSamples
            }
            pendingFrameCount = converted
            pendingFrameConverted = true
            pendingOffsetFrames = 0
            val pts = framePtsUs()
            if (pts != C.TIME_UNSET && lastPtsUs == C.TIME_UNSET) lastPtsUs = pts
            if (skipFrames > 0) {
                val drop = minOf(skipFrames, converted.toLong()).toInt()
                pendingOffsetFrames += drop
                skipFrames -= drop
                if (lastPtsUs != C.TIME_UNSET) lastPtsUs += drop * C.MICROS_PER_SECOND / info.sampleRate
            }
        }
        val available = pendingFrameCount - pendingOffsetFrames
        if (available <= 0) {
            pendingFrameAvailable = false
            avutil.av_frame_unref(frame)
            return -1
        }
        val n = minOf(available, maxFrames)
        val bytes = n * bytesPerFrame
        if (scratch.size < bytes) scratch = ByteArray(bytes)
        outBuf.position(pendingOffsetFrames.toLong() * bytesPerFrame).get(scratch, 0, bytes)
        outBuf.position(0)
        dst.put(scratch, 0, bytes)
        pendingOffsetFrames += n
        if (pendingOffsetFrames >= pendingFrameCount) {
            pendingFrameAvailable = false
            avutil.av_frame_unref(frame)
        }
        return n
    }

    private fun ensureOut(frames: Int) {
        // The resampler may emit a few more samples than it was given.
        val needed = frames + 256
        if (outCapacityFrames < needed) {
            outBuf.deallocate()
            outBuf = BytePointer(needed.toLong() * bytesPerFrame)
            outCapacityFrames = needed
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        avutil.av_frame_free(frame)
        avcodec.av_packet_free(packet)
        swr?.let { swresample.swr_free(it) }
        avcodec.avcodec_free_context(codecCtx)
        avformat.avformat_close_input(format)
        avio?.close()
        outBuf.deallocate()
    }

    /** Custom AVIO over one of the app's DataSources. */
    private class AvioBridge(private val source: DataSource, private val uri: Uri, private val length: Long) : AutoCloseable {
        var ended = false
        private var position = 0L
        private var scratch = ByteArray(IO_BUFFER)

        // Strong references: libavformat only holds the native function pointers.
        val reader = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, buf: BytePointer, size: Int): Int {
                return try {
                    if (scratch.size < size) scratch = ByteArray(size)
                    val n = source.read(scratch, 0, size)
                    if (n == C.RESULT_END_OF_INPUT || n < 0) {
                        ended = true
                        avutil.AVERROR_EOF
                    } else {
                        position += n
                        buf.put(scratch, 0, n)
                        n
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "avio read failed", e)
                    averror(EIO)
                }
            }
        }

        val seeker = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, whence: Int): Long {
                if (whence and avformat.AVSEEK_SIZE != 0) return if (length >= 0) length else -1L
                val target = when (whence and 3) {
                    0 -> offset                        // SEEK_SET
                    1 -> position + offset             // SEEK_CUR
                    2 -> if (length >= 0) length + offset else return -1L
                    else -> return -1L
                }
                if (target < 0 || (length >= 0 && target > length)) return -1L
                return try {
                    source.close()
                    source.open(DataSpec(uri, target))
                    position = target
                    ended = false
                    target
                } catch (e: IOException) {
                    Log.w(TAG, "avio seek failed", e)
                    -1L
                }
            }
        }

        val context: AVIOContext = avformat.avio_alloc_context(
            BytePointer(avutil.av_malloc(IO_BUFFER.toLong())), IO_BUFFER, 0, null, reader, null, seeker,
        ) ?: throw IOException("avio_alloc_context failed")

        override fun close() {
            try { source.close() } catch (_: IOException) {}
            // libavformat frees the buffer; the context is ours.
            val buffer = context.buffer()
            avformat.avio_context_free(context)
            avutil.av_free(buffer)
            reader.deallocate()
            seeker.deallocate()
        }

        companion object { const val IO_BUFFER = 64 * 1024 }
    }

    companion object {
        private const val TAG = "FfmpegDecoder"
        private const val EAGAIN = 11
        private const val EIO = 5

        init {
            avformat.avformat_network_init()
            avutil.av_log_set_level(avutil.AV_LOG_WARNING)
        }

        private fun averror(errno: Int): Int = -errno

        fun errorString(code: Int): String {
            val buf = ByteArray(256)
            avutil.av_strerror(code, buf, buf.size.toLong())
            val end = buf.indexOf(0).let { if (it < 0) buf.size else it }
            return String(buf, 0, end)
        }

        /**
         * Opens [url] directly with libavformat (file paths, http(s), hls, dash),
         * sending [headers] and the app's user agent on network streams.
         */
        fun open(
            url: String,
            headers: Map<String, String> = emptyMap(),
            userAgent: String? = null,
            formatName: String? = null,
            protocolWhitelist: String? = null,
        ): FfmpegDecoder {
            val format = avformat.avformat_alloc_context() ?: throw IOException("avformat_alloc_context failed")
            val options = AVDictionary(null as Pointer?)
            if (userAgent != null) avutil.av_dict_set(options, "user_agent", userAgent, 0)
            if (protocolWhitelist != null) avutil.av_dict_set(options, "protocol_whitelist", protocolWhitelist, 0)
            if (headers.isNotEmpty()) {
                avutil.av_dict_set(options, "headers", headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }, 0)
            }
            avutil.av_dict_set(options, "reconnect", "1", 0)
            avutil.av_dict_set(options, "reconnect_streamed", "1", 0)
            avutil.av_dict_set(options, "reconnect_delay_max", "5", 0)
            avutil.av_dict_set(options, "rw_timeout", "20000000", 0)   // 20 s, microseconds
            val inputFormat = formatName?.let { name ->
                avformat.av_find_input_format(name) ?: throw IOException("this FFmpeg build has no $name demuxer")
            }
            val rc = avformat.avformat_open_input(format, url, inputFormat, options)
            avutil.av_dict_free(options)
            if (rc < 0) throw IOException("cannot open ${url.take(200)}: ${errorString(rc)}")
            return finishOpen(format, null)
        }

        /**
         * Opens an inline DASH manifest: StreamResolver puts TIDAL's MPD on the
         * item as a `data:` URI, as the Android app handed it to ExoPlayer.
         *
         * libavformat's DASH demuxer reads the manifest and then fetches the
         * segments it names, and a demuxer may only open protocols its parent
         * allows. So the manifest goes to a file and the segment protocols are
         * whitelisted. A static manifest is read once, during open, so the file
         * is deleted as soon as that returns.
         */
        fun openDash(manifest: String, scratchDir: File, userAgent: String?): FfmpegDecoder {
            scratchDir.mkdirs()
            val file = File.createTempFile("stream-", ".mpd", scratchDir)
            return try {
                file.writeText(manifest)
                open(file.absolutePath, userAgent = userAgent, formatName = "dash", protocolWhitelist = DASH_PROTOCOLS)
            } finally {
                file.delete()
            }
        }

        /** libavformat/avformat.h; JavaCV does not expose the AVFMT_FLAG_* macros. */
        private const val AVFMT_FLAG_CUSTOM_IO = 0x0080

        private const val DASH_PROTOCOLS = "file,http,https,tcp,tls,crypto"

        /** Opens through one of the app's DataSources (partial caches, decrypting sources). */
        fun open(source: DataSource, uri: Uri): FfmpegDecoder {
            val length = source.open(DataSpec(uri, 0))
            val bridge = AvioBridge(source, uri, length)
            val format = avformat.avformat_alloc_context() ?: run { bridge.close(); throw IOException("avformat_alloc_context failed") }
            format.pb(bridge.context)
            format.flags(format.flags() or AVFMT_FLAG_CUSTOM_IO)
            val rc = avformat.avformat_open_input(format, "", null, null as AVDictionary?)
            if (rc < 0) { bridge.close(); throw IOException("cannot open ${uri}: ${errorString(rc)}") }
            return finishOpen(format, bridge)
        }

        private fun finishOpen(format: AVFormatContext, avio: AvioBridge?): FfmpegDecoder {
            try {
                var rc = avformat.avformat_find_stream_info(format, null as PointerPointer<*>?)
                if (rc < 0) throw IOException("avformat_find_stream_info: ${errorString(rc)}")
                val index = avformat.av_find_best_stream(format, avutil.AVMEDIA_TYPE_AUDIO, -1, -1, null as AVCodec?, 0)
                if (index < 0) throw IOException("no audio stream: ${errorString(index)}")
                val stream = format.streams(index)
                val par = stream.codecpar()
                val codec = avcodec.avcodec_find_decoder(par.codec_id()) ?: throw IOException("no decoder for codec ${par.codec_id()}")
                val ctx = avcodec.avcodec_alloc_context3(codec) ?: throw IOException("avcodec_alloc_context3 failed")
                rc = avcodec.avcodec_parameters_to_context(ctx, par)
                if (rc < 0) throw IOException("avcodec_parameters_to_context: ${errorString(rc)}")
                ctx.pkt_timebase(stream.time_base())
                rc = avcodec.avcodec_open2(ctx, codec, null as AVDictionary?)
                if (rc < 0) throw IOException("avcodec_open2: ${errorString(rc)}")

                val inFmt = par.format()
                val channels = par.ch_layout().nb_channels()
                val sampleRate = par.sample_rate()
                if (channels <= 0 || sampleRate <= 0) throw IOException("stream has no usable format")
                // 16-bit integer sources stay 16-bit (bit-exact); everything else becomes float.
                val wantS16 = inFmt == avutil.AV_SAMPLE_FMT_S16 || inFmt == avutil.AV_SAMPLE_FMT_S16P
                val outFmt = if (wantS16) avutil.AV_SAMPLE_FMT_S16 else avutil.AV_SAMPLE_FMT_FLT
                val encoding = if (wantS16) C.ENCODING_PCM_16BIT else C.ENCODING_PCM_FLOAT
                var swr: SwrContext? = null
                if (inFmt != outFmt) {
                    val outLayout = AVChannelLayout()
                    avutil.av_channel_layout_default(outLayout, channels)
                    val holder = SwrContext(null as Pointer?)
                    rc = swresample.swr_alloc_set_opts2(holder, outLayout, outFmt, sampleRate, par.ch_layout(), inFmt, sampleRate, 0, null)
                    if (rc < 0 || holder.isNull) throw IOException("swr_alloc_set_opts2: ${errorString(rc)}")
                    rc = swresample.swr_init(holder)
                    if (rc < 0) throw IOException("swr_init: ${errorString(rc)}")
                    swr = holder
                }
                val codecId = par.codec_id()
                val durationUs = when {
                    format.duration() > 0 -> format.duration()
                    stream.duration() > 0 -> avutil.av_rescale_q(stream.duration(), stream.time_base(), AVRational().num(1).den(avutil.AV_TIME_BASE))
                    else -> C.TIME_UNSET
                }
                val info = DecodedStreamInfo(
                    codecName = avcodec.avcodec_get_name(codecId).string,
                    mimeType = mimeTypeFor(codecId),
                    sampleRate = sampleRate,
                    channelCount = channels,
                    bitrate = par.bit_rate().takeIf { it > 0 }?.toInt(),
                    bitsPerRawSample = par.bits_per_raw_sample().takeIf { it > 0 } ?: if (wantS16) 16 else null,
                    outputIsFloat = !wantS16,
                    durationUs = durationUs,
                    isEac3 = codecId == avcodec.AV_CODEC_ID_EAC3,
                    containerName = format.iformat()?.name()?.string,
                )
                Log.i(TAG, "opened ${info.codecName} ${sampleRate} Hz x$channels in=$inFmt out=${if (wantS16) "s16" else "flt"} duration=${durationUs / 1000} ms")
                return FfmpegDecoder(format, ctx, stream, index, swr, AudioFormat(sampleRate, channels, encoding), info, avio)
            } catch (e: Exception) {
                avformat.avformat_close_input(format)
                avio?.close()
                throw e
            }
        }

        private fun mimeTypeFor(codecId: Int): String? = when (codecId) {
            avcodec.AV_CODEC_ID_FLAC -> MimeTypes.AUDIO_FLAC
            avcodec.AV_CODEC_ID_MP3 -> MimeTypes.AUDIO_MPEG
            avcodec.AV_CODEC_ID_AAC -> MimeTypes.AUDIO_AAC
            avcodec.AV_CODEC_ID_ALAC -> MimeTypes.AUDIO_ALAC
            avcodec.AV_CODEC_ID_OPUS -> MimeTypes.AUDIO_OPUS
            avcodec.AV_CODEC_ID_VORBIS -> MimeTypes.AUDIO_VORBIS
            avcodec.AV_CODEC_ID_EAC3 -> MimeTypes.AUDIO_E_AC3
            avcodec.AV_CODEC_ID_AC3 -> MimeTypes.AUDIO_AC3
            avcodec.AV_CODEC_ID_DTS -> MimeTypes.AUDIO_DTS
            avcodec.AV_CODEC_ID_TRUEHD -> MimeTypes.AUDIO_TRUEHD
            avcodec.AV_CODEC_ID_PCM_S16LE, avcodec.AV_CODEC_ID_PCM_S24LE, avcodec.AV_CODEC_ID_PCM_S32LE, avcodec.AV_CODEC_ID_PCM_F32LE -> MimeTypes.AUDIO_RAW
            else -> null
        }
    }
}
