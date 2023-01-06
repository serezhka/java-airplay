package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.lib.AirPlay;

public class Session {

    private final AirPlay airPlay;

    private Thread videoReceiverThread;
    private Thread audioReceiverThread;
    private Thread audioControlServerThread;

    Session() {
        airPlay = new AirPlay();
    }

    public AirPlay getAirPlay() {
        return airPlay;
    }

    public void setVideoReceiverThread(Thread videoReceiverThread) {
        this.videoReceiverThread = videoReceiverThread;
    }

    public void setAudioReceiverThread(Thread audioReceiverThread) {
        this.audioReceiverThread = audioReceiverThread;
    }

    public void setAudioControlServerThread(Thread audioControlServerThread) {
        this.audioControlServerThread = audioControlServerThread;
    }

    public boolean isVideoActive() {
        return videoReceiverThread != null;
    }

    public boolean isAudioActive() {
        return audioReceiverThread != null && audioControlServerThread != null;
    }

    public void stopVideo() {
        if (videoReceiverThread != null) {
            videoReceiverThread.interrupt();
            videoReceiverThread = null;
        }
        // TODO destroy fair play video decryptor
    }

    public void stopAudio() {
        if (audioReceiverThread != null) {
            audioReceiverThread.interrupt();
            audioReceiverThread = null;
        }
        if (audioControlServerThread != null) {
            audioControlServerThread.interrupt();
            audioControlServerThread = null;
        }
    }
}
