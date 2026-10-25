package com.nexusplay.matchmaking.dto;

/** Sala de espera: estado del torneo (según A) + estado de emparejamiento (según B). */
public record LobbyResponse(TournamentDto tournament, int teamsRegisteredHere,
                            int pendingMatches, int completedMatches) {
}
