package com.grlduarte.droidplay.decoder;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;
import android.view.Surface;

import com.github.serezhka.airplay.server.AirPlayConfig;

// examples:
// https://github.com/taehwandev/MediaCodecExample
// https://github.com/lucribas/AirplayServer/blob/master/app/src/main/java/com/fang/myapplication/player/VideoPlayer.java
public class VideoDecoder extends MediaCodec.Callback {
  private static String TAG = "VideoDecoder";

  private final String mimeType = MediaFormat.MIMETYPE_VIDEO_AVC;
  private final int videoWidth;
  private final int videoHeight;

  private MediaCodec decoder = null;
  private MediaFormat mediaFormat = null;
  private Surface surface;
  private List<DataPacket> listBuffer = Collections.synchronizedList(new ArrayList<DataPacket>());
  private boolean isRunning = false;

  public VideoDecoder(AirPlayConfig config) {
    videoWidth = config.getWidth();
    videoHeight = config.getHeight();
  }

  public void init(Surface surface) {
    this.surface = surface;

    boolean wasRunning = isRunning;
    if (decoder != null)
      release();

    try {
      decoder = MediaCodec.createDecoderByType(mimeType);
      mediaFormat = getMediaFormat();
      decoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
    } catch (Exception e) {
      Log.e(TAG, "Failed while initializing audio decoder", e);
    }

    if (wasRunning) 
      start();
  }

  public void addToBuffer(DataPacket packet) {
    listBuffer.add(packet);
  }

  public void start() {
    try {
      decoder.configure(mediaFormat, surface, null, 0);
      decoder.setCallback(this);
    } catch (IllegalStateException e) {
      // decoder already configured, just start it
    }

    isRunning = true;
    decoder.start();
  }

  public void stop() {
    isRunning = false;
    decoder.stop();
  }

  public void release() {
    stop();
    decoder.release();
    decoder = null;
  }

  private boolean decoderSupportsAndroidRLowLatency(MediaCodecInfo decoderInfo, String mimeType) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      try {
        if (decoderInfo.getCapabilitiesForType(mimeType).isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)) {
          return true;
        }
      } catch (Exception e) {
        // Tolerate buggy codecs
        e.printStackTrace();
      }
    }

    Log.d(TAG, "Low latency decoding mode NOT supported (FEATURE_LowLatency)");
    return false;
  }

  private MediaFormat getMediaFormat() {
    MediaFormat mediaFormat = MediaFormat.createVideoFormat(mimeType, videoWidth, videoHeight);
    MediaCodecInfo decoderInfo = decoder.getCodecInfo();
    Log.i(TAG, "DECODER SELECTED = " + decoderInfo.getName());

    if (decoderSupportsAndroidRLowLatency(decoderInfo, mediaFormat.getString(MediaFormat.KEY_MIME))) {
      Log.i(TAG, "Using low-latency mode");
      mediaFormat.setInteger("low-latency", 1);
    }

    return mediaFormat;
  }

  @Override
  public void onInputBufferAvailable(MediaCodec codec, int index) {
    while (listBuffer.size() == 0) {
      if (isRunning)
        continue;

      return;
    }

    try {
      byte[] packet = listBuffer.remove(0).data;
      ByteBuffer inputBuf = decoder.getInputBuffer(index);
      inputBuf.put(packet, 0, packet.length);
      decoder.queueInputBuffer(index, 0, packet.length, 0, 0);
    } catch (IllegalStateException e) {
      Log.e(TAG, "Not in executing state, skipping");
      return;
    }
  }

  @Override
  public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
    try {
      decoder.releaseOutputBuffer(index, true);
    } catch (IllegalStateException e) {
      Log.e(TAG, "Not in executing state, skipping");
      return;
    }
  }

  @Override
  public void onError(MediaCodec codec, MediaCodec.CodecException e) {
    Log.e(TAG, "Error while decoding packet", e);
  }

  @Override
  public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
    Log.d(TAG, "Output format changed: " + format.toString());
  }
}
