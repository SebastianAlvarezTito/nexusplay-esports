package com.nexusplay.matchmaking.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Vista de un torneo tal como lo devuelve tournament-service (Servicio A). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TournamentDto(String id, String name, String game, String status,
                            int maxSlots, int registeredTeams, int availableSlots,
                            int minMmr, long prizePoolUsd) {
}
