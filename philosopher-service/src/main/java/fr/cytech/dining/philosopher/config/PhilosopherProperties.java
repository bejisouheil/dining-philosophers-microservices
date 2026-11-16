package fr.cytech.dining.philosopher.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration d'un philosophe (préfixe {@code philosopher} dans application.yml ou variables
 * d'environnement {@code PHILOSOPHER_*}).
 *
 * @param id               identifiant (0..count-1) ; -1 = le déduire du nom d'hôte ou du port
 * @param count            nombre total de philosophes (et donc de fourchettes)
 * @param mode             variante du problème
 * @param autoStart        démarrer la boucle penser/manger dès le lancement
 * @param thinkMinMs       durée minimale de réflexion
 * @param thinkMaxMs       durée maximale de réflexion
 * @param eatMinMs         durée minimale d'un repas
 * @param eatMaxMs         durée maximale d'un repas
 * @param monitorUrl       adresse du monitor-service
 * @param supervisorUrl    adresse du supervisor-service (sections 2 et 3.1-3.2)
 * @param eventsTransport  envoi des événements : http, kafka ou none
 * @param eventsTopic      topic Kafka des événements
 * @param peerUrlTemplate  adresse des autres philosophes ; {id} et {port} sont remplacés
 * @param basePort         port du philosophe 0 (le philosophe i écoute sur basePort + i en local)
 * @param zookeeperConnect adresse de ZooKeeper (section 3.4)
 * @param requestTimeoutMs délai maximal d'un appel HTTP
 * @param retransmitMs     période de retransmission d'une demande restée sans effet (section 3.3)
 * @param pollMs           période d'interrogation du superviseur quand une fourchette est prise
 */
@ConfigurationProperties("philosopher")
public record PhilosopherProperties(
        @DefaultValue("-1") int id,
        @DefaultValue("5") int count,
        @DefaultValue("spoiled") Mode mode,
        @DefaultValue("true") boolean autoStart,
        @DefaultValue("500") long thinkMinMs,
        @DefaultValue("1500") long thinkMaxMs,
        @DefaultValue("300") long eatMinMs,
        @DefaultValue("900") long eatMaxMs,
        @DefaultValue("http://localhost:8090") String monitorUrl,
        @DefaultValue("http://localhost:8080") String supervisorUrl,
        @DefaultValue("http") String eventsTransport,
        @DefaultValue("philosopher-events") String eventsTopic,
        @DefaultValue("http://localhost:{port}") String peerUrlTemplate,
        @DefaultValue("8081") int basePort,
        @DefaultValue("localhost:2181") String zookeeperConnect,
        @DefaultValue("2000") long requestTimeoutMs,
        @DefaultValue("1000") long retransmitMs,
        @DefaultValue("50") long pollMs) {

    /** Adresse du philosophe {@code peerId} d'après le gabarit configuré. */
    public String peerUrl(int peerId) {
        return peerUrlTemplate
                .replace("{id}", Integer.toString(peerId))
                .replace("{port}", Integer.toString(basePort + peerId));
    }
}
