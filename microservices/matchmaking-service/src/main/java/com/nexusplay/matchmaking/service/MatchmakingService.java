package com.nexusplay.matchmaking.service;

import com.nexusplay.matchmaking.client.TournamentClient;
import com.nexusplay.matchmaking.dto.LobbyResponse;
import com.nexusplay.matchmaking.dto.PairingResponse;
import com.nexusplay.matchmaking.dto.TournamentDto;
import com.nexusplay.matchmaking.exception.ApiException;
import com.nexusplay.matchmaking.metrics.MatchmakingMetrics;
import com.nexusplay.matchmaking.model.Match;
import com.nexusplay.matchmaking.model.MatchStatus;
import com.nexusplay.matchmaking.model.Team;
import com.nexusplay.matchmaking.repository.MatchRepository;
import com.nexusplay.matchmaking.repository.TeamRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class MatchmakingService {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingService.class);

    private final TeamRepository teams;
    private final MatchRepository matches;
    private final TournamentClient tournaments;
    private final MatchmakingMetrics metrics;

    public MatchmakingService(TeamRepository teams, MatchRepository matches,
                              TournamentClient tournaments, MatchmakingMetrics metrics) {
        this.teams = teams;
        this.matches = matches;
        this.tournaments = tournaments;
        this.metrics = metrics;
    }

    /**
     * Genera emparejamientos por cercanía de MMR (1º vs 2º, 3º vs 4º...).
     * Reglas que dependen del Servicio A:
     *  - torneo FINISHED => no se empareja
     *  - torneo OPEN (cupos sin llenar) => solo con allowPartial=true
     *  - si aún no está IN_PROGRESS, se le ordena a A que inicie el torneo
     */
    public PairingResponse pair(String tournamentId, boolean allowPartial) {
        return metrics.timePairing(() -> doPair(tournamentId, allowPartial));
    }

    private PairingResponse doPair(String tournamentId, boolean allowPartial) {
        TournamentDto t = tournaments.getTournament(tournamentId);

        if ("FINISHED".equals(t.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "El torneo '" + tournamentId + "' ya finalizó");
        }
        if ("OPEN".equals(t.status()) && !allowPartial) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El torneo aún tiene " + t.availableSlots() + " cupos abiertos. Usa allowPartial=true para emparejar igual.");
        }

        Set<String> busy = matches.findByTournament(tournamentId).stream()
                .filter(m -> m.status() == MatchStatus.PENDING)
                .flatMap(m -> Stream.of(m.teamAId(), m.teamBId()))
                .collect(Collectors.toSet());
        List<Team> free = teams.findByTournament(tournamentId).stream()
                .filter(team -> !busy.contains(team.id()))
                .sorted(Comparator.comparingInt(Team::mmr).reversed())
                .toList();
        if (free.size() < 2) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Se necesitan al menos 2 equipos libres para emparejar (hay " + free.size() + ")");
        }

        if (!"IN_PROGRESS".equals(t.status())) {
            tournaments.startTournament(tournamentId);
        }

        List<Match> created = new ArrayList<>();
        for (int i = 0; i + 1 < free.size(); i += 2) {
            Match m = Match.pending("match-" + UUID.randomUUID().toString().substring(0, 8),
                    tournamentId, free.get(i).id(), free.get(i + 1).id());
            matches.save(m);
            metrics.matchCreated();
            created.add(m);
        }
        String bye = free.size() % 2 == 1 ? free.get(free.size() - 1).id() : null;
        log.info("Emparejamiento torneo={} partidas={} bye={}", tournamentId, created.size(), bye);
        return new PairingResponse(tournamentId, created, bye);
    }

    public List<Match> listMatches(String tournamentId) {
        return tournamentId == null ? matches.findAll() : matches.findByTournament(tournamentId);
    }

    public Match submitResult(String matchId, String winnerTeamId) {
        Match m = matches.findById(matchId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Partida '" + matchId + "' no existe"));
        if (m.status() == MatchStatus.COMPLETED) {
            throw new ApiException(HttpStatus.CONFLICT, "La partida ya tiene resultado");
        }
        if (!winnerTeamId.equals(m.teamAId()) && !winnerTeamId.equals(m.teamBId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El ganador debe ser uno de los equipos de la partida");
        }
        Match done = m.completedWith(winnerTeamId);
        matches.save(done);
        metrics.matchCompleted();
        return done;
    }

    /** Sala de espera: combina datos del Servicio A (torneo) con datos locales (equipos/partidas). */
    public LobbyResponse lobby(String tournamentId) {
        TournamentDto t = tournaments.getTournament(tournamentId);
        List<Match> ms = matches.findByTournament(tournamentId);
        int pending = (int) ms.stream().filter(m -> m.status() == MatchStatus.PENDING).count();
        return new LobbyResponse(t, teams.findByTournament(tournamentId).size(), pending, ms.size() - pending);
    }
}
