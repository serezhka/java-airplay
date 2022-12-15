package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.lib.AirPlay;

public class Session {

    private final AirPlay airPlay;

    private Thread airPlayReceiverThread;
    private Thread audioReceiverThread;
    private Thread audioControlServerThread;

    Session() {
        airPlay = new AirPlay();
    }

    public AirPlay getAirPlay() {
        return airPlay;
    }

    public void setAirPlayReceiverThread(Thread airPlayReceiverThread) {
        this.airPlayReceiverThread = airPlayReceiverThread;
    }

    public void setAudioReceiverThread(Thread audioReceiverThread) {
        this.audioReceiverThread = audioReceiverThread;
    }

    public void setAudioControlServerThread(Thread audioControlServerThread) {
        this.audioControlServerThread = audioControlServerThread;
    }

    public boolean isVideoActive() {
        return airPlayReceiverThread != null;
    }

    public boolean isAudioActive() {
        return audioReceiverThread != null && audioControlServerThread != null;
    }

    public void stopVideo() {
        if (airPlayReceiverThread != null) {
            airPlayReceiverThread.interrupt();
            airPlayReceiverThread = null;
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
