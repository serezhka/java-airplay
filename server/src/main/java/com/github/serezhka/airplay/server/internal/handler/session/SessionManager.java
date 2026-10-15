package com.github.serezhka.airplay.server.internal.handler.session;

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SessionManager {

    private final PairingIdentity identity;
    private final Map<String, Session> sessions = new HashMap<>();

    public SessionManager() {
        this(PairingIdentity.generate());
    }

    public SessionManager(PairingIdentity identity) {
        this.identity = identity;
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
