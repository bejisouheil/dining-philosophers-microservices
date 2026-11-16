package fr.cytech.dining.philosopher.supervised;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import fr.cytech.dining.philosopher.core.DiningStrategy;
import fr.cytech.dining.philosopher.core.ForkAssignment;

/**
 * Sections 3.1 et 3.2 : le philosophe demande ses deux fourchettes en une seule requête. Le
 * superviseur répond « les deux » ou « aucune » ; en cas de refus le philosophe réessaie un peu
 * plus tard (avec une part de hasard pour éviter que deux voisins se resynchronisent sans fin).
 *
 * @see fr.cytech.dining.philosopher.config.Mode#SUPERVISED_UNSAFE
 * @see fr.cytech.dining.philosopher.config.Mode#SUPERVISED_LOCKED
 */
public class PairSupervisedStrategy implements DiningStrategy {

    private final SupervisorClient supervisor;
    private final String variant;
    private final List<Integer> forks;
    private final long pollMs;

    public PairSupervisedStrategy(SupervisorClient supervisor, String variant, int philosopherId, int count,
                                  long pollMs) {
        this.supervisor = supervisor;
        this.variant = variant;
        this.forks = ForkAssignment.forksFor(philosopherId, count);
        this.pollMs = pollMs;
    }

    @Override
    public List<Integer> forks() {
        return forks;
    }

    @Override
    public void acquire() throws InterruptedException {
        while (!supervisor.acquirePair(variant, forks.get(0), forks.get(1))) {
            Thread.sleep(pollMs + ThreadLocalRandom.current().nextLong(pollMs + 1));
        }
    }

    @Override
    public void release() {
        supervisor.releasePair(variant, forks.get(0), forks.get(1));
    }
}
