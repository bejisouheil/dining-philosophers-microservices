package fr.cytech.dining.philosopher.events;

import java.time.Duration;

import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.common.PhilosopherEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Envoie les événements au moniteur par HTTP (communication synchrone, sections 1 à 5).
 *
 * <p>Chaque envoi est protégé par Resilience4j : quelques nouvelles tentatives (Retry), puis un
 * disjoncteur (CircuitBreaker) qui coupe les appels quand le moniteur est en panne, pour ne pas
 * accumuler des délais d'attente.
 */
public class HttpEventPublisher extends QueuedEventPublisher {

    private final RestClient client;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public HttpEventPublisher(RestClient client) {
        this.client = client;
        this.retry = Retry.of("monitor", RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .build());
        this.circuitBreaker = CircuitBreaker.of("monitor", CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .build());
    }

    @Override
    protected void deliver(PhilosopherEvent event) {
        Runnable call = () -> client.post()
                .uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toBodilessEntity();
        Retry.decorateRunnable(retry, CircuitBreaker.decorateRunnable(circuitBreaker, call)).run();
    }
}
