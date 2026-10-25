package com.nexusplay.matchmaking.repository;

import com.nexusplay.matchmaking.model.Match;
import com.nexusplay.matchmaking.model.MatchStatus;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class MatchRepository {

    private final Map<String, Match> matches = new ConcurrentHashMap<>();

    public void save(Match match) { matches.put(match.id(), match); }

    public Optional<Match> findById(String id) { return Optional.ofNullable(matches.get(id)); }

    public List<Match> findAll() { return List.copyOf(matches.values()); }

    public List<Match> findByTournament(String tournamentId) {
        return matches.values().stream().filter(m -> m.tournamentId().equals(tournamentId)).toList();
    }

    public int countByStatus(MatchStatus status) {
        return (int) matches.values().stream().filter(m -> m.status() == status).count();
    }
}
