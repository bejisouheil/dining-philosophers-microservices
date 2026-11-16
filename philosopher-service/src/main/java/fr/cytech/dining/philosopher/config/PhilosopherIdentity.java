package fr.cytech.dining.philosopher.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Identifiant du philosophe et nombre total de philosophes.
 *
 * <p>L'identifiant vient, dans l'ordre : de la propriété {@code philosopher.id} si elle est
 * positive ; sinon du nom d'hôte s'il finit par {@code -N} (pod {@code philosopher-3} d'un
 * StatefulSet Kubernetes, conteneur {@code philosopher-3} de Docker Compose) ; sinon du port
 * ({@code port - basePort}, pratique en local : PORT=8083 donne le philosophe 2).
 */
public record PhilosopherIdentity(int id, int count) {

    private static final Pattern TRAILING_NUMBER = Pattern.compile(".*-(\\d+)$");

    public PhilosopherIdentity {
        if (count < 1) {
            throw new IllegalArgumentException("philosopher.count doit être >= 1");
        }
        if (id < 0 || id >= count) {
            throw new IllegalArgumentException(
                    "Identifiant de philosophe invalide : " + id + " (attendu entre 0 et " + (count - 1) + ")");
        }
    }

    public static PhilosopherIdentity resolve(PhilosopherProperties properties, String hostname, int serverPort) {
        return new PhilosopherIdentity(resolveId(properties, hostname, serverPort), properties.count());
    }

    static int resolveId(PhilosopherProperties properties, String hostname, int serverPort) {
        if (properties.id() >= 0) {
            return properties.id();
        }
        if (hostname != null) {
            Matcher matcher = TRAILING_NUMBER.matcher(hostname);
            if (matcher.matches()) {
                return Integer.parseInt(matcher.group(1));
            }
        }
        int fromPort = serverPort - properties.basePort();
        if (fromPort >= 0 && fromPort < properties.count()) {
            return fromPort;
        }
        return 0;
    }
}
