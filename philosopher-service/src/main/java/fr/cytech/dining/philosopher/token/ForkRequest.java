package fr.cytech.dining.philosopher.token;

import java.util.Comparator;
import java.util.List;

/**
 * Demande horodatée d'un philosophe pour deux fourchettes.
 *
 * <p>L'ordre naturel est la <strong>priorité</strong> : la plus petite date de Lamport d'abord ;
 * à date égale, le plus petit identifiant. Comme deux demandes n'ont jamais la même paire
 * (date, identifiant), tous les philosophes classent les demandes exactement de la même façon :
 * c'est cet ordre total partagé qui empêche l'interblocage et la famine.
 *
 * @param philosopherId demandeur
 * @param sequence      numéro de la demande chez ce demandeur (1, 2, 3...)
 * @param timestamp     date de Lamport de la demande
 * @param forks         les deux fourchettes demandées
 */
public record ForkRequest(int philosopherId, long sequence, long timestamp, List<Integer> forks)
        implements Comparable<ForkRequest> {

    private static final Comparator<ForkRequest> PRIORITY = Comparator
            .comparingLong(ForkRequest::timestamp)
            .thenComparingInt(ForkRequest::philosopherId)
            .thenComparingLong(ForkRequest::sequence);

    public ForkRequest {
        forks = List.copyOf(forks);
    }

    public boolean needs(int fork) {
        return forks.contains(fork);
    }

    @Override
    public int compareTo(ForkRequest other) {
        return PRIORITY.compare(this, other);
    }
}
