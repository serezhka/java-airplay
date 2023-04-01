package com.grlduarte.droidplay;

import android.util.Log;
import android.view.Surface;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConfig;
import com.github.serezhka.airplay.server.AirPlayConsumer;

import com.grlduarte.droidplay.decoder.DataPacket;
import com.grlduarte.droidplay.decoder.VideoDecoder;

public class StreamConsumer implements AirPlayConsumer {
  private static String TAG = "StreamConsumer";

  private VideoDecoder videoDecoder;

  public StreamConsumer(AirPlayConfig config) {
    videoDecoder = new VideoDecoder(config);
  }

  public void init(Surface surface) {
    videoDecoder.init(surface);
  }

  public void stop() {
    videoDecoder.release();
  }

  @Override
  public void onVideoFormat(VideoStreamInfo videoStreamInfo) {
    videoDecoder.start();
  }

  @Override
  public void onVideo(byte[] packet) {
    videoDecoder.addToBuffer(new DataPacket(packet));
  }

  @Override
  public void onVideoSrcDisconnect() {
    videoDecoder.stop();
  }

  @Override
  public void onAudioFormat(AudioStreamInfo audioStreamInfo) { }

  @Override
  public void onAudio(byte[] packet) { }

  @Override
  public void onAudioSrcDisconnect() { }
}
