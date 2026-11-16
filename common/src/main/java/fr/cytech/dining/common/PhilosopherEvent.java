package fr.cytech.dining.common;

import java.util.List;

/**
 * Événement envoyé par un philosophe au moniteur.
 *
 * @param philosopherId   identifiant du philosophe émetteur
 * @param type            HUNGRY, EATING ou DONE
 * @param lamportTime     date de l'horloge logique du philosophe au moment de l'événement
 * @param sequence        numéro d'ordre local (1, 2, 3...) : sert à ignorer les doublons
 * @param wallClockMillis heure réelle de la machine, purement indicative
 * @param forks           fourchettes concernées (vide pour les philosophes gâtés)
 * @param mode            variante en cours (spoiled, token, zookeeper...)
 */
public record PhilosopherEvent(
        int philosopherId,
        EventType type,
        long lamportTime,
        long sequence,
        long wallClockMillis,
        List<Integer> forks,
        String mode) {

    public PhilosopherEvent {
        forks = forks == null ? List.of() : List.copyOf(forks);
    }
}
