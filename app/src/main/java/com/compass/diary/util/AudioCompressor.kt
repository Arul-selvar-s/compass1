package com.compass.diary.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object AudioCompressor {

    private const val BIT_RATE = 64_000
    private const val SAMPLE_RATE = 44_100
    private const val CHANNEL_COUNT = 1
    private const val TIMEOUT_US = 10_000L

    suspend fun compressToAac(context: Context, sourceUri: Uri, outputFile: File): String? =
        withContext(Dispatchers.IO) {
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            var encoder: MediaCodec? = null
            var muxer: MediaMuxer? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(context, sourceUri, null)

                var trackIndex = -1
                var inputFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        inputFormat = format
                        break
                    }
                }
                if (trackIndex == -1 || inputFormat == null) return@withContext "No audio track found in this file"
                extractor.selectTrack(trackIndex)

                val inputMime = inputFormat.getString(MediaFormat.KEY_MIME)!!
                val isRawPcm = inputMime == MediaFormat.MIMETYPE_AUDIO_RAW

                if (!isRawPcm) {
                    decoder = MediaCodec.createDecoderByType(inputMime)
                    decoder.configure(inputFormat, null, null, 0)
                    decoder.start()
                }

                val outputFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT
                ).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                }
                encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder.start()

                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                var muxerTrackIndex = -1
                var muxerStarted = false

                val decInfo = MediaCodec.BufferInfo()
                val encInfo = MediaCodec.BufferInfo()
                var extractorDone = false
                var decoderDone = isRawPcm
                var encoderDone = false

                if (isRawPcm) {
                    while (!extractorDone) {
                        val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (encInIndex >= 0) {
                            val encInBuffer = encoder.getInputBuffer(encInIndex)!!
                            val sampleSize = extractor.readSampleData(encInBuffer, 0)
                            if (sampleSize < 0) {
                                encoder.queueInputBuffer(encInIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                encoder.queueInputBuffer(encInIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                        drainEncoder(encoder, encInfo, muxer, muxerTrackIndex, muxerStarted) { newIndex, started ->
                            muxerTrackIndex = newIndex; muxerStarted = started
                        }.let { if (it) encoderDone = true }
                    }
                    while (!encoderDone) {
                        encoderDone = drainEncoder(encoder, encInfo, muxer, muxerTrackIndex, muxerStarted) { newIndex, started ->
                            muxerTrackIndex = newIndex; muxerStarted = started
                        }
                    }
                } else {
                    while (!encoderDone) {
                        if (!extractorDone) {
                            val inIndex = decoder!!.dequeueInputBuffer(TIMEOUT_US)
                            if (inIndex >= 0) {
                                val inputBuffer = decoder.getInputBuffer(inIndex)!!
                                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                                if (sampleSize < 0) {
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    extractorDone = true
                                } else {
                                    decoder.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                    extractor.advance()
                                }
                            }
                        }

                        if (!decoderDone) {
                            val outIndex = decoder!!.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                            if (outIndex >= 0) {
                                val decodedBuffer = decoder.getOutputBuffer(outIndex)
                                if (decInfo.size > 0 && decodedBuffer != null) {
                                    decodedBuffer.position(decInfo.offset)
                                    decodedBuffer.limit(decInfo.offset + decInfo.size)

                                    while (decodedBuffer.hasRemaining()) {
                                        val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                                        if (encInIndex < 0) break
                                        val encInBuffer = encoder.getInputBuffer(encInIndex)!!
                                        encInBuffer.clear()
                                        val toCopy = minOf(decodedBuffer.remaining(), encInBuffer.remaining())
                                        val slice = decodedBuffer.slice()
                                        slice.limit(toCopy)
                                        encInBuffer.put(slice)
                                        decodedBuffer.position(decodedBuffer.position() + toCopy)
                                        encoder.queueInputBuffer(encInIndex, 0, toCopy, decInfo.presentationTimeUs, 0)
                                    }
                                }
                                val isEos = (decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                                decoder.releaseOutputBuffer(outIndex, false)
                                if (isEos) {
                                    decoderDone = true
                                    val encInIndex = encoder.dequeueInputBuffer(TIMEOUT_US)
                                    if (encInIndex >= 0) {
                                        encoder.queueInputBuffer(encInIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    }
                                }
                            }
                        }

                        encoderDone = drainEncoder(encoder, encInfo, muxer, muxerTrackIndex, muxerStarted) { newIndex, started ->
                            muxerTrackIndex = newIndex; muxerStarted = started
                        }
                    }
                }

                if (!muxerStarted) return@withContext "Encoder never produced output — unsupported audio format"
                null
            } catch (e: Exception) {
                "${e.javaClass.simpleName}: ${e.message}"
            } finally {
                try { decoder?.stop() } catch (e: Exception) {}
                try { decoder?.release() } catch (e: Exception) {}
                try { encoder?.stop() } catch (e: Exception) {}
                try { encoder?.release() } catch (e: Exception) {}
                try { muxer?.stop() } catch (e: Exception) {}
                try { muxer?.release() } catch (e: Exception) {}
                try { extractor?.release() } catch (e: Exception) {}
            }
        }

    private fun drainEncoder(
        encoder: MediaCodec,
        encInfo: MediaCodec.BufferInfo,
        muxer: MediaMuxer,
        currentTrackIndex: Int,
        currentStarted: Boolean,
        onMuxerState: (Int, Boolean) -> Unit
    ): Boolean {
        var trackIndex = currentTrackIndex
        var started = currentStarted
        while (true) {
            val encOutIndex = encoder.dequeueOutputBuffer(encInfo, 0)
            when {
                encOutIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                encOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    started = true
                    onMuxerState(trackIndex, started)
                }
                encOutIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(encOutIndex)!!
                    if ((encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) encInfo.size = 0
                    if (encInfo.size > 0 && started) {
                        encodedData.position(encInfo.offset)
                        encodedData.limit(encInfo.offset + encInfo.size)
                        muxer.writeSampleData(trackIndex, encodedData, encInfo)
                    }
                    val isEos = (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    encoder.releaseOutputBuffer(encOutIndex, false)
                    if (isEos) return true
                }
                else -> return false
            }
        }
    }
}
