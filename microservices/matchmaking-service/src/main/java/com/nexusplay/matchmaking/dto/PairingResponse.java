package com.nexusplay.matchmaking.dto;

import com.nexusplay.matchmaking.model.Match;

import java.util.List;

/** Resultado del emparejamiento. byeTeamId: equipo que pasa de ronda si la cantidad es impar. */
public record PairingResponse(String tournamentId, List<Match> matches, String byeTeamId) {
}
