package com.nexusplay.matchmaking.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record RegisterTeamRequest(@NotBlank String name, @NotBlank String tournamentId, @Min(0) int mmr) {
}
