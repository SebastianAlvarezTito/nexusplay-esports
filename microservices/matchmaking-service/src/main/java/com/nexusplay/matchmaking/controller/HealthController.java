package com.nexusplay.matchmaking.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check PROPIO: NO consulta a tournament-service a propósito.
 * Si lo hiciera, una caída del Servicio A tumbaría también los probes de B (fallo en cascada).
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "matchmaking-service");
    }
}
