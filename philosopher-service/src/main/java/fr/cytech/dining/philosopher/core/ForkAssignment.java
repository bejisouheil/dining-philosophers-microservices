package fr.cytech.dining.philosopher.core;

import java.util.List;

/**
 * Règle d'attribution automatique des deux fourchettes d'un philosophe.
 *
 * <p>Le philosophe {@code i} reçoit la fourchette {@code i} (la sienne) et la fourchette
 * {@code (i + 1) mod N}. Chaque fourchette est ainsi partagée par exactement deux philosophes et
 * la charge est équilibrée. La liste est triée : prendre les ressources dans un ordre global fixe
 * est la manière classique d'éviter l'attente circulaire.
 */
public final class ForkAssignment {

    private ForkAssignment() {
    }

    public static List<Integer> forksFor(int philosopherId, int count) {
        if (count < 2) {
            throw new IllegalArgumentException("Il faut au moins 2 philosophes pour partager des fourchettes");
        }
        int own = philosopherId;
        int next = (philosopherId + 1) % count;
        return own < next ? List.of(own, next) : List.of(next, own);
    }
}
