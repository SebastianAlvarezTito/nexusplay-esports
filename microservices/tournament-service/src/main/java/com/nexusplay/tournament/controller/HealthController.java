package com.nexusplay.tournament.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Health check PROPIO: responde al instante y no depende de nada externo.
 * Lo usan el liveness y el readiness probe de Kubernetes.
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", "tournament-service");
    }
}
