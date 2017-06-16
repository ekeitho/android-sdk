/**
 * Copyright IBM Corporation 2016
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package com.ibm.watson.developer_cloud.android.library.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;
import com.ibm.watson.developer_cloud.android.library.audio.opus.OggOpusEnc;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Dedicated thread for capturing raw audio data from the microphone. Captured data is passed to
 * an {@link AudioConsumer}. To begin capturing data, call {@link #start()}.  Ensure {@link #end()}
 * is called to stop this thread from running and to clean up its resources appropriately.
 */
final class MicrophoneCaptureThread extends Thread {
  private static final String TAG = MicrophoneCaptureThread.class.getName();
  private static final int SAMPLE_RATE = 16000;

  private boolean opusEncoded;
  private OggOpusEnc encoder;

  private final AudioConsumer consumer;
  private boolean stop;
  private boolean stopped;

  /**
   * This only initializes data associated with the thread. To start recording microphone data,
   * call {@link #start()}. Ensure that there is a corresponding call to {@link #end()} when
   * finished recording data.
   *
   * @param consumer Delegate for consuming audio data from the microphone.
   */
  public MicrophoneCaptureThread(AudioConsumer consumer, boolean opusEncoded) {
    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
    this.consumer = consumer;
    this.opusEncoded = opusEncoded;
  }

  @Override public void run() {
    int bufferSize = Math.max(SAMPLE_RATE / 2, AudioRecord.getMinBufferSize(SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
    short[] buffer = new short[bufferSize]; // use short to hold 16-bit PCM encoding

    AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
    record.startRecording();

    if (opusEncoded) {
      try {
        encoder = new OggOpusEnc(consumer);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }

    while (!stop) {

      /*

      public int read(@NonNull short[] audioData, int offsetInShorts, int sizeInShorts,
        @ReadMode int readMode) {
        if (mState != STATE_INITIALIZED || mAudioFormat == AudioFormat.ENCODING_PCM_FLOAT) {
            return ERROR_INVALID_OPERATION;
        }

        if ((readMode != READ_BLOCKING) && (readMode != READ_NON_BLOCKING)) {
            Log.e(TAG, "AudioRecord.read() called with invalid blocking mode");
            return ERROR_BAD_VALUE;
        }

        if ( (audioData == null) || (offsetInShorts < 0 ) || (sizeInShorts < 0)
                || (offsetInShorts + sizeInShorts < 0)  // detect integer overflow
                || (offsetInShorts + sizeInShorts > audioData.length)) {
            return ERROR_BAD_VALUE;
        }

        return native_read_in_short_array(audioData, offsetInShorts, sizeInShorts,
                readMode == READ_BLOCKING);
    }

       */

      int r = record.read(buffer, 0, buffer.length);

      if (AudioRecord.ERROR_INVALID_OPERATION == r) {
          // if the object wasn't properly initialized
          Log.e(TAG, "ERROR: ERROR_INVALID_OPERATION - Denotes a failure due to the improper use of a method.");
          stop = true;
      } else if (AudioRecord.ERROR_BAD_VALUE == r) {
        // if the parameters don't resolve to valid data and indexes
        Log.e(TAG, "ERROR: ERROR_BAD_VALUE - Denotes a failure due to the use of an invalid value.");
        Log.e(TAG, "Buffer Length: " + buffer.length);
        stop = true;
      } else if (AudioRecord.ERROR == r) {
        Log.e(TAG, "ERROR: ERROR - Denotes a generic operation failure.");
        stop = true;
      } else {
        // calculate amplitude and volume
        long v = 0;
        for (int i = 0; i < r; i++) {
          v += buffer[i] * buffer[i];
        }

        double amplitude = v / (double) r;
        double volume = 0;
        if (amplitude > 0) {
          volume = 10 * Math.log10(amplitude);
        }

        // convert short buffer to bytes
        ByteBuffer bufferBytes = ByteBuffer.allocate(r * 2); // 2 bytes per short
        bufferBytes.order(ByteOrder.LITTLE_ENDIAN); // save little-endian byte from short buffer
        bufferBytes.asShortBuffer().put(buffer, 0, r);
        byte[] bytes = bufferBytes.array();

        if (opusEncoded) {
          try {
            encoder.onStart(); //must be called before writing
            encoder.encodeAndWrite(bytes);
          } catch (Exception e) {
            e.printStackTrace();
          }
        } else {
          consumer.consume(bytes, amplitude, volume);
        }
      }

    }

    if (encoder != null) {
      encoder.close();
    }
    record.stop();
    record.release();
    stopped = true;
  }

  /**
   * Gracefully stops recording microphone data. Make sure this is called when data no longer needs
   * to be collected to ensure this thread and its resources are properly cleaned up.
   */
  public void end() {
    stop = true;

    while (!stopped) {
      try {
        Thread.sleep(10);
      } catch (InterruptedException e) {
        Log.e(TAG, e.getMessage());
      }
    }
  }
}
