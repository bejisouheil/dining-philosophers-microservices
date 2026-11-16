package fr.cytech.dining.philosopher.core;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import fr.cytech.dining.common.EventType;
import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.common.PhilosopherEvent;
import fr.cytech.dining.philosopher.events.EventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Boucle infinie d'un philosophe : penser, avoir faim, manger, recommencer.
 *
 * <p>À chaque étape le philosophe avance son horloge de Lamport et émet un événement (HUNGRY,
 * EATING, DONE). L'événement DONE est daté <em>avant</em> de rendre les fourchettes : le prochain
 * philosophe à les utiliser aura donc forcément un EATING daté après ce DONE, ce qui permet au
 * moniteur de reconstruire un historique cohérent.
 */
public class PhilosopherLoop {

    private static final Logger log = LoggerFactory.getLogger(PhilosopherLoop.class);

    public enum State { THINKING, HUNGRY, EATING }

    /** Bornes des durées aléatoires de réflexion et de repas, en millisecondes. */
    public record Timing(long thinkMinMs, long thinkMaxMs, long eatMinMs, long eatMaxMs) {
    }

    /** État exposé par l'API REST du philosophe. */
    public record Status(int id, String mode, State state, boolean running, long lamportTime, long meals,
                         long averageWaitMs, long maxWaitMs, List<Integer> forks) {
    }

    private final int id;
    private final String mode;
    private final DiningStrategy strategy;
    private final EventPublisher publisher;
    private final LamportClock clock;
    private final Timing timing;
    private final Counter mealsCounter;
    private final Timer waitTimer;

    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong meals = new AtomicLong();
    private final AtomicLong totalWaitNanos = new AtomicLong();
    private final AtomicLong maxWaitNanos = new AtomicLong();
    private volatile State state = State.THINKING;
    private volatile boolean running;
    private Thread thread;

    public PhilosopherLoop(int id, String mode, DiningStrategy strategy, EventPublisher publisher,
                           LamportClock clock, Timing timing, MeterRegistry registry) {
        this.id = id;
        this.mode = mode;
        this.strategy = strategy;
        this.publisher = publisher;
        this.clock = clock;
        this.timing = timing;
        this.mealsCounter = Counter.builder("philosopher.meals")
                .description("Nombre de repas terminés")
                .register(registry);
        this.waitTimer = Timer.builder("philosopher.wait")
                .description("Temps passé à attendre les fourchettes")
                .register(registry);
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::run, "philosopher-" + id);
        thread.start();
        log.info("Philosophe {} démarré en mode {}", id, mode);
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        thread.interrupt();
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Philosophe {} arrêté après {} repas", id, meals.get());
    }

    private void run() {
        while (running) {
            try {
                runOneCycle();
            } catch (InterruptedException e) {
                break;
            } catch (RuntimeException e) {
                // Une panne (superviseur ou ZooKeeper injoignable...) ne doit pas tuer le philosophe.
                state = State.THINKING;
                log.warn("Philosophe {} : cycle interrompu par une erreur, nouvel essai dans 1 s ({})",
                        id, e.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    break;
                }
            }
        }
    }

    /** Un cycle complet : penser, avoir faim, obtenir les fourchettes, manger, les rendre. */
    public void runOneCycle() throws InterruptedException {
        pause(timing.thinkMinMs(), timing.thinkMaxMs());

        state = State.HUNGRY;
        long hungryAt = System.nanoTime();
        emit(EventType.HUNGRY);

        strategy.acquire();
        recordWait(System.nanoTime() - hungryAt);

        state = State.EATING;
        emit(EventType.EATING);
        try {
            pause(timing.eatMinMs(), timing.eatMaxMs());
        } finally {
            meals.incrementAndGet();
            mealsCounter.increment();
            emit(EventType.DONE);
            state = State.THINKING;
            strategy.release();
        }
    }

    private void emit(EventType type) {
        PhilosopherEvent event = new PhilosopherEvent(id, type, clock.tick(), sequence.incrementAndGet(),
                System.currentTimeMillis(), strategy.forks(), mode);
        log.debug("Philosophe {} : {} (horloge {})", id, type, event.lamportTime());
        publisher.publish(event);
    }

    private void recordWait(long nanos) {
        totalWaitNanos.addAndGet(nanos);
        maxWaitNanos.accumulateAndGet(nanos, Math::max);
        waitTimer.record(nanos, TimeUnit.NANOSECONDS);
    }

    private static void pause(long minMs, long maxMs) throws InterruptedException {
        long duration = maxMs > minMs ? ThreadLocalRandom.current().nextLong(minMs, maxMs + 1) : minMs;
        if (duration > 0) {
            Thread.sleep(duration);
        }
    }

    public Status status() {
        long count = meals.get();
        long average = count == 0 ? 0 : TimeUnit.NANOSECONDS.toMillis(totalWaitNanos.get() / count);
        return new Status(id, mode, state, running, clock.current(), count, average,
                TimeUnit.NANOSECONDS.toMillis(maxWaitNanos.get()), strategy.forks());
    }

    public boolean isRunning() {
        return running;
    }
}
