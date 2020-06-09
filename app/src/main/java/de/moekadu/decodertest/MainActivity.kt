package de.moekadu.decodertest

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.SystemClock
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.widget.Button
import android.widget.TextView
import java.nio.ByteOrder
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    var text : TextView? = null
    var isound = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sounds = intArrayOf(R.raw.base48, R.raw.snare48, R.raw.sticks48, R.raw. woodblock_high48,
                R.raw.claves48, R.raw.hihat48, R.raw.mute48)

        text = findViewById(R.id.text)

        text?.movementMethod = ScrollingMovementMethod()

        val button = findViewById<Button>(R.id.button)

        button.setOnClickListener {
            if(isound == sounds.size)
                isound = 0
            val textViewText = StringBuilder()
            textViewText.append("Decoding sound $isound \n")
            audioToPCM(sounds[isound], textViewText)
            textViewText.append("\n")

            text?.text = textViewText.toString()
            text?.invalidate()
            ++isound
        }
    }

    fun audioToPCM(id : Int, textViewText : StringBuilder) : FloatArray {

        val decodingStart = SystemClock.uptimeMillis()

        val sampleFD = resources.openRawResourceFd(id)
        val mediaExtractor = MediaExtractor()

        mediaExtractor.setDataSource(sampleFD.fileDescriptor, sampleFD.startOffset, sampleFD.length)
        val format = mediaExtractor.getTrackFormat(0)

        val mime = format.getString(MediaFormat.KEY_MIME)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val duration = format.getLong(MediaFormat.KEY_DURATION)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val mediaCodecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val mediaCodecName = mediaCodecList.findDecoderForFormat(format)

        val nFrames = (ceil((duration * sampleRate).toDouble() / 1000000.0)).toInt()
        val nFramesDouble = duration.toDouble() * sampleRate / 1000000.0
        textViewText.append("sample rate = $sampleRate\n")
                .append("mime = $mime\n")
                .append("duration = $duration us\n")
                .append("channel count = $channelCount\n")
                .append("codec name = $mediaCodecName\n")
                .append("expected frame number = $nFrames, (floating point = $nFramesDouble)\n")
        // Log.v("AudioMixer", "AudioEncoder.decode: MIME TYPE: $mime")
        // Log.v("AudioMixer", "AudioEncoder.decode: duration $duration")
        // Log.v("AudioMixer", "AudioEncoder.decode: sampleRate = $sampleRate")
        // Log.v("AudioMixer", "AudioEncoder.decode: channel count $channelCount")
        // Log.v("AudioMixer", "AudioEncoder.decode: track count: " + mediaExtractor.trackCount)
        // Log.v("AudioMixer", "AudioEncoder.decode: media codec name: $mediaCodecName")

        if (nFrames > 50000)
            return floatArrayOf(0f)

        val result = FloatArray(2 * nFrames) { 0f }
        // Log.v("AudioMixer", "AudioEncoder.decode: result.size = " + result.size)

        //val codec = MediaCodec.createByCodecName(mediaCodecName)
        if(mime == null) {
            textViewText.append("no mime\n")
            return floatArrayOf(0f)
        }

        val codec = MediaCodec.createDecoderByType(mime)
        textViewText.append("codecs name based on mime: ${codec.name}\n")
        codec.configure(format, null, null, 0)

        codec.start()

        mediaExtractor.selectTrack(0)
        var numSamples = 0
        val bufferInfo = MediaCodec.BufferInfo()
        val timeOutUs = 1000L
        var sawOutputEOS = false
        var sawInputEOS = false
        var numNoOutput = 0
        var counter = 0

        while (!sawOutputEOS) {

            if(!sawInputEOS) {
                // get input buffer index and then the input buffer itself
                Log.v("DecoderTest", "dequeInputBuffer")
                val inputBufferIndex = codec.dequeueInputBuffer(timeOutUs)
                Log.v("DecoderTest", "Done")
                if (inputBufferIndex >= 0) {
                    Log.v("DecoderTest", "getInputBuffer")
                    val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                    Log.v("DecoderTest", "Done")
                    if (inputBuffer == null) {
                        textViewText.append("AudioEncoder.decode: failed to acquire input buffer\n")
                        Log.v("DecoderTest", "failed to acquire input buffer")
                        return floatArrayOf(0f)
                    }

                    // write the next bunch of data from our media file to the input buffer
                    Log.v("DecoderTest", "read sample data")
                    var sampleSize = mediaExtractor.readSampleData(inputBuffer, 0)
                    Log.v("DecoderTest", "Done")

                    var presentationTimeUs = 0L
                    var eosFlag = 0

                    if (sampleSize < 0) {
                        sawInputEOS = true
                        eosFlag = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        sampleSize = 0
                    } else {
                        presentationTimeUs = mediaExtractor.sampleTime
                    }

                    // queue the input buffer such that the codec can decode it
                    Log.v("DecoderTest", "queue input buffer")
                    codec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        sampleSize,
                        presentationTimeUs,
                        eosFlag
                    )
                    Log.v("DecoderTest", "Done")

                    if (!sawInputEOS) {
                        Log.v("DecoderTest", "advance media extractor")
                        mediaExtractor.advance()
                        Log.v("DecoderTest", "Done")
                    }
                }
            }

            // we are done decoding and can now read our result
            Log.v("DecoderTest", "deque output buffer")
            val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, timeOutUs)
            Log.v("DecoderTest", "Done")

            if(outputBufferIndex >= 0) {
                // finally get our output data and create a view to a short buffer which is the
                // standard data type for 16bit audio
                Log.v("DecoderTest", "get output buffer")
                val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                Log.v("DecoderTest", "Done")

                if (outputBuffer == null) {
                    textViewText.append("Cannot acquire output buffer\n")
                    return floatArrayOf(0f)
                }

                Log.v("DecoderTest", "create short buffer")
                val shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
                Log.v("DecoderTest", "Done")

                var c2 = 0
                // convert the short data to floats and store it to the result-array which will be
                // returned later. We want to have mono output stream, so we add different channel
                // to the same index.
                Log.v("DecoderTest", "read short buffer from ${shortBuffer.position()} to ${shortBuffer.limit()}")
                while (shortBuffer.position() < shortBuffer.limit()) {
                    if(numSamples/channelCount >= result.size) {
                        textViewText.append("Too many samples, something is wrong with track duration")
                        Log.v("DecoderTest", "Too many samples, something is wrong with track duration")
                        return result
                    }
                    result[numSamples / channelCount] += shortBuffer.get().toFloat()
                    ++numSamples
                    ++c2
                   if(c2 > 50000) {
                       textViewText.append("c2 too big, something is wrong")
                       Log.v("DecoderTest", "c2 too big, something is wrong")
                       return result
                   }

                }

                Log.v("DecoderTest", "release output buffer")
                codec.releaseOutputBuffer(outputBufferIndex, false)
                Log.v("DecoderTest", "Done")

                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                    sawOutputEOS = true
                numNoOutput = 0
            }
            else {
                ++numNoOutput
                if(numNoOutput > 50) {
                    textViewText.append("it seems as I don't get output data from codec\n")
                    Log.v("DecoderTest", "it seems as I don't get output data from codec")
                    break
                }
            }


            counter++
            if(counter > 50) {
                textViewText.append("stopping decoding early since something seems wrong\n")
                Log.v("DecoderTest", "stopping decoding early since something seems wrong")
                break
            }
        }
        Log.v("DecoderTest", "COUNTER: $counter")
        val decodingEnd = SystemClock.uptimeMillis()
        textViewText.append("decoded frames (total)= $numSamples\n")
        textViewText.append("decoded frames (per channel)= ${numSamples/channelCount}\n")
        textViewText.append("time for decoding = ${decodingEnd-decodingStart} ms\n")

        mediaExtractor.release()
        codec.stop()
        codec.release()
        sampleFD.close()

        Log.v("DecoderTest", "scaling result")
        val channelCountInv = 1.0f / channelCount
        for (i in result.indices)
            result[i] = channelCountInv * result[i] / 32768.0f //peak * peakValue
        // val peak = result.max() ?: 0f
        // Log.v("AudioMixer", "AudioEncoder.decode: peak value = $peak")
        return result
    }

}
