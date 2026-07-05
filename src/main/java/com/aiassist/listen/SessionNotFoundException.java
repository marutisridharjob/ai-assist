package com.aiassist.listen;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class SessionNotFoundException extends ResponseStatusException {

    public SessionNotFoundException(String id) {
        super(HttpStatus.NOT_FOUND, "No listening session with id " + id);
    }
}
