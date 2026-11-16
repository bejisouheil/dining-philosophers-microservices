package fr.cytech.dining.philosopher.token;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import fr.cytech.dining.philosopher.core.DiningStrategy;

/**
 * Section 3.3 : branche la boucle du philosophe sur le protocole par jetons. Un minuteur
 * rediffuse la demande tant que le philosophe attend, pour rattraper les messages perdus.
 */
public class TokenStrategy implements DiningStrategy {

    private final TokenNode node;
    private final List<Integer> forks;
    private final ScheduledExecutorService retransmitter;

    public TokenStrategy(TokenNode node, List<Integer> forks, long retransmitMs) {
        this.node = node;
        this.forks = List.copyOf(forks);
        this.retransmitter = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "token-retransmit-" + node.id());
            thread.setDaemon(true);
            return thread;
        });
        retransmitter.scheduleWithFixedDelay(node::retransmit, retransmitMs, retransmitMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<Integer> forks() {
        return forks;
    }

    @Override
    public void acquire() throws InterruptedException {
        try {
            node.request(forks).get();
        } catch (InterruptedException e) {
            node.release(); // on abandonne proprement : les autres ne doivent pas attendre après nous
            throw e;
        } catch (ExecutionException | CancellationException e) {
            throw new IllegalStateException("Demande de fourchettes interrompue", e);
        }
    }

    @Override
    public void release() {
        node.release();
    }

    @Override
    public void close() {
        retransmitter.shutdownNow();
    }
}
