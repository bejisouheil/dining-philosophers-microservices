package fr.cytech.dining.philosopher.token;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import fr.cytech.dining.common.HttpSupport;

/**
 * Transport HTTP entre philosophes (contrainte du sujet : échange de messages HTTP).
 *
 * <p>Une file et un thread par destinataire : les messages vers un même philosophe partent dans
 * l'ordre, et un destinataire lent ne bloque pas les autres. Le destinataire traite le message
 * avant de répondre : une réponse 2xx vaut accusé de réception. En cas d'échec (délai dépassé,
 * service arrêté) l'envoi est retenté avec une attente qui double à chaque fois ; un jeton est
 * retenté sans limite car il ne doit jamais disparaître.
 */
public class HttpPeerTransport implements PeerTransport, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HttpPeerTransport.class);
    private static final int REQUEST_ATTEMPTS = 2;
    private static final int RELEASE_ATTEMPTS = 6;
    private static final int UNLIMITED = Integer.MAX_VALUE;

    private final IntFunction<String> urlOf;
    private final Duration timeout;
    private final Map<Integer, RestClient> clients = new ConcurrentHashMap<>();
    private final Map<Integer, ExecutorService> queues = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public HttpPeerTransport(IntFunction<String> urlOf, Duration timeout) {
        this.urlOf = urlOf;
        this.timeout = timeout;
    }

    @Override
    public void sendRequest(int to, Messages.Request message) {
        enqueue(to, "/token/request", message, REQUEST_ATTEMPTS, null);
    }

    @Override
    public void sendRelease(int to, Messages.Release message) {
        enqueue(to, "/token/release", message, RELEASE_ATTEMPTS, null);
    }

    @Override
    public void sendToken(int to, Messages.Token message, Runnable acknowledged) {
        enqueue(to, "/token/transfer", message, UNLIMITED, acknowledged);
    }

    private void enqueue(int to, String path, Object body, int maxAttempts, Runnable onDelivered) {
        if (closed) {
            return;
        }
        queues.computeIfAbsent(to, peer -> Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "peer-" + peer);
            thread.setDaemon(true);
            return thread;
        })).execute(() -> {
            if (deliver(to, path, body, maxAttempts) && onDelivered != null) {
                onDelivered.run();
            }
        });
    }

    private boolean deliver(int to, String path, Object body, int maxAttempts) {
        RestClient client = clients.computeIfAbsent(to, peer -> HttpSupport.restClient(urlOf.apply(peer), timeout));
        long backoffMs = 50;
        for (int attempt = 1; attempt <= maxAttempts && !closed; attempt++) {
            try {
                client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                        .toBodilessEntity();
                return true;
            } catch (RestClientException e) {
                if (attempt == maxAttempts) {
                    log.warn("Message {} vers le philosophe {} abandonné après {} essais", path, to, attempt);
                    return false;
                }
                if (attempt == 1 || attempt % 20 == 0) {
                    log.warn("Philosophe {} injoignable pour {} (essai {}) : {}", to, path, attempt, e.getMessage());
                }
                try {
                    Thread.sleep(backoffMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                backoffMs = Math.min(backoffMs * 2, 1000);
            }
        }
        return false;
    }

    @Override
    public void close() {
        closed = true;
        queues.values().forEach(ExecutorService::shutdownNow);
    }
}
