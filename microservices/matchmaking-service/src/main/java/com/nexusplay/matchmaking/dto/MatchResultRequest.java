package com.nexusplay.matchmaking.dto;

import jakarta.validation.constraints.NotBlank;

public record MatchResultRequest(@NotBlank String winnerTeamId) {
}
