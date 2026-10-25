package com.nexusplay.matchmaking.model;

import java.time.Instant;

/** Equipo inscrito en un torneo. mmr = rating de emparejamiento. */
public record Team(String id, String name, String game, String tournamentId, int mmr, Instant registeredAt) {
}
