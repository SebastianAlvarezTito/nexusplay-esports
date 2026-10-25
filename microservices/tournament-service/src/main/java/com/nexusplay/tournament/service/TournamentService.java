package com.nexusplay.tournament.service;

import com.nexusplay.tournament.dto.CreateTournamentRequest;
import com.nexusplay.tournament.dto.ReserveSlotRequest;
import com.nexusplay.tournament.dto.TournamentResponse;
import com.nexusplay.tournament.exception.ApiException;
import com.nexusplay.tournament.metrics.TournamentMetrics;
import com.nexusplay.tournament.model.Tournament;
import com.nexusplay.tournament.model.TournamentStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Lógica de torneos y cupos. Sin base de datos: todo vive en un ConcurrentHashMap. */
@Service
public class TournamentService {

    private static final Logger log = LoggerFactory.getLogger(TournamentService.class);

    private final Map<String, Tournament> tournaments = new ConcurrentHashMap<>();
    private final TournamentMetrics metrics;

    public TournamentService(TournamentMetrics metrics) {
        this.metrics = metrics;
        metrics.bindOpenTournaments(() -> tournaments.values().stream()
                .filter(t -> t.getStatus() == TournamentStatus.OPEN)
                .count());
        seed();
    }

    /** Torneos iniciales para poder probar sin crear nada a mano. */
    private void seed() {
        register(new Tournament("valorant-lima-open", "Valorant Lima Open", "VALORANT", 16, 800, 5000));
        register(new Tournament("dota2-andes-cup", "Dota 2 Andes Cup", "DOTA2", 8, 0, 3000));
        register(new Tournament("cs2-pacific-series", "CS2 Pacific Series", "CS2", 32, 1000, 10000));
        register(new Tournament("lol-inca-league", "League of Legends Inca League", "LOL", 16, 600, 2500));
        log.info("Torneos semilla cargados: {}", tournaments.keySet());
    }

    private void register(Tournament t) {
        tournaments.put(t.getId(), t);
        metrics.registerTournamentGauge(t);
    }

    // ------------------------------------------------------------------ consultas

    public List<TournamentResponse> list(String game, String status) {
        return tournaments.values().stream()
                .filter(t -> game == null || t.getGame().equalsIgnoreCase(game))
                .filter(t -> status == null || t.getStatus().name().equalsIgnoreCase(status))
                .sorted(Comparator.comparing(Tournament::getId))
                .map(TournamentResponse::from)
                .toList();
    }

    public TournamentResponse get(String id) {
        return TournamentResponse.from(find(id));
    }

    // ------------------------------------------------------------------ comandos

    public TournamentResponse create(CreateTournamentRequest req) {
        String id = slugify(req.name());
        Tournament t = new Tournament(id, req.name(), req.game().toUpperCase(),
                req.maxSlots(), req.minMmr(), req.prizePoolUsd());
        if (tournaments.putIfAbsent(id, t) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya existe un torneo con id '" + id + "'");
        }
        metrics.registerTournamentGauge(t);
        metrics.tournamentCreated(t.getGame());
        log.info("Torneo creado id={} game={} slots={}", id, t.getGame(), t.getMaxSlots());
        return TournamentResponse.from(t);
    }

    /** Reserva un cupo para un equipo. Idempotente por teamId. */
    public TournamentResponse reserveSlot(String id, ReserveSlotRequest req) {
        return metrics.timeReservation(() -> {
            Tournament t = find(id);
            synchronized (t) {
                if (t.hasTeam(req.teamId())) {
                    return TournamentResponse.from(t);
                }
                if (t.getStatus() != TournamentStatus.OPEN) {
                    String reason = t.getStatus() == TournamentStatus.FULL ? "full" : "closed";
                    metrics.slotRejected(reason, t.getGame());
                    throw new ApiException(HttpStatus.CONFLICT,
                            "El torneo '" + id + "' no acepta inscripciones (estado " + t.getStatus() + ")");
                }
                t.addTeam(req.teamId(), req.teamName());
                metrics.slotReserved(t.getGame());
                log.info("Cupo reservado torneo={} team={} libres={}", id, req.teamId(), t.availableSlots());
                return TournamentResponse.from(t);
            }
        });
    }

    public void releaseSlot(String id, String teamId) {
        Tournament t = find(id);
        synchronized (t) {
            if (t.getStatus() == TournamentStatus.IN_PROGRESS || t.getStatus() == TournamentStatus.FINISHED) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "No se pueden liberar cupos de un torneo en estado " + t.getStatus());
            }
            if (!t.removeTeam(teamId)) {
                throw new ApiException(HttpStatus.NOT_FOUND,
                        "El equipo '" + teamId + "' no tiene cupo en el torneo '" + id + "'");
            }
            log.info("Cupo liberado torneo={} team={} libres={}", id, teamId, t.availableSlots());
        }
    }

    /** Respuesta de negocio ante demanda no prevista: abrir cupos extra. */
    public TournamentResponse expandCapacity(String id, int extraSlots) {
        Tournament t = find(id);
        synchronized (t) {
            if (t.getStatus() == TournamentStatus.IN_PROGRESS || t.getStatus() == TournamentStatus.FINISHED) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "No se puede ampliar un torneo en estado " + t.getStatus());
            }
            t.expandCapacity(extraSlots);
            log.warn("Capacidad ampliada torneo={} +{} => max={}", id, extraSlots, t.getMaxSlots());
            return TournamentResponse.from(t);
        }
    }

    public TournamentResponse start(String id) {
        Tournament t = find(id);
        synchronized (t) {
            if (t.getStatus() != TournamentStatus.OPEN && t.getStatus() != TournamentStatus.FULL) {
                throw new ApiException(HttpStatus.CONFLICT, "El torneo ya está en estado " + t.getStatus());
            }
            if (t.registeredTeams() < 2) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Se necesitan al menos 2 equipos para iniciar (hay " + t.registeredTeams() + ")");
            }
            t.start();
            log.info("Torneo iniciado id={} equipos={}", id, t.registeredTeams());
            return TournamentResponse.from(t);
        }
    }

    // ------------------------------------------------------------------ helpers

    private Tournament find(String id) {
        Tournament t = tournaments.get(id);
        if (t == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Torneo '" + id + "' no existe");
        }
        return t;
    }

    private static String slugify(String name) {
        String n = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
