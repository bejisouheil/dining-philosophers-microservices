package fr.cytech.dining.philosopher.supervised;

import java.util.List;

import fr.cytech.dining.philosopher.core.DiningStrategy;

/**
 * Section 2 : le philosophe demande au superviseur si une fourchette est disponible, la prend,
 * puis recommence pour la seconde.
 *
 * <p>Implémentation volontairement naïve : il garde la première fourchette pendant qu'il attend la
 * seconde. Si tous les philosophes prennent leur première fourchette en même temps, plus personne
 * ne peut avancer : c'est l'interblocage.
 */
public class NaiveSupervisedStrategy implements DiningStrategy {

    private final SupervisorClient supervisor;
    private final int firstFork;
    private final int secondFork;
    private final long pollMs;

    public NaiveSupervisedStrategy(SupervisorClient supervisor, int philosopherId, int count, long pollMs) {
        this.supervisor = supervisor;
        this.firstFork = philosopherId;
        this.secondFork = (philosopherId + 1) % count;
        this.pollMs = pollMs;
    }

    @Override
    public List<Integer> forks() {
        return List.of(firstFork, secondFork);
    }

    @Override
    public void acquire() throws InterruptedException {
        take(firstFork);
        take(secondFork);
    }

    private void take(int fork) throws InterruptedException {
        while (!(supervisor.isAvailable(fork) && supervisor.acquire(fork))) {
            Thread.sleep(pollMs);
        }
    }

    @Override
    public void release() {
        supervisor.release(firstFork);
        supervisor.release(secondFork);
    }
}
