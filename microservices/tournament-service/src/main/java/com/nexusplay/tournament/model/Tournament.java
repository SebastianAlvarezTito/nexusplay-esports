package com.nexusplay.tournament.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Torneo de eSports con cupos limitados. Estado 100% en memoria.
 * Los métodos mutables son synchronized: varios hilos de Tomcat inscriben equipos a la vez.
 */
public class Tournament {

    private final String id;
    private final String name;
    private final String game;
    private final int minMmr;
    private final long prizePoolUsd;
    private final Instant createdAt = Instant.now();

    private int maxSlots;
    private TournamentStatus status = TournamentStatus.OPEN;
    private final Map<String, String> teams = new LinkedHashMap<>(); // teamId -> teamName

    public Tournament(String id, String name, String game, int maxSlots, int minMmr, long prizePoolUsd) {
        this.id = id;
        this.name = name;
        this.game = game;
        this.maxSlots = maxSlots;
        this.minMmr = minMmr;
        this.prizePoolUsd = prizePoolUsd;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getGame() { return game; }
    public int getMinMmr() { return minMmr; }
    public long getPrizePoolUsd() { return prizePoolUsd; }
    public Instant getCreatedAt() { return createdAt; }

    public synchronized int getMaxSlots() { return maxSlots; }
    public synchronized TournamentStatus getStatus() { return status; }
    public synchronized int registeredTeams() { return teams.size(); }
    public synchronized int availableSlots() { return maxSlots - teams.size(); }
    public synchronized boolean hasTeam(String teamId) { return teams.containsKey(teamId); }
    public synchronized List<String> teamIds() { return new ArrayList<>(teams.keySet()); }

    public synchronized void addTeam(String teamId, String teamName) {
        teams.put(teamId, teamName);
        if (availableSlots() <= 0) {
            status = TournamentStatus.FULL;
        }
    }

    public synchronized boolean removeTeam(String teamId) {
        boolean removed = teams.remove(teamId) != null;
        if (removed && status == TournamentStatus.FULL) {
            status = TournamentStatus.OPEN;
        }
        return removed;
    }

    public synchronized void expandCapacity(int extraSlots) {
        maxSlots += extraSlots;
        if (status == TournamentStatus.FULL && availableSlots() > 0) {
            status = TournamentStatus.OPEN;
        }
    }

    public synchronized void start() {
        status = TournamentStatus.IN_PROGRESS;
    }
}
