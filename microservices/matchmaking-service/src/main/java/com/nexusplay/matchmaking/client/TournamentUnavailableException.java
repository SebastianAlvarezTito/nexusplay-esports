package com.nexusplay.matchmaking.client;

/** tournament-service no respondió bien: reason = timeout | connection | upstream_5xx | unexpected. */
public class TournamentUnavailableException extends RuntimeException {
    private final String operation;
    private final String reason;

    public TournamentUnavailableException(String operation, String reason, Throwable cause) {
        super("tournament-service no disponible (operation=" + operation + ", reason=" + reason + ")", cause);
        this.operation = operation;
        this.reason = reason;
    }

    public String getOperation() { return operation; }
    public String getReason() { return reason; }
}
