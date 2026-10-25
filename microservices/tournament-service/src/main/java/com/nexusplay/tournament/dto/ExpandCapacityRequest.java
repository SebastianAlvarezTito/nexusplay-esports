package com.nexusplay.tournament.dto;

import jakarta.validation.constraints.Min;

public record ExpandCapacityRequest(@Min(1) int extraSlots) {
}
