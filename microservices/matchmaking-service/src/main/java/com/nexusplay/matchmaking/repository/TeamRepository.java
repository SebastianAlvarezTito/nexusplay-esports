package com.nexusplay.matchmaking.repository;

import com.nexusplay.matchmaking.model.Team;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class TeamRepository {

    private final Map<String, Team> teams = new ConcurrentHashMap<>();

    public void save(Team team) { teams.put(team.id(), team); }

    public Optional<Team> findById(String id) { return Optional.ofNullable(teams.get(id)); }

    public void delete(String id) { teams.remove(id); }

    public int count() { return teams.size(); }

    public List<Team> findAll() { return List.copyOf(teams.values()); }

    public List<Team> findByTournament(String tournamentId) {
        return teams.values().stream().filter(t -> t.tournamentId().equals(tournamentId)).toList();
    }
}
