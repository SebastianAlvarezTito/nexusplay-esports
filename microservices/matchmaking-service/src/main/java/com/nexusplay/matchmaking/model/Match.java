package com.nexusplay.matchmaking.model;

import java.time.Instant;

public record Match(String id, String tournamentId, String teamAId, String teamBId,
                    MatchStatus status, String winnerTeamId, Instant createdAt) {

    public static Match pending(String id, String tournamentId, String teamAId, String teamBId) {
        return new Match(id, tournamentId, teamAId, teamBId, MatchStatus.PENDING, null, Instant.now());
    }

    public Match completedWith(String winnerTeamId) {
        return new Match(id, tournamentId, teamAId, teamBId, MatchStatus.COMPLETED, winnerTeamId, createdAt);
    }
}
