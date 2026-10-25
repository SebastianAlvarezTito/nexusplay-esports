package com.nexusplay.tournament.metrics;

import com.nexusplay.tournament.config.DelaySimulator;
import com.nexusplay.tournament.model.Tournament;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Métricas de negocio del Servicio A (prefijo "tournament_" en Prometheus).
 *
 *  Counter  tournament_slots_reserved_total{game}          cupos reservados con éxito
 *  Counter  tournament_slots_rejected_total{reason,game}   reservas rechazadas (full / closed)
 *  Counter  tournament_created_total{game}                 torneos creados
 *  Gauge    tournament_slots_available{tournament,game}    cupos libres por torneo
 *  Gauge    tournament_open_count                          torneos aceptando inscripciones
 *  Gauge    tournament_artificial_delay_ms                 delay artificial activo (Casuística 2)
 *  Timer    tournament_reservation_duration_seconds        latencia de la reserva de cupo
 */
@Component
public class TournamentMetrics {

    private final MeterRegistry registry;
    private final Timer reservationTimer;

    public TournamentMetrics(MeterRegistry registry, DelaySimulator delaySimulator) {
        this.registry = registry;

        this.reservationTimer = Timer.builder("tournament.reservation.duration")
                .description("Tiempo que tarda el servicio en procesar una reserva de cupo")
                .publishPercentileHistogram()
                .register(registry);

        Gauge.builder("tournament.artificial.delay.ms", delaySimulator, DelaySimulator::currentDelayMs)
                .description("Delay artificial (ms) inyectado en las respuestas de /api/**")
                .register(registry);
    }

    public void slotReserved(String game) {
        Counter.builder("tournament.slots.reserved")
                .description("Cupos de torneo reservados con éxito")
                .tag("game", game)
                .register(registry)
                .increment();
    }

    public void slotRejected(String reason, String game) {
        Counter.builder("tournament.slots.rejected")
                .description("Reservas de cupo rechazadas")
                .tag("reason", reason)
                .tag("game", game)
                .register(registry)
                .increment();
    }

    public void tournamentCreated(String game) {
        Counter.builder("tournament.created")
                .description("Torneos creados")
                .tag("game", game)
                .register(registry)
                .increment();
    }

    /** Un Gauge por torneo: Micrometer lo lee en cada scrape (el torneo vive en el mapa del service). */
    public void registerTournamentGauge(Tournament t) {
        Gauge.builder("tournament.slots.available", t, Tournament::availableSlots)
                .description("Cupos libres del torneo")
                .tag("tournament", t.getId())
                .tag("game", t.getGame())
                .register(registry);
    }

    public void bindOpenTournaments(Supplier<Number> supplier) {
        Gauge.builder("tournament.open.count", supplier)
                .description("Torneos en estado OPEN (aceptando inscripciones)")
                .strongReference(true)
                .register(registry);
    }

    public <T> T timeReservation(Supplier<T> action) {
        return reservationTimer.record(action);
    }
}
