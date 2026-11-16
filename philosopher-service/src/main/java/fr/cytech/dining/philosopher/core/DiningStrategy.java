package fr.cytech.dining.philosopher.core;

import java.util.List;

/**
 * Manière d'obtenir le droit de manger. Une implémentation par section du sujet : la boucle du
 * philosophe ({@link PhilosopherLoop}) reste la même, seule la stratégie change.
 */
public interface DiningStrategy {

    /** Fourchettes que ce philosophe compte utiliser (vide s'il n'en partage aucune). */
    List<Integer> forks();

    /** Bloque jusqu'à ce que le philosophe ait le droit de manger. */
    void acquire() throws InterruptedException;

    /** Rend les fourchettes à la fin du repas. */
    void release();

    /** Libère les ressources (connexions, threads) à l'arrêt du service. */
    default void close() {
    }
}
