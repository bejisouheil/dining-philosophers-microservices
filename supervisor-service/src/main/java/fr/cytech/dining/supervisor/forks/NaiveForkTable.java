package fr.cytech.dining.supervisor.forks;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Section 2 : superviseur naïf. Il attribue une fourchette si elle est libre et accepte sa
 * libération, sans aucun contrôle global.
 *
 * <p>Failles volontaires, recensées par {@code NaiveForkTableTest} :
 * <ol>
 *   <li>« vérifier puis prendre » n'est pas atomique : deux philosophes peuvent obtenir la même
 *       fourchette ;</li>
 *   <li>la réponse à « est-elle disponible ? » peut être périmée quand la demande arrive ;</li>
 *   <li>n'importe qui peut libérer la fourchette d'un autre ;</li>
 *   <li>aucune vision d'ensemble : chacun peut garder une fourchette en attendant l'autre, d'où
 *       l'interblocage ;</li>
 *   <li>aucune limite : un philosophe peut accaparer toutes les fourchettes (famine).</li>
 * </ol>
 *
 * <p>La table est une {@link ConcurrentHashMap} : chaque opération isolée est sûre. Les failles
 * viennent de l'enchaînement de plusieurs opérations, pas de la structure de données.
 */
public class NaiveForkTable {

    private final int forkCount;
    private final RaceWindow raceWindow;
    private final Map<Integer, Integer> owners = new ConcurrentHashMap<>();

    public NaiveForkTable(int forkCount, RaceWindow raceWindow) {
        this.forkCount = forkCount;
        this.raceWindow = raceWindow;
    }

    public boolean isAvailable(int fork) {
        checkFork(fork);
        return !owners.containsKey(fork);
    }

    public boolean acquire(int fork, int philosopher) {
        checkFork(fork);
        if (owners.containsKey(fork)) {
            return false;
        }
        raceWindow.pause(); // un autre thread peut passer le même test ici
        owners.put(fork, philosopher);
        return true;
    }

    public void release(int fork, int philosopher) {
        checkFork(fork);
        owners.remove(fork); // on ne vérifie pas que « philosopher » est bien le détenteur
    }

    public Map<Integer, Integer> owners() {
        return new TreeMap<>(owners);
    }

    public void reset() {
        owners.clear();
    }

    private void checkFork(int fork) {
        if (fork < 0 || fork >= forkCount) {
            throw new IllegalArgumentException("Fourchette inconnue : " + fork);
        }
    }
}
