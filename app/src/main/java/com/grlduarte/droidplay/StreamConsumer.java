package com.grlduarte.droidplay;

import com.github.serezhka.airplay.lib.AudioStreamInfo;
import com.github.serezhka.airplay.lib.VideoStreamInfo;
import com.github.serezhka.airplay.server.AirPlayConsumer;

public class StreamConsumer implements AirPlayConsumer {
  @Override
  public void onVideoFormat(VideoStreamInfo videoStreamInfo) { }

  @Override
  public void onVideo(byte[] packet) { }

  @Override
  public void onVideoSrcDisconnect() { }

  @Override
  public void onAudioFormat(AudioStreamInfo audioStreamInfo) { }

  @Override
  public void onAudio(byte[] packet) { }

  @Override
  public void onAudioSrcDisconnect() { }
}
