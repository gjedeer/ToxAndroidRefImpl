/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.AsyncTask;
import android.util.Log;

import java.nio.ByteBuffer;

import static com.zoffcc.applications.trifa.MainActivity.PREF__audiorec_asynctask;
import static com.zoffcc.applications.trifa.MainActivity.PREF__audiosource;
import static com.zoffcc.applications.trifa.MainActivity.PREF__min_audio_samplingrate_out;
import static com.zoffcc.applications.trifa.MainActivity.audio_manager_s;
import static com.zoffcc.applications.trifa.MainActivity.set_JNI_audio_buffer;
import static com.zoffcc.applications.trifa.MainActivity.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.MainActivity.toxav_audio_send_frame;

public class AudioRecording extends Thread
{
    static final String TAG = "trifa.AudioRecording";
    static boolean stopped = true;
    static boolean finished = true;

    // the audio recording options
    static int RECORDING_RATE = 48000; // 16000; // 44100;
    static final int CHANNEL = AudioFormat.CHANNEL_IN_MONO;
    static final int FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    static final int CHANNELS_TOX = 1;
    static long SMAPLINGRATE_TOX = 48000; // 16000;
    static boolean soft_echo_canceller_ready = false;

    private int buffer_size = 0;
    static int audio_session_id = -1;
    AutomaticGainControl agc = null;
    AcousticEchoCanceler aec = null;
    NoiseSuppressor np = null;

    // -----------------------
    private ByteBuffer _recBuffer = null;
    private byte[] _tempBufRec = null;
    // private int _bufferedRecSamples = 0;
    private int buffer_mem_factor = 30;
    private int buf_multiplier = 5;
    private boolean android_M_bug = false;
    // -----------------------

    /**
     * Give the thread high priority so that it's not canceled unexpectedly, and start it
     */
    public AudioRecording()
    {

        // AcousticEchoCanceler
        // AutomaticGainControl
        // LoudnessEnhancer

        try
        {
            // android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
            android.os.Process.setThreadPriority(Thread.MAX_PRIORITY);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        stopped = false;
        finished = false;

        audio_manager_s.setMicrophoneMute(false);
        audio_manager_s.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN);
        audio_manager_s.setMode(AudioManager.MODE_IN_COMMUNICATION);
        start();
    }

    @Override
    public void run()
    {
        Log.i(TAG, "Running Audio Thread [OUT]");
        AudioRecord recorder = null;

        try
        {
            int min_sampling_rate = -1;
            // try user set min freq first
            min_sampling_rate = getMinSupportedSampleRate(PREF__min_audio_samplingrate_out);
            Log.i(TAG, "Running Audio Thread [OUT]:try sampling rate:1:" + min_sampling_rate);
            if (min_sampling_rate == -1)
            {
                // ok, now try also with 8kHz
                min_sampling_rate = getMinSupportedSampleRate(8000);
                Log.i(TAG, "Running Audio Thread [OUT]:try sampling rate:2:" + min_sampling_rate);
            }

            if (min_sampling_rate != -1)
            {
                RECORDING_RATE = min_sampling_rate;
            }
            SMAPLINGRATE_TOX = RECORDING_RATE;
            Log.i(TAG, "Running Audio Thread [OUT]:using sampling rate:" + RECORDING_RATE + " kHz (min=" + min_sampling_rate + ")");

            /*
             * Initialize buffer to hold continuously recorded audio data, start recording
             */
            buffer_size = AudioRecord.getMinBufferSize(RECORDING_RATE, CHANNEL, FORMAT);

            int buffer_size_M = buffer_size;

            // ---------- 222 ----------
            int want_buf_size_in_bytes = (int) (2 * (RECORDING_RATE / buffer_mem_factor));
            Log.i(TAG, "want_buf_size_in_bytes(1)=" + want_buf_size_in_bytes);
            if (want_buf_size_in_bytes < buffer_size)
            {
                want_buf_size_in_bytes = buffer_size;
            }

            //            if (want_buf_size_in_bytes < 6000)
            //            {
            //                want_buf_size_in_bytes = 6550;
            //            }

            if (android_M_bug)
            {
                want_buf_size_in_bytes = buffer_size_M;
            }

            _recBuffer = ByteBuffer.allocateDirect((want_buf_size_in_bytes * buf_multiplier) + 1024); // Max 10 ms @ 48 kHz
            // _recBuffer = ByteBuffer.allocateDirect((want_buf_size_in_bytes)); // Max 10 ms @ 48 kHz
            _tempBufRec = new byte[want_buf_size_in_bytes];
            int recBufSize = buffer_size * buf_multiplier;

            if (android_M_bug)
            {
                recBufSize = buffer_size_M;
            }

            // _bufferedRecSamples = RECORDING_RATE / 200;
            // ---------- 222 ----------
            Log.i(TAG, "want_buf_size_in_bytes(2)=" + want_buf_size_in_bytes);
            Log.i(TAG, "getMinBufferSize buffer_size=" + buffer_size + " recBufSize=" + recBufSize);

            set_JNI_audio_buffer(_recBuffer);

            if (PREF__audiosource == 1)
            {
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RECORDING_RATE, CHANNEL, FORMAT, recBufSize);
            }
            else
            {
                recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RECORDING_RATE, CHANNEL, FORMAT, recBufSize);
            }
            // recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, RECORDING_RATE, CHANNEL, FORMAT, recBufSize);
            audio_session_id = recorder.getAudioSessionId();

            Log.i(TAG, "Audio Thread [OUT]:AutomaticGainControl:===============================");
            agc = null;
            try
            {
                Log.i(TAG, "Audio Thread [OUT]:AutomaticGainControl:isAvailable:" + AutomaticGainControl.isAvailable());
                agc = AutomaticGainControl.create(audio_session_id);
                int res = agc.setEnabled(true);
                Log.i(TAG, "Audio Thread [OUT]:AutomaticGainControl:setEnabled:" + res + " audio_session_id=" + audio_session_id);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "Audio Thread [OUT]:EE1:" + e.getMessage());
            }
            Log.i(TAG, "Audio Thread [OUT]:AutomaticGainControl:===============================");

            Log.i(TAG, "Audio Thread [OUT]:AcousticEchoCanceler:===============================");
            aec = null;
            try
            {
                Log.i(TAG, "Audio Thread [OUT]:AcousticEchoCanceler:isAvailable:" + AcousticEchoCanceler.isAvailable());
                aec = AcousticEchoCanceler.create(audio_session_id);
                int res = aec.setEnabled(true);
                Log.i(TAG, "Audio Thread [OUT]:AcousticEchoCanceler:setEnabled:" + res + " audio_session_id=" + audio_session_id);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "Audio Thread [OUT]:EE2:" + e.getMessage());
            }
            Log.i(TAG, "Audio Thread [OUT]:AcousticEchoCanceler:===============================");


            Log.i(TAG, "Audio Thread [OUT]:NoiseSuppressor:===============================");
            aec = null;
            try
            {
                Log.i(TAG, "Audio Thread [OUT]:NoiseSuppressor:isAvailable:" + NoiseSuppressor.isAvailable());
                np = NoiseSuppressor.create(audio_session_id);
                int res = np.setEnabled(true);
                Log.i(TAG, "Audio Thread [OUT]:NoiseSuppressor:setEnabled:" + res + " audio_session_id=" + audio_session_id);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "Audio Thread [OUT]:EE2:" + e.getMessage());
            }
            Log.i(TAG, "Audio Thread [OUT]:NoiseSuppressor:===============================");

            recorder.startRecording();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        /*
         * Loops until something outside of this thread stops it.
         * Reads the data from the recorder and writes it to the audio track for playback.
         */

        int readBytes = 0;
        int audio_send_res2 = 0;
        while (!stopped)
        {
            try
            {
                // only send audio frame if call has started
                // Log.i(TAG, "Callstate.tox_call_state=" + Callstate.tox_call_state);
                if (!((Callstate.tox_call_state == 0) || (Callstate.tox_call_state == 1) || (Callstate.tox_call_state == 2)))
                {
                    if (Callstate.my_audio_enabled == 1)
                    {
                        _recBuffer.rewind();
                        readBytes = recorder.read(_tempBufRec, 0, _tempBufRec.length);

                        //  Log.i(TAG, "audio buffer:" + "readBytes=" + readBytes + " _tempBufRec.length=" + _tempBufRec.length + " buffer_size=" + buffer_size);

                        if (readBytes != _tempBufRec.length)
                        {
                            Log.i(TAG, "audio buffer:" + "ERROR:readBytes != _tempBufRec.length");
                        }

                        if (PREF__audiorec_asynctask)
                        {
                            new send_audio_frame_to_toxcore(readBytes).execute();
                        }
                        else
                        {
                            try
                            {
                                _recBuffer.put(_tempBufRec);
                                audio_send_res2 = toxav_audio_send_frame(tox_friend_by_public_key__wrapper(Callstate.friend_pubkey), (long) (readBytes / 2), CHANNELS_TOX, SMAPLINGRATE_TOX);
                                if (audio_send_res2 != 0)
                                {
                                    Log.i(TAG, "audio:res=" + audio_send_res2 + ":" + ToxVars.TOXAV_ERR_SEND_FRAME.value_str(audio_send_res2));
                                }
                            }
                            catch (Exception e)
                            {
                                e.printStackTrace();
                                Log.i(TAG, "audio:EE8:" + e.getMessage());
                                Log.i(TAG, "audio:EE9:" + _recBuffer.limit() + " <-> " + _tempBufRec.length + " <-> " + readBytes);
                            }
                        }
                    }
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "Audio Thread [OUT]:EE3:" + e.getMessage());
            }
        }

        try
        {
            agc.release();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "Audio Thread [OUT]:EE4:" + e.getMessage());
        }

        try
        {
            aec.release();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "Audio Thread [OUT]:EE5:" + e.getMessage());
        }

        try
        {
            recorder.stop();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        recorder.release();

        finished = true;

        Log.i(TAG, "Audio Thread [OUT]:finished");
    }

    public static void close()
    {
        stopped = true;
    }

    private class send_audio_frame_to_toxcore extends AsyncTask<Void, Void, String>
    {
        int audio_send_res = 0;
        int readBytes_ = 1;

        send_audio_frame_to_toxcore(int readBytes)
        {
            readBytes_ = readBytes;
        }

        @Override
        protected String doInBackground(Void... voids)
        {
            try
            {
                _recBuffer.put(_tempBufRec);
                audio_send_res = toxav_audio_send_frame(tox_friend_by_public_key__wrapper(Callstate.friend_pubkey), (long) (readBytes_ / 2), CHANNELS_TOX, SMAPLINGRATE_TOX);
                if (audio_send_res != 0)
                {
                    Log.i(TAG, "audio:res=" + audio_send_res + ":" + ToxVars.TOXAV_ERR_SEND_FRAME.value_str(audio_send_res));
                }
            }
            catch (Exception e)
            {
                e.printStackTrace();
                Log.i(TAG, "audio:EE6:" + e.getMessage());
                Log.i(TAG, "audio:EE7:" + _recBuffer.limit() + " <-> " + _tempBufRec.length + " <-> " + readBytes_);
            }

            return null;
        }

        @Override
        protected void onPostExecute(String result)
        {
        }

        @Override
        protected void onPreExecute()
        {
        }
    }

    /*
     * thanks to: http://stackoverflow.com/questions/8043387/android-audiorecord-supported-sampling-rates
     */
    int getMinSupportedSampleRate(int min_rate)
    {
    /*
     * Valid Audio Sample rates
     *
     * @see <a
     * href="http://en.wikipedia.org/wiki/Sampling_%28signal_processing%29"
     * >Wikipedia</a>
     */
        int validSampleRates[];
        validSampleRates = new int[]{8000, 16000, 22050,
                //
                32000, 37800, 44056, 44100, 47250, 48000, 50000, 50400,
                //
                88200, 96000, 176400, 192000, 352800, 2822400, 5644800};
    /*
     * Selecting default audio input source for recording since
     * AudioFormat.CHANNEL_CONFIGURATION_DEFAULT is deprecated and selecting
     * default encoding format.
     */
        for (int i = 0; i < validSampleRates.length; i++)
        {
            if (validSampleRates[i] >= min_rate)
            {
                int result = AudioRecord.getMinBufferSize(validSampleRates[i], CHANNEL, FORMAT);
                if (result != AudioRecord.ERROR && result != AudioRecord.ERROR_BAD_VALUE && result > 0)
                {
                    // return the mininum supported audio sample rate
                    return validSampleRates[i];
                }
            }
        }
        // If none of the sample rates are supported return -1 handle it in
        // calling method
        return -1;
    }
}