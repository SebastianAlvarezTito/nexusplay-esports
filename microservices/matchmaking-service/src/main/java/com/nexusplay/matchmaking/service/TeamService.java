package com.nexusplay.matchmaking.service;

import com.nexusplay.matchmaking.client.TournamentClient;
import com.nexusplay.matchmaking.client.TournamentRejectedException;
import com.nexusplay.matchmaking.dto.RegisterTeamRequest;
import com.nexusplay.matchmaking.dto.TournamentDto;
import com.nexusplay.matchmaking.exception.ApiException;
import com.nexusplay.matchmaking.metrics.MatchmakingMetrics;
import com.nexusplay.matchmaking.model.Team;
import com.nexusplay.matchmaking.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TeamService {

    private static final Logger log = LoggerFactory.getLogger(TeamService.class);

    private final TeamRepository teams;
    private final TournamentClient tournaments;
    private final MatchmakingMetrics metrics;

    public TeamService(TeamRepository teams, TournamentClient tournaments, MatchmakingMetrics metrics) {
        this.teams = teams;
        this.tournaments = tournaments;
        this.metrics = metrics;
    }

    /**
     * Inscribe un equipo. La decisión depende SIEMPRE del Servicio A:
     *   1) consulta el torneo (¿existe? ¿está OPEN? ¿quedan cupos? ¿cumple el MMR mínimo?)
     *   2) reserva el cupo en A
     *   3) solo entonces guarda el equipo localmente
     * Si A no responde a tiempo => TournamentUnavailableException => HTTP 503 controlado.
     */
    public Team register(RegisterTeamRequest req) {
        TournamentDto t;
        try {
            t = tournaments.getTournament(req.tournamentId());
        } catch (TournamentRejectedException ex) {
            metrics.registrationRejected("tournament_not_found");
            throw ex;
        }

        if (!"OPEN".equals(t.status())) {
            String reason = "FULL".equals(t.status()) ? "full" : "closed";
            metrics.registrationRejected(reason);
            throw new ApiException(HttpStatus.CONFLICT,
                    "El torneo '" + t.id() + "' no acepta inscripciones (estado " + t.status() + ")");
        }
        if (t.availableSlots() <= 0) {
            metrics.registrationRejected("full");
            throw new ApiException(HttpStatus.CONFLICT, "El torneo '" + t.id() + "' no tiene cupos disponibles");
        }
        if (req.mmr() < t.minMmr()) {
            metrics.registrationRejected("mmr_too_low");
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "MMR insuficiente: el torneo exige mínimo " + t.minMmr() + " y el equipo tiene " + req.mmr());
        }
        boolean duplicate = teams.findByTournament(t.id()).stream()
                .anyMatch(x -> x.name().equalsIgnoreCase(req.name()));
        if (duplicate) {
            metrics.registrationRejected("duplicate");
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ya existe un equipo llamado '" + req.name() + "' en el torneo '" + t.id() + "'");
        }

        String teamId = "team-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            tournaments.reserveSlot(t.id(), teamId, req.name());
        } catch (TournamentRejectedException ex) {
            // Carrera: el último cupo se ocupó entre la consulta y la reserva.
            metrics.registrationRejected("full");
            throw ex;
        }

        Team team = new Team(teamId, req.name(), t.game(), t.id(), req.mmr(), Instant.now());
        teams.save(team);
        metrics.teamRegistered(t.game());
        log.info("Equipo inscrito id={} name={} torneo={} mmr={}", teamId, req.name(), t.id(), req.mmr());
        return team;
    }

    public List<Team> list(String tournamentId) {
        return tournamentId == null ? teams.findAll() : teams.findByTournament(tournamentId);
    }

    public Team get(String id) {
        return teams.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Equipo '" + id + "' no existe"));
    }

    /** Baja de un equipo: primero libera el cupo en A; si A falla, el equipo NO se elimina (consistencia). */
    public void withdraw(String id) {
        Team team = get(id);
        tournaments.releaseSlot(team.tournamentId(), team.id());
        teams.delete(id);
        log.info("Equipo retirado id={} torneo={}", id, team.tournamentId());
    }
}
