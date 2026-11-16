package fr.cytech.dining.supervisor.forks;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Section 3.1 : les deux fourchettes sont demandées ensemble, mais <strong>sans verrou</strong>.
 *
 * <p>Demander les deux d'un coup supprime l'interblocage (plus personne ne garde une fourchette en
 * attendant l'autre). Mais le serveur web traite les requêtes sur plusieurs threads : entre le
 * test « les deux sont-elles libres ? » et la réservation, un autre thread peut passer le même
 * test. Deux voisins se voient alors attribuer la fourchette qu'ils partagent.
 */
public class UnsafePairForkTable implements PairForkTable {

    private final int forkCount;
    private final RaceWindow raceWindow;
    private final Map<Integer, Integer> owners = new ConcurrentHashMap<>();

    public UnsafePairForkTable(int forkCount, RaceWindow raceWindow) {
        this.forkCount = forkCount;
        this.raceWindow = raceWindow;
    }

    @Override
    public boolean acquirePair(int first, int second, int philosopher) {
        Forks.check(first, second, forkCount);
        if (owners.containsKey(first) || owners.containsKey(second)) {
            return false;
        }
        raceWindow.pause(); // section critique non protégée
        owners.put(first, philosopher);
        owners.put(second, philosopher);
        return true;
    }

    @Override
    public void releasePair(int first, int second, int philosopher) {
        Forks.check(first, second, forkCount);
        owners.remove(first, philosopher);
        owners.remove(second, philosopher);
    }

    @Override
    public Map<Integer, Integer> owners() {
        return new TreeMap<>(owners);
    }

    @Override
    public void reset() {
        owners.clear();
    }
}
