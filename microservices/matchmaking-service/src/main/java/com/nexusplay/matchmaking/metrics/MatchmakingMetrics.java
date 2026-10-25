package com.nexusplay.matchmaking.metrics;

import com.nexusplay.matchmaking.model.MatchStatus;
import com.nexusplay.matchmaking.repository.MatchRepository;
import com.nexusplay.matchmaking.repository.TeamRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Métricas del Servicio B (prefijo "matchmaking_" en Prometheus).
 *
 *  Counter  matchmaking_teams_registered_total{game}
 *  Counter  matchmaking_registrations_rejected_total{reason}
 *  Counter  matchmaking_matches_created_total / matchmaking_matches_completed_total
 *  Counter  matchmaking_tournament_client_errors_total{operation,reason}   fallos hacia el Servicio A
 *  Gauge    matchmaking_teams_active
 *  Gauge    matchmaking_matches_pending
 *  Gauge    matchmaking_tournament_client_timeout_ms                       timeout configurado
 *  Timer    matchmaking_tournament_client_duration_seconds{operation,outcome}  latencia B -> A
 *  Timer    matchmaking_pairing_duration_seconds
 */
@Component
public class MatchmakingMetrics {

    private static final List<String> CLIENT_OPERATIONS =
            List.of("get_tournament", "reserve_slot", "release_slot", "start_tournament");
    private static final List<String> CLIENT_ERROR_REASONS =
            List.of("timeout", "connection", "upstream_5xx", "unexpected");
    private static final List<String> REJECTION_REASONS =
            List.of("full", "closed", "mmr_too_low", "duplicate", "tournament_not_found");

    private final MeterRegistry registry;
    private final Timer pairingTimer;
    private final AtomicLong timeoutMsHolder;

    public MatchmakingMetrics(MeterRegistry registry,
                              TeamRepository teams,
                              MatchRepository matches,
                              @Value("${nexusplay.tournament-client.timeout-ms}") long timeoutMs) {
        this.registry = registry;
        this.timeoutMsHolder = new AtomicLong(timeoutMs);

        this.pairingTimer = Timer.builder("matchmaking.pairing.duration")
                .description("Tiempo en generar emparejamientos de un torneo")
                .publishPercentileHistogram()
                .register(registry);

        Gauge.builder("matchmaking.teams.active", teams, TeamRepository::count)
                .description("Equipos actualmente inscritos").register(registry);
        Gauge.builder("matchmaking.matches.pending", matches, m -> m.countByStatus(MatchStatus.PENDING))
                .description("Partidas pendientes de resultado").register(registry);
        Gauge.builder("matchmaking.tournament.client.timeout.ms", timeoutMsHolder, AtomicLong::doubleValue)
                .description("Timeout configurado (ms) del cliente hacia tournament-service").register(registry);

        // Pre-registro en 0: las series existen desde el arranque y los paneles/alertas no quedan "vacíos".
        for (String reason : REJECTION_REASONS) {
            rejectionCounter(reason);
        }
        for (String op : CLIENT_OPERATIONS) {
            for (String reason : CLIENT_ERROR_REASONS) {
                clientErrorCounter(op, reason);
            }
        }
    }

    // ----- negocio
    public void teamRegistered(String game) {
        Counter.builder("matchmaking.teams.registered")
                .description("Equipos inscritos con éxito").tag("game", game)
                .register(registry).increment();
    }

    public void registrationRejected(String reason) {
        rejectionCounter(reason).increment();
    }

    public void matchCreated() {
        Counter.builder("matchmaking.matches.created").description("Partidas generadas")
                .register(registry).increment();
    }

    public void matchCompleted() {
        Counter.builder("matchmaking.matches.completed").description("Partidas finalizadas")
                .register(registry).increment();
    }

    public <T> T timePairing(Supplier<T> action) {
        return pairingTimer.record(action);
    }

    // ----- cliente hacia el Servicio A
    public Timer.Sample startSample() {
        return Timer.start(registry);
    }

    public Timer clientTimer(String operation, String outcome) {
        return Timer.builder("matchmaking.tournament.client.duration")
                .description("Latencia de las llamadas de matchmaking hacia tournament-service")
                .tag("operation", operation)
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .register(registry);
    }

    public void clientError(String operation, String reason) {
        clientErrorCounter(operation, reason).increment();
    }

    private Counter clientErrorCounter(String operation, String reason) {
        return Counter.builder("matchmaking.tournament.client.errors")
                .description("Llamadas fallidas hacia tournament-service")
                .tag("operation", operation)
                .tag("reason", reason)
                .register(registry);
    }

    private Counter rejectionCounter(String reason) {
        return Counter.builder("matchmaking.registrations.rejected")
                .description("Inscripciones de equipos rechazadas")
                .tag("reason", reason)
                .register(registry);
    }
}
