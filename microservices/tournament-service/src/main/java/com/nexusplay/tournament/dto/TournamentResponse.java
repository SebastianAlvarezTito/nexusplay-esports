package com.nexusplay.tournament.dto;

import com.nexusplay.tournament.model.Tournament;
import com.nexusplay.tournament.model.TournamentStatus;

import java.time.Instant;
import java.util.List;

public record TournamentResponse(
        String id,
        String name,
        String game,
        TournamentStatus status,
        int maxSlots,
        int registeredTeams,
        int availableSlots,
        int minMmr,
        long prizePoolUsd,
        List<String> teamIds,
        Instant createdAt) {

    /** Foto consistente del torneo (se toma el mismo monitor que usan sus métodos synchronized). */
    public static TournamentResponse from(Tournament t) {
        synchronized (t) {
            return new TournamentResponse(
                    t.getId(), t.getName(), t.getGame(), t.getStatus(),
                    t.getMaxSlots(), t.registeredTeams(), t.availableSlots(),
                    t.getMinMmr(), t.getPrizePoolUsd(), t.teamIds(), t.getCreatedAt());
        }
    }
}
