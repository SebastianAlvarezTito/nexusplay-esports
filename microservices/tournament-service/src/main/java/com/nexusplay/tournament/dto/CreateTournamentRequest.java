package com.nexusplay.tournament.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public record CreateTournamentRequest(
        @NotBlank String name,
        @NotBlank String game,
        @Min(2) int maxSlots,
        @Min(0) int minMmr,
        @Min(0) long prizePoolUsd) {
}
