package com.nexusplay.tournament.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Casuística 2: introduce un delay artificial (ms) en las respuestas de /api/**.
 * Se configura con la variable de entorno ARTIFICIAL_DELAY_MS y se expone como Gauge
 * (tournament_artificial_delay_ms) para verlo en Grafana en tiempo real.
 */
@Component
public class DelaySimulator {

    private final AtomicLong delayMs;

    public DelaySimulator(@Value("${nexusplay.tournament.artificial-delay-ms:0}") long initialDelayMs) {
        this.delayMs = new AtomicLong(Math.max(0, initialDelayMs));
    }

    public long currentDelayMs() {
        return delayMs.get();
    }

    public void applyDelay() {
        long d = delayMs.get();
        if (d > 0) {
            try {
                Thread.sleep(d);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
