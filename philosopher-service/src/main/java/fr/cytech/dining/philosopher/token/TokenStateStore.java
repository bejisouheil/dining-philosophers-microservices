package fr.cytech.dining.philosopher.token;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Mémoire stable d'un philosophe : ce qu'il doit retrouver après une panne pour ne jamais créer
 * ni perdre de jeton.
 *
 * <p>Sans elle, un philosophe redémarré croirait détenir de nouveau le jeton de sa fourchette,
 * alors qu'il l'avait peut-être donné : il y aurait deux jetons pour une fourchette et
 * l'exclusion mutuelle serait rompue.
 */
public interface TokenStateStore {

    /** Jeton en cours d'envoi, pas encore acquitté par son destinataire. */
    record Outgoing(int to, Messages.Token token) {
    }

    /**
     * État sauvegardé.
     *
     * @param clock       horloge de Lamport
     * @param sequence    numéro de la dernière demande émise
     * @param completed   numéro de la dernière demande terminée
     * @param held        jetons détenus (fourchette → version)
     * @param lastVersion plus haute version vue par fourchette
     * @param inFlight    jetons envoyés dont l'accusé de réception n'est pas arrivé
     */
    record State(long clock, long sequence, long completed, Map<Integer, Long> held,
                 Map<Integer, Long> lastVersion, List<Outgoing> inFlight) {
    }

    Optional<State> load();

    void save(State state);

    /** Pas de sauvegarde : l'état disparaît avec le processus (tests, démonstrations courtes). */
    TokenStateStore NONE = new TokenStateStore() {
        @Override
        public Optional<State> load() {
            return Optional.empty();
        }

        @Override
        public void save(State state) {
            // rien
        }
    };
}
