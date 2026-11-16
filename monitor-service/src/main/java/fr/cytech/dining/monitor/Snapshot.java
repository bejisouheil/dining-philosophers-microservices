package fr.cytech.dining.monitor;

import java.util.List;

import fr.cytech.dining.common.PhilosopherEvent;

/**
 * Photographie du système calculée par le moniteur pour le tableau de bord.
 *
 * @param philosophers      état courant de chaque philosophe connu
 * @param recentEvents      derniers événements, du plus récent au plus ancien (ordre de Lamport)
 * @param violations        dernières violations d'exclusion mutuelle détectées
 * @param violationCount    nombre total de violations
 * @param suspectedDeadlock tous les philosophes ont faim depuis longtemps et personne ne mange
 * @param starving          philosophes qui attendent depuis plus longtemps que le seuil
 * @param totalEvents       nombre d'événements en mémoire
 * @param totalMeals        nombre de repas terminés
 * @param generatedAtMillis heure de calcul
 */
public record Snapshot(
        List<PhilosopherView> philosophers,
        List<PhilosopherEvent> recentEvents,
        List<Violation> violations,
        int violationCount,
        boolean suspectedDeadlock,
        List<Integer> starving,
        int totalEvents,
        long totalMeals,
        long generatedAtMillis) {

    /** État d'un philosophe vu par le moniteur. */
    public record PhilosopherView(int id, String state, String mode, long lamportTime, long meals,
                                  long hungryForMs, long maxWaitMs, List<Integer> forks) {
    }

    /** Une même fourchette utilisée par deux philosophes en même temps. */
    public record Violation(int fork, int holder, int intruder, long lamportTime) {
    }
}
