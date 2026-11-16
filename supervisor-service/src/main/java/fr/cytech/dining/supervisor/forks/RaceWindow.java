package fr.cytech.dining.supervisor.forks;

/**
 * Instant qui sépare « je vérifie que la fourchette est libre » de « je la marque comme prise ».
 *
 * <p>En production cet instant dure quelques nanosecondes. On le rend réglable pour pouvoir
 * provoquer à coup sûr la situation de compétition dans les tests et les démonstrations.
 */
@FunctionalInterface
public interface RaceWindow {

    void pause();

    /** Aucune pause : la fenêtre de course existe quand même, mais elle est minuscule. */
    RaceWindow NONE = () -> { };

    /** Pause fixe, pour élargir la fenêtre de course. */
    static RaceWindow ofMillis(long millis) {
        if (millis <= 0) {
            return NONE;
        }
        return () -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
    }
}
