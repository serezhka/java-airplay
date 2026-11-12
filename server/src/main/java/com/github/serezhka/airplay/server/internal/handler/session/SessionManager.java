package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SessionManager {

    private final PairingIdentity identity;
    private final Map<String, Session> sessions = new HashMap<>();
    /**
     * True after any RTSP VIDEO SETUP on this receiver process. Shared across control
     * channels so YouTube-only {@code /stop} can end presentation, while mirror→HLS→/stop
     * keeps the player warm for remirror.
     */
    private final AtomicBoolean screenMirrorSeen = new AtomicBoolean();

    public SessionManager() {
        this(PairingIdentity.generate());
    }

    public SessionManager(PairingIdentity identity) {
        this.identity = identity;
    }

    public void markScreenMirrorSeen() {
        screenMirrorSeen.set(true);
    }

    public boolean screenMirrorSeen() {
        return screenMirrorSeen.get();
    }

    public void clearScreenMirrorSeen() {
        screenMirrorSeen.set(false);
    }

    public byte[] pairingPublicKey() {
        return identity.publicKey();
    }

    public Session getSession(String sessionId) {
        synchronized (sessions) {
            Session session;
            if ((session = sessions.get(sessionId)) == null) {
                session = new Session(sessionId, identity);
                sessions.put(sessionId, session);
            }
            return session;
        }
    }

    public List<Session> allSessions() {
        synchronized (sessions) {
            return new ArrayList<>(sessions.values());
        }
    }
}
