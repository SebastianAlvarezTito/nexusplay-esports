package com.nexusplay.matchmaking.client;

import com.nexusplay.matchmaking.dto.TournamentDto;
import com.nexusplay.matchmaking.metrics.MatchmakingMetrics;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.ChannelOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Cliente HTTP hacia tournament-service (Servicio A) con Spring WebClient.
 *
 *  - Timeout máximo por llamada (nexusplay.tournament-client.timeout-ms).
 *  - Si A no responde: se registra el error (log + métrica) y se lanza una excepción
 *    controlada que el GlobalExceptionHandler convierte en HTTP 503.
 *  - Si A responde 4xx: es una regla de negocio (404/409), se propaga como TournamentRejectedException.
 */
@Component
public class TournamentClient {

    private static final Logger log = LoggerFactory.getLogger(TournamentClient.class);

    private final WebClient webClient;
    private final Duration timeout;
    private final MatchmakingMetrics metrics;

    public TournamentClient(WebClient.Builder builder,
                            MatchmakingMetrics metrics,
                            @Value("${nexusplay.tournament-client.base-url}") String baseUrl,
                            @Value("${nexusplay.tournament-client.timeout-ms}") long timeoutMs) {
        this.metrics = metrics;
        this.timeout = Duration.ofMillis(timeoutMs);
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 1000);
        this.webClient = builder
                .baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
        log.info("TournamentClient listo baseUrl={} timeoutMs={}", baseUrl, timeoutMs);
    }

    public TournamentDto getTournament(String tournamentId) {
        return call("get_tournament", webClient.get()
                .uri("/api/tournaments/{id}", tournamentId)
                .retrieve()
                .bodyToMono(TournamentDto.class));
    }

    public TournamentDto reserveSlot(String tournamentId, String teamId, String teamName) {
        return call("reserve_slot", webClient.post()
                .uri("/api/tournaments/{id}/slots", tournamentId)
                .bodyValue(Map.of("teamId", teamId, "teamName", teamName))
                .retrieve()
                .bodyToMono(TournamentDto.class));
    }

    public void releaseSlot(String tournamentId, String teamId) {
        call("release_slot", webClient.delete()
                .uri("/api/tournaments/{id}/slots/{teamId}", tournamentId, teamId)
                .retrieve()
                .toBodilessEntity());
    }

    public TournamentDto startTournament(String tournamentId) {
        return call("start_tournament", webClient.post()
                .uri("/api/tournaments/{id}/start", tournamentId)
                .retrieve()
                .bodyToMono(TournamentDto.class));
    }

    /** Ejecuta la llamada con timeout, mide su duración y traduce los fallos a excepciones propias. */
    private <T> T call(String operation, Mono<T> request) {
        Timer.Sample sample = metrics.startSample();
        String outcome = "success";
        try {
            T result = request.timeout(timeout).block();
            if (result == null) {
                throw new IllegalStateException("respuesta vacía de tournament-service");
            }
            return result;
        } catch (RuntimeException ex) {
            Throwable root = Exceptions.unwrap(ex);

            if (root instanceof WebClientResponseException wre && wre.getStatusCode().is4xxClientError()) {
                outcome = "rejected";
                throw new TournamentRejectedException(wre.getStatusCode().value(), wre.getResponseBodyAsString());
            }

            String reason = classify(root);
            outcome = reason;
            metrics.clientError(operation, reason);
            log.error("Fallo llamando a tournament-service operation={} reason={} timeoutMs={} detail={}",
                    operation, reason, timeout.toMillis(), root.toString());
            throw new TournamentUnavailableException(operation, reason, root);
        } finally {
            sample.stop(metrics.clientTimer(operation, outcome));
        }
    }

    private static String classify(Throwable root) {
        if (root instanceof TimeoutException) {
            return "timeout";
        }
        if (root instanceof WebClientRequestException) {
            return "connection";
        }
        if (root instanceof WebClientResponseException wre && wre.getStatusCode().is5xxServerError()) {
            return "upstream_5xx";
        }
        return "unexpected";
    }
}
