package com.nexusplay.matchmaking.client;

/** tournament-service contestó con un 4xx: es una decisión de negocio, no una caída. */
public class TournamentRejectedException extends RuntimeException {
    private final int status;

    public TournamentRejectedException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
