package fr.cytech.dining.philosopher.events;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.common.PhilosopherEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

/**
 * Envoie les événements au moniteur par HTTP, sans jamais bloquer le philosophe.
 *
 * <p>Les événements passent par une file traitée par un seul thread : l'ordre d'émission est
 * conservé. Chaque envoi est protégé par Resilience4j : quelques nouvelles tentatives (Retry),
 * puis un disjoncteur (CircuitBreaker) qui coupe les appels quand le moniteur est en panne, pour
 * ne pas accumuler des délais d'attente. Un moniteur indisponible fait perdre des événements
 * d'observation, jamais la correction du protocole.
 */
public class HttpEventPublisher implements EventPublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpEventPublisher.class);

    private final RestClient client;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

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
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10_000),
                runnable -> {
                    Thread thread = new Thread(runnable, "event-publisher");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    @Override
    public void publish(PhilosopherEvent event) {
        executor.execute(() -> send(event));
    }

    private void send(PhilosopherEvent event) {
        Runnable call = () -> client.post()
                .uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .body(event)
                .retrieve()
                .toBodilessEntity();
        try {
            Retry.decorateRunnable(retry, CircuitBreaker.decorateRunnable(circuitBreaker, call)).run();
        } catch (RuntimeException e) {
            long count = dropped.incrementAndGet();
            if (count == 1 || count % 50 == 0) {
                log.warn("Moniteur injoignable : {} événement(s) perdu(s) ({})", count, e.getMessage());
            }
        }
    }

    /** Nombre d'événements abandonnés parce que le moniteur ne répondait pas. */
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
