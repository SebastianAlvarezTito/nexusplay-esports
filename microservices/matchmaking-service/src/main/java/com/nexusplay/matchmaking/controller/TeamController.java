package com.nexusplay.matchmaking.controller;

import com.nexusplay.matchmaking.dto.RegisterTeamRequest;
import com.nexusplay.matchmaking.model.Team;
import com.nexusplay.matchmaking.service.TeamService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamService service;

    public TeamController(TeamService service) {
        this.service = service;
    }

    /** Inscribe un equipo (consulta y reserva cupo en tournament-service). */
    @PostMapping
    public ResponseEntity<Team> register(@Valid @RequestBody RegisterTeamRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.register(req));
    }

    @GetMapping
    public List<Team> list(@RequestParam(required = false) String tournamentId) {
        return service.list(tournamentId);
    }

    @GetMapping("/{id}")
    public Team get(@PathVariable String id) {
        return service.get(id);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> withdraw(@PathVariable String id) {
        service.withdraw(id);
        return ResponseEntity.noContent().build();
    }
}
