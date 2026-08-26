package com.sentinel.service;

import com.sentinel.domain.FraudCase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Pushes FraudCase updates to connected dashboard clients over Server-Sent
 * Events, so the dashboard reflects a status change the moment it happens
 * instead of waiting for the next poll. Emitters are plain in-memory state —
 * fine for a single-instance app; a multi-instance deployment would need
 * this backed by something shared (e.g. a Kafka topic fanned out per node).
 */
@Service
@Slf4j
public class CaseEventBroadcaster {

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — connection lives until the tab closes
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.debug("Dashboard client connected ({} active)", emitters.size());
        return emitter;
    }

    public void broadcast(FraudCase fraudCase) {
        if (emitters.isEmpty()) {
            return;
        }
        List<SseEmitter> dead = new CopyOnWriteArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("case-update").data(fraudCase, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
