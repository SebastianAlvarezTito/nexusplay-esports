package com.nexusplay.matchmaking.controller;

import com.nexusplay.matchmaking.dto.LobbyResponse;
import com.nexusplay.matchmaking.dto.MatchResultRequest;
import com.nexusplay.matchmaking.dto.PairingResponse;
import com.nexusplay.matchmaking.model.Match;
import com.nexusplay.matchmaking.service.MatchmakingService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
public class MatchController {

    private final MatchmakingService service;

    public MatchController(MatchmakingService service) {
        this.service = service;
    }

    /** POST /api/matchmaking/{tournamentId}/pair?allowPartial=false */
    @PostMapping("/matchmaking/{tournamentId}/pair")
    public ResponseEntity<PairingResponse> pair(@PathVariable String tournamentId,
                                                @RequestParam(defaultValue = "false") boolean allowPartial) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.pair(tournamentId, allowPartial));
    }

    @GetMapping("/matches")
    public List<Match> matches(@RequestParam(required = false) String tournamentId) {
        return service.listMatches(tournamentId);
    }

    @PostMapping("/matches/{matchId}/result")
    public Match result(@PathVariable String matchId, @Valid @RequestBody MatchResultRequest req) {
        return service.submitResult(matchId, req.winnerTeamId());
    }

    /** Sala de espera del torneo: depende de tournament-service (ideal para observar la Casuística 2). */
    @GetMapping("/tournaments/{tournamentId}/lobby")
    public LobbyResponse lobby(@PathVariable String tournamentId) {
        return service.lobby(tournamentId);
    }
}
