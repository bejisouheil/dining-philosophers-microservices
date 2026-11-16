package fr.cytech.dining.philosopher.token;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import fr.cytech.dining.common.LamportClock;

/**
 * Faux réseau pour tester le protocole avec plusieurs philosophes dans une seule JVM. Il sait
 * retarder, perdre, dupliquer et désordonner les messages, ce qu'un vrai réseau fait rarement
 * sur commande.
 *
 * <p>Comme le vrai transport HTTP, il garantit qu'un jeton finit par arriver (il n'est jamais
 * perdu, mais peut être livré deux fois) alors que demandes et libérations peuvent disparaître.
 */
class InMemoryNetwork implements AutoCloseable {

    private final List<TokenNode> nodes = new ArrayList<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(4);
    private final Random random;
    private final int maxDelayMs;
    private final double lossRate;
    private final double duplicateRate;
    final AtomicLong delivered = new AtomicLong();
    final AtomicLong lost = new AtomicLong();
    final AtomicLong duplicated = new AtomicLong();

    InMemoryNetwork(int count, long seed, int maxDelayMs, double lossRate, double duplicateRate) {
        this.random = new Random(seed);
        this.maxDelayMs = maxDelayMs;
        this.lossRate = lossRate;
        this.duplicateRate = duplicateRate;
        for (int id = 0; id < count; id++) {
            nodes.add(new TokenNode(id, count, new LamportClock(), new Link()));
        }
        // Minuteur de retransmission, comme TokenStrategy en production.
        scheduler.scheduleWithFixedDelay(() -> nodes.forEach(TokenNode::retransmit), 20, 20, TimeUnit.MILLISECONDS);
    }

    TokenNode node(int id) {
        return nodes.get(id);
    }

    List<TokenNode> nodes() {
        return nodes;
    }

    private synchronized double nextDouble() {
        return random.nextDouble();
    }

    private synchronized long nextDelay() {
        return maxDelayMs == 0 ? 0 : random.nextInt(maxDelayMs + 1);
    }

    private void deliver(Runnable action, boolean canBeLost) {
        if (canBeLost && nextDouble() < lossRate) {
            lost.incrementAndGet();
            return;
        }
        int copies = nextDouble() < duplicateRate ? 2 : 1;
        if (copies == 2) {
            duplicated.incrementAndGet();
        }
        for (int copy = 0; copy < copies; copy++) {
            scheduler.schedule(() -> {
                delivered.incrementAndGet();
                action.run();
            }, nextDelay(), TimeUnit.MILLISECONDS);
        }
    }

    private final class Link implements PeerTransport {

        @Override
        public void sendRequest(int to, Messages.Request message) {
            deliver(() -> nodes.get(to).onRequest(message), true);
        }

        @Override
        public void sendRelease(int to, Messages.Release message) {
            deliver(() -> nodes.get(to).onRelease(message), true);
        }

        @Override
        public void sendToken(int to, Messages.Token message, Runnable acknowledged) {
            deliver(() -> {
                nodes.get(to).onToken(message);
                acknowledged.run();
            }, false);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
