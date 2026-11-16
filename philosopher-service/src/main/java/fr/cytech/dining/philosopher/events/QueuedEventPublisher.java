package fr.cytech.dining.philosopher.events;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.cytech.dining.common.PhilosopherEvent;

/**
 * Base des envois d'événements : une file bornée vidée par un seul thread.
 *
 * <p>{@link #publish} rend la main immédiatement, donc une panne du moniteur ou de Kafka ne
 * ralentit jamais le philosophe ; le thread unique conserve l'ordre d'émission ; si la file
 * déborde, les plus anciens événements sont abandonnés. On perd alors de l'observation, jamais la
 * correction du protocole.
 */
public abstract class QueuedEventPublisher implements EventPublisher, AutoCloseable {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

    protected QueuedEventPublisher() {
        this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10_000),
                runnable -> {
                    Thread thread = new Thread(runnable, "event-publisher");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy());
    }

    /** Envoie réellement l'événement ; peut bloquer et lever une exception en cas d'échec. */
    protected abstract void deliver(PhilosopherEvent event) throws Exception;

    @Override
    public void publish(PhilosopherEvent event) {
        executor.execute(() -> {
            try {
                deliver(event);
            } catch (Exception e) {
                long count = dropped.incrementAndGet();
                if (count == 1 || count % 50 == 0) {
                    log.warn("Envoi impossible : {} événement(s) perdu(s) ({})", count, e.getMessage());
                }
            }
        });
    }

    /** Nombre d'événements abandonnés parce que le destinataire ne répondait pas. */
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
