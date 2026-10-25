package com.nexusplay.tournament.controller;

import com.nexusplay.tournament.dto.CreateTournamentRequest;
import com.nexusplay.tournament.dto.ExpandCapacityRequest;
import com.nexusplay.tournament.dto.ReserveSlotRequest;
import com.nexusplay.tournament.dto.TournamentResponse;
import com.nexusplay.tournament.service.TournamentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tournaments")
public class TournamentController {

    private final TournamentService service;

    public TournamentController(TournamentService service) {
        this.service = service;
    }

    /** GET /api/tournaments?game=VALORANT&status=OPEN */
    @GetMapping
    public List<TournamentResponse> list(@RequestParam(required = false) String game,
                                         @RequestParam(required = false) String status) {
        return service.list(game, status);
    }

    @GetMapping("/{id}")
    public TournamentResponse get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<TournamentResponse> create(@Valid @RequestBody CreateTournamentRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(req));
    }

    /** Reserva un cupo (lo invoca matchmaking-service al registrar un equipo). */
    @PostMapping("/{id}/slots")
    public ResponseEntity<TournamentResponse> reserveSlot(@PathVariable String id,
                                                          @Valid @RequestBody ReserveSlotRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.reserveSlot(id, req));
    }

    @DeleteMapping("/{id}/slots/{teamId}")
    public ResponseEntity<Void> releaseSlot(@PathVariable String id, @PathVariable String teamId) {
        service.releaseSlot(id, teamId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/capacity")
    public TournamentResponse expand(@PathVariable String id, @Valid @RequestBody ExpandCapacityRequest req) {
        return service.expandCapacity(id, req.extraSlots());
    }

    @PostMapping("/{id}/start")
    public TournamentResponse start(@PathVariable String id) {
        return service.start(id);
    }
}
