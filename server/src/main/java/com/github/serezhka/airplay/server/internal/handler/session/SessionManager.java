package com.github.serezhka.airplay.server.internal.handler.session;

import lombok.RequiredArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@RequiredArgsConstructor
public class SessionManager {

    private final Map<String, Session> sessions = new HashMap<>();

    public Session getSession(String sessionId) {
        synchronized (sessions) {
            Session session;
            if ((session = sessions.get(sessionId)) == null) {
                session = new Session(sessionId);
                sessions.put(sessionId, session);
            }
            return session;
        }
    }
}
