package com.nexusplay.tournament.dto;

import jakarta.validation.constraints.NotBlank;

public record ReserveSlotRequest(@NotBlank String teamId, @NotBlank String teamName) {
}
