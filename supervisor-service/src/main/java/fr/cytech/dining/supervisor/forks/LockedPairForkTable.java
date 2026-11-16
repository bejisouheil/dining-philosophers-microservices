package fr.cytech.dining.supervisor.forks;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Section 3.2 : la section critique est protégée par un verrou.
 *
 * <p>« Vérifier que les deux fourchettes sont libres, puis les réserver » s'exécute d'un seul
 * bloc : un seul thread à la fois peut lire et modifier l'état des fourchettes. La pause
 * {@link RaceWindow} est conservée au même endroit que dans la version sans verrou pour montrer
 * que c'est bien le verrou, et non la chance, qui garantit l'exclusion mutuelle.
 *
 * <p>Le verrou est équitable (FIFO) : les threads entrent en section critique dans leur ordre
 * d'arrivée.
 */
public class LockedPairForkTable implements PairForkTable {

    private final int forkCount;
    private final RaceWindow raceWindow;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Map<Integer, Integer> owners = new HashMap<>();

    public LockedPairForkTable(int forkCount, RaceWindow raceWindow) {
        this.forkCount = forkCount;
        this.raceWindow = raceWindow;
    }

    @Override
    public boolean acquirePair(int first, int second, int philosopher) {
        Forks.check(first, second, forkCount);
        lock.lock();
        try {
            if (owners.containsKey(first) || owners.containsKey(second)) {
                return false;
            }
            raceWindow.pause(); // sans danger : personne d'autre ne peut entrer ici
            owners.put(first, philosopher);
            owners.put(second, philosopher);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void releasePair(int first, int second, int philosopher) {
        Forks.check(first, second, forkCount);
        lock.lock();
        try {
            // Seul le détenteur peut libérer : corrige une faille du superviseur naïf.
            owners.remove(first, philosopher);
            owners.remove(second, philosopher);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Map<Integer, Integer> owners() {
        lock.lock();
        try {
            return new TreeMap<>(owners);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void reset() {
        lock.lock();
        try {
            owners.clear();
        } finally {
            lock.unlock();
        }
    }
}
