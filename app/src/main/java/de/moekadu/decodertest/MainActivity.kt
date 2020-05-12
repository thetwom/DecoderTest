package de.moekadu.decodertest

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import java.nio.ByteOrder
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {

    var text : TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val sounds = intArrayOf(R.raw.base48, R.raw.snare48, R.raw.sticks48, R.raw. woodblock_high48,
                R.raw.claves48, R.raw.hihat48, R.raw.mute48)

        text = findViewById(R.id.text)

        text?.movementMethod = ScrollingMovementMethod()

        val textViewText= StringBuilder()

        for(s in sounds) {
            audioToPCM(s, textViewText)
            textViewText.append("\n")
        }

        text?.text = textViewText.toString()
        text?.invalidate()
    }

    fun audioToPCM(id : Int, textViewText : StringBuilder) : FloatArray {

        val sampleFD = resources.openRawResourceFd(id)
        val mediaExtractor = MediaExtractor()
        mediaExtractor.setDataSource(sampleFD.fileDescriptor, sampleFD.startOffset, sampleFD.length)
        val format = mediaExtractor.getTrackFormat(0)

//    val mime = format.getString(MediaFormat.KEY_MIME)
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val duration = format.getLong(MediaFormat.KEY_DURATION)
        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mediaCodecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val mediaCodecName = mediaCodecList.findDecoderForFormat(format)

        val nFrames = (ceil((duration * sampleRate).toDouble() / 1000000.0)).toInt()
        val nFramesDouble = duration.toDouble() * sampleRate / 1000000.0
        textViewText.append("sample rate = $sampleRate\n")
                .append("duration = $duration\n")
                .append("channel count = $channelCount\n")
                .append("codec name = $mediaCodecName\n")
                .append("expected frame number = $nFrames, (floating point = $nFramesDouble)\n")
        // Log.v("AudioMixer", "AudioEncoder.decode: MIME TYPE: $mime")
        // Log.v("AudioMixer", "AudioEncoder.decode: duration $duration")
        // Log.v("AudioMixer", "AudioEncoder.decode: sampleRate = $sampleRate")
        // Log.v("AudioMixer", "AudioEncoder.decode: channel count $channelCount")
        // Log.v("AudioMixer", "AudioEncoder.decode: track count: " + mediaExtractor.trackCount)
        // Log.v("AudioMixer", "AudioEncoder.decode: media codec name: $mediaCodecName")

        val result = FloatArray(10 * nFrames) { 0f }
        // Log.v("AudioMixer", "AudioEncoder.decode: result.size = " + result.size)

        val codec = MediaCodec.createByCodecName(mediaCodecName)

        codec.configure(format, null, null, 0)
        codec.start()

        mediaExtractor.selectTrack(0)
        var numSamples = 0
        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {

            // get input buffer index and then the input buffer itself
            val inputBufferIndex = codec.dequeueInputBuffer(5000000)
            if (inputBufferIndex < 0) {
                textViewText.append("AudioEncoder.decode: failed to get input buffer index\n")
                return floatArrayOf(0f)
            }

            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
            if(inputBuffer == null) {
                textViewText.append("AudioEncoder.decode: failed to acquire input buffer\n")
                return floatArrayOf(0f)
            }

            // write the next bunch of data from our media file to the input buffer
            val sampleSize = mediaExtractor.readSampleData(inputBuffer, 0)

            // queue the input buffer such that the codec can decode it
            if (sampleSize < 0) {
                codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                codec.queueInputBuffer(inputBufferIndex, 0, sampleSize, mediaExtractor.sampleTime, 0)
                mediaExtractor.advance()
            }

            // we are done decoding and can now read our result
            var outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 5000000)

            // sometimes this output format changed appears, then we have to try again to get
            // the output buffer index again
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Log.v("AudioMixer", "AudioEncoder.decode: output format changed")
                outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 5000000)
            }

            // if something fails ....
            if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // Log.v("AudioMixer", "AudioEncoder.decode: output format changed")
                textViewText.append("Cannot acquire valid output buffer index\n")
                return floatArrayOf(0f)
            } else if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // Log.v("AudioMixer", "AudioEncoder.decode: try again later")
                textViewText.append("Cannot acquire valid output buffer index\n")
                return floatArrayOf(0f)
            }

            // finally get our output data and create a view to a short buffer which is the
            // standard data type for 16bit audio
            val outputBuffer = codec.getOutputBuffer(outputBufferIndex)

            if (outputBuffer == null) {
                textViewText.append("Cannot acquire output buffer\n")
                return floatArrayOf(0f)
            }

            val shortBuffer = outputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()

            // convert the short data to floats and store it to the result-array which will be
            // returned later. We want to have mono output stream, so we add different channel
            // to the same index.
            while (shortBuffer.position() < shortBuffer.limit()) {
                if(numSamples/channelCount >= result.size) {
                    textViewText.append("Too many samples, something is wrong with track duration")
                    return result
                }
                result[numSamples / channelCount] += shortBuffer.get().toFloat()
                ++numSamples
            }

            codec.releaseOutputBuffer(outputBufferIndex, false)
            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
        }

        textViewText.append("decoded frames (total)= $numSamples\n")
        textViewText.append("decoded frames (per channel)= ${numSamples/channelCount}\n")
        mediaExtractor.release()
        codec.release()

        val channelCountInv = 1.0f / channelCount
        for (i in result.indices)
            result[i] = channelCountInv * result[i] / 32768.0f //peak * peakValue
        // val peak = result.max() ?: 0f
        // Log.v("AudioMixer", "AudioEncoder.decode: peak value = $peak")
        return result
    }

}
