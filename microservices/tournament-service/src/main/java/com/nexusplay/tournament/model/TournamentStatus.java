package com.nexusplay.tournament.model;

/** Ciclo de vida de un torneo. */
public enum TournamentStatus {
    OPEN,          // acepta inscripciones
    FULL,          // cupos agotados
    IN_PROGRESS,   // brackets en juego
    FINISHED
}
