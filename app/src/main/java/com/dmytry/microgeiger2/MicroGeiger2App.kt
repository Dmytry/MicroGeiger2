/*
    Copyright 2013 Dmytry Lavrov

    This file is part of MicroGeiger for Android.

    MicroGeiger is free software: you can redistribute it and/or modify it under the terms of the 
    GNU General Public License as published by the Free Software Foundation, either version 2 
    of the License, or (at your option) any later version.

    MicroGeiger is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; 
    without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. 
    See the GNU General Public License for more details.

    You should have received a copy of the GNU General Public License along with MicroGeiger. 
    If not, see http://www.gnu.org/licenses/.
*/
package com.dmytry.microgeiger2

import android.app.Application
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.media.*
import android.media.MediaRecorder.AudioSource
import android.os.Build
import android.os.SystemClock
import android.preference.PreferenceManager
import android.util.Log
import java.util.*
import kotlin.math.max

class MicroGeiger2App : Application() {
    @JvmField
    @Volatile
    var totalCount = 0
    val minClicksInQueue = 100

    // Minimum queue length in seconds
    val minSmoothingDurationSec = 0.5f
    val sampleRate = 44100

    @Volatile
    var totalSampleCount: Long = 0
    val logInterval = 6 * sampleRate /// logging interval in samples
    var countsLog = Vector<Int>()
    var logCountdown = 0
    var logIntervalClickCount = 0

    @JvmField
    @Volatile
    var changeCount = 0
    var started = false

    @JvmField
    var connected = false

    class IIRFilter {
        var runningAvg = 0f
        fun getValue(normalized_in: Float): Float {
            val v = normalized_in - runningAvg
            runningAvg = runningAvg * (1.0f - running_avg_const) + normalized_in * running_avg_const
            return v
        }
    }

    class Click {
        @JvmField
        var timeInSamples: Long = 0
    }

    @JvmField
    @Volatile
    var lastNClicks: Deque<Click> = LinkedList()
    fun appendClick(time_in_samples: Long) {
        val c = Click()
        c.timeInSamples = time_in_samples
        synchronized(lastNClicks) { lastNClicks.addLast(c) }
    }

    fun trimQueue(time_in_samples: Long) {
        val cutoff = time_in_samples - (minSmoothingDurationSec * sampleRate).toLong()
        synchronized(lastNClicks) {
            while (lastNClicks.size > minClicksInQueue && lastNClicks.first.timeInSamples < cutoff) {
                lastNClicks.removeFirst()
            }
        }
    }

    fun getQueueCPM(): Float {
        synchronized(lastNClicks) {
            if (lastNClicks.size == 0) return 0.0f
            val sc = totalSampleCount
            val duration_in_samples = sc - lastNClicks.first.timeInSamples
            if (duration_in_samples < 0) {
                lastNClicks.clear()
                return 0.0f
            }
            if (duration_in_samples == 0L) return 0.0f
            val count = lastNClicks.size.toLong()
            return count * sampleRate * 60.0f / duration_in_samples
        }
    }

    inner class Listener : Runnable, AudioRouting.OnRoutingChangedListener,
        OnSharedPreferenceChangeListener {
        @Volatile
        var doStop = false
        var filter = IIRFilter()
        var currentOffset = 0
        var inputBuffer = ShortArray(1024 * 1024)
        var playbackBuffer = ShortArray(8000)
        var recorder: AudioRecord? = null
        var player: AudioTrack? = null

        @JvmField
        @Volatile
        var deadTime = sampleRate / 2000

        @JvmField
        @Volatile
        var threshold = 0.1

        // High pass filter , peak is reached in 3 samples
        @Volatile
        var clickVolume = 1.0f
        var deadCountdown = 0
        var clickCountdown = 0
        var clickDuration = 40
        var clickBeepDivisor = 20
        var sampleCount = 0

        @Volatile
        var isPeripheral = false
        fun getFromBufferAt(in_i: Int): Short {
            var i = in_i
            i %= inputBuffer.size
            if (i < 0) i += inputBuffer.size
            return inputBuffer[i]
        }

        // Circular buffer reading
        // Returns how many bytes were read
        private fun readIntoBuffer(
            recorder: AudioRecord,
            wanted_read: Int,
            blocking: Boolean
        ): Int { // int read_size = recorder.read(input_buffer,0,data_size, AudioRecord.READ_NON_BLOCKING);
            // sanitize
            var wanted_read = wanted_read
            if (wanted_read > inputBuffer.size) wanted_read = inputBuffer.size
            var max_read_size = wanted_read
            // Are we wrapping around the buffer?
            val partial = max_read_size > inputBuffer.size - currentOffset
            if (partial) {
                max_read_size = inputBuffer.size - currentOffset
            }
            var bytes_read = recorder.read(
                inputBuffer,
                currentOffset,
                max_read_size,
                if (blocking) AudioRecord.READ_BLOCKING else AudioRecord.READ_NON_BLOCKING
            )
            var new_offset = currentOffset + bytes_read
            // Wraparound
            if (new_offset >= inputBuffer.size) {
                new_offset = 0
                if (partial) { // Wraparound and read was partial, need another read
                    bytes_read += recorder.read(
                        inputBuffer,
                        new_offset,
                        wanted_read - bytes_read,
                        if (blocking) AudioRecord.READ_BLOCKING else AudioRecord.READ_NON_BLOCKING
                    )
                }
            }
            return bytes_read
        }

        // Blocking read for min_read plus queue emptying up to max_read
        private fun readAtLeast(recorder: AudioRecord, min_read: Int, max_read: Int): Int {
            var result = readIntoBuffer(recorder, min_read, true)
            if (result < max_read) {
                result += readIntoBuffer(recorder, max_read - result, false)
            }
            return result
        }

        override fun run() {
            Log.d(TAG, "Run")
            val sharedPref = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            sharedPref.registerOnSharedPreferenceChangeListener(this)
            parametersFromConfig()
            val record_min_buffer_size = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_FRONT_LEFT or AudioFormat.CHANNEL_OUT_FRONT_RIGHT,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val play_min_buffer_size = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val min_buffer_size = Math.max(record_min_buffer_size, play_min_buffer_size)

            val data_size = max(sampleRate / 4, min_buffer_size)
            // Store about 160 seconds in circular buffer for the waveform viewer, power of 2 so that wraparound of sample numbers isn't a problem
            inputBuffer = ShortArray(Math.max(data_size, 1024 * 1024))
            currentOffset = 0
            totalSampleCount = 0
            lastNClicks.clear()
            playbackBuffer = ShortArray(data_size * 2)
            val recorder_buffer_size_bytes = 4 * max(sampleRate / 10, min_buffer_size)
            try {
                recorder = AudioRecord(
                    AudioSource.DEFAULT,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recorder_buffer_size_bytes
                )
                recorder?.addOnRoutingChangedListener(this, null)
            } catch (ex: SecurityException) {
                Log.d(TAG, "No audio permission")
                return
            }
            if (recorder == null) return
            player = AudioTrack(
                AudioManager.STREAM_RING,
                sampleRate,  /* AudioFormat.CHANNEL_OUT_MONO */
                AudioFormat.CHANNEL_OUT_FRONT_LEFT or AudioFormat.CHANNEL_OUT_FRONT_RIGHT,
                AudioFormat.ENCODING_PCM_16BIT,
                4 * data_size,
                AudioTrack.MODE_STREAM
            )
            if (player == null){
                recorder?.stop()
                recorder?.release()
                return
            }
            Log.d(TAG, "Output channels: " + player?.channelCount)
            player?.play()
            try {
                isPeripheral = checkIfPeripheralIsConnected()
                while (!doStop) {
                    //getParametersFromConfig();
                    if (recorder?.state == AudioRecord.STATE_INITIALIZED) { // check to see if the recorder has initialized yet.
                        if (recorder?.recordingState == AudioRecord.RECORDSTATE_STOPPED) {
                            recorder?.startRecording()
                        } else {
                            // This is slow for some reason, todo: use events instead
                            if (isPeripheral) {
                                if (!connected) changeCount++
                                connected = true
                                val start_t_ = SystemClock.elapsedRealtime()
                                //int read_size = recorder.read(input_buffer,0,data_size, AudioRecord.READ_NON_BLOCKING);

                                // read at least 0.1 seconds of audio
                                val read_size = readAtLeast(
                                    recorder!!,
                                    4410,
                                    inputBuffer.size
                                ) //readIntoBuffer(recorder, input_buffer.length);
                                val end_t_ = SystemClock.elapsedRealtime()
                                //Log.d(TAG, "Read: "+read_size+" duration="+(end_t_-start_t_)+" t="+end_t_);
                                val old_total_count = totalCount
                                processInputDataAndGenerateClicks(read_size, playbackBuffer)
                                trimQueue(totalSampleCount)
                                if (old_total_count != totalCount) {
                                    changeCount++
                                }
                                playOutputSounds(read_size)
                            } else { /// wired headset is not on
                                if (connected) changeCount++
                                connected = false
                                recorder?.stop()
                                recorder?.release()

                                Thread.sleep(500)
                                try {
                                    recorder = AudioRecord(
                                        AudioSource.DEFAULT,
                                        sampleRate,
                                        AudioFormat.CHANNEL_IN_MONO,
                                        AudioFormat.ENCODING_PCM_16BIT,
                                        recorder_buffer_size_bytes
                                    )
                                    recorder?.addOnRoutingChangedListener(this, null)
                                } catch (ex: SecurityException) {
                                    Log.d(TAG, "No audio permission")
                                    return
                                }
                                Thread.sleep(500)
                                isPeripheral = checkIfPeripheralIsConnected()
                            }
                        }
                    } else {
                        Log.d(TAG, "failed to initialize audio")
                        Thread.sleep(5000)
                    }
                }
            } catch (e: InterruptedException) {
            } finally {
                recorder?.stop()
                recorder?.release()
                player?.stop()
                player?.release()
            }
        }

        private fun playOutputSounds(read_size: Int) {
            if (clickVolume > 0.001) {
                val start_t = SystemClock.elapsedRealtime()
                var how_much_to_write = read_size * 2
                // hack to reduce amount of buffering
                // read was more than 1/10th of a second, skip some writing
                if (read_size > 4410) {
                    how_much_to_write -= 16
                }
                how_much_to_write -= 2
                var wrote_size = 0
                if (how_much_to_write > 0) {
                    wrote_size = player!!.write(
                        playbackBuffer,
                        0,
                        how_much_to_write
                    ) // , AudioTrack.WRITE_NON_BLOCKING
                }
                val end_t = SystemClock.elapsedRealtime()
                //Log.d(TAG, "Time to write: "+(end_t-start_t));
            }
        }

        private fun processInputDataAndGenerateClicks(read_size: Int, playback_data: ShortArray) {
            var i = 0
            var click_v =
                (Math.exp((clickVolume - 1.0) * Math.log(10000.0)) * 32767).toInt().toShort()
            while (i < read_size) {
                if (deadCountdown > 0) {
                    deadCountdown--
                }
                if (i * 2 + 1 < playback_data.size) {
                    if (clickCountdown > 0) {
                        clickCountdown--
                        playback_data[i * 2] = if (i % 2 == 0) click_v else 0
                        playback_data[i * 2 + 1] = playback_data[i * 2]
                    } else {
                        //playback_data[i]=0;
                        // test beep
                        //short beep=(short)((total_sample_count/40)%2 == 1 ? 1000:-1000);
                        val beep: Short = 0
                        playback_data[i * 2] = beep
                        playback_data[i * 2 + 1] = beep
                    }
                }
                val raw_v = inputBuffer[currentOffset] * (1.0f / 32768.0f)
                val v = filter.getValue(raw_v)
                if ( /* v>threshold || */v < -threshold) {
                    if (deadCountdown <= 0) {
                        totalCount++
                        sampleCount++
                        logIntervalClickCount++
                        deadCountdown = deadTime
                        if (clickCountdown <= 0) clickCountdown = clickDuration
                        appendClick(totalSampleCount)
                        trimQueue(totalSampleCount)
                    }
                }
                if (logCountdown <= 0) {
                    logCountdown = logInterval
                    countsLog.add(logIntervalClickCount)
                    logIntervalClickCount = 0
                }
                logCountdown--
                ++i
                ++currentOffset
                if (currentOffset >= inputBuffer.size) currentOffset = 0
                ++totalSampleCount
            }
        }

        override fun onRoutingChanged(audioRouting: AudioRouting) {
            Log.d(TAG, "Routing changed!")
            isPeripheral = checkIfPeripheralIsConnected()
        }

        private fun checkIfPeripheralIsConnected(): Boolean {
            if (recorder == null) return false
            var is_peripheral = false
            var checked_peripheral = false
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    val microphones = recorder?.activeMicrophones
                    if (microphones != null) {
                        for (m in microphones) {
                            val l = m.location
                            if (l == MicrophoneInfo.LOCATION_UNKNOWN || l == MicrophoneInfo.LOCATION_PERIPHERAL) {
                                is_peripheral = true
                            }
                        }
                    }
                    checked_peripheral = true
                }
            } catch (exception: Exception) {
                Log.d(TAG, "Failed to query connected microphones")
            }
            // if we were unable to determine if microphone is peripheral, fallback to wired headset check
            if (!checked_peripheral) {
                is_peripheral =
                    (applicationContext.getSystemService(AUDIO_SERVICE) as AudioManager).isWiredHeadsetOn
            }
            return is_peripheral
        }

        private fun parametersFromConfig() {
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            try {
                threshold = prefs.getString("threshold", "")!!.toDouble()
            } catch (e: NumberFormatException) {
            }
            try {
                deadTime =
                    (0.001 * sampleRate * prefs.getString("dead_time", "")!!.toDouble()).toInt()
            } catch (e: NumberFormatException) {
            }
            try {
                clickVolume = prefs.getInt("int_click_volume2", 100) / 100.0f
            } catch (e: NumberFormatException) {
            }
        }

        override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, s: String) {
            parametersFromConfig()
        }
    }

    @JvmField
    var listener: Listener? = null
    var listenerThread: Thread? = null

    fun start() {
        if (!started) {
            if (listenerThread == null) {
                listener = Listener()
                listenerThread = Thread(listener)
                listenerThread!!.start()
            }
            started = true
        }
    }

    fun reset() {
        totalCount = 0
        changeCount++
        lastNClicks.clear()
    }

    fun stop() {
        if (listener != null) {
            listener!!.doStop = true
            listenerThread!!.interrupt()
            try {
                listenerThread!!.join()
            } catch (e: InterruptedException) {
                // TODO Auto-generated catch block
                e.printStackTrace()
            }
            listenerThread = null
        }
        started = false
    }

    override fun onCreate() {
        super.onCreate()
        start()
    }

    override fun onTerminate() {
        super.onTerminate()
        stop()
    }

    companion object {
        private const val TAG = "MicroGeiger"
        const val running_avg_const = 0.25f
    }
}