package fr.cytech.dining.supervisor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration du superviseur.
 *
 * @param forks        nombre de fourchettes (égal au nombre de philosophes)
 * @param raceWindowMs pause artificielle entre le test et la réservation, pour rendre les
 *                     situations de compétition visibles en démonstration (0 = aucune)
 */
@ConfigurationProperties("supervisor")
public record SupervisorProperties(
        @DefaultValue("5") int forks,
        @DefaultValue("0") long raceWindowMs) {
}
