package com.aiassist.listen;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/** In-memory registry of listening sessions. */
@Component
public class SessionStore {

    private final Map<String, ListeningSession> sessions = new ConcurrentHashMap<>();

    public ListeningSession create(String topic) {
        String id = UUID.randomUUID().toString();
        ListeningSession session = new ListeningSession(id, topic);
        sessions.put(id, session);
        return session;
    }

    public ListeningSession get(String id) {
        ListeningSession session = sessions.get(id);
        if (session == null) {
            throw new SessionNotFoundException(id);
        }
        return session;
    }

    public Collection<ListeningSession> all() {
        return sessions.values();
    }

    public void delete(String id) {
        if (sessions.remove(id) == null) {
            throw new SessionNotFoundException(id);
        }
    }
}
