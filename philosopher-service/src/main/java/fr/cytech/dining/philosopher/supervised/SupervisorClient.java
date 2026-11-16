package fr.cytech.dining.philosopher.supervised;

import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.common.HttpSupport;
import fr.cytech.dining.common.LamportClock;

/**
 * Client HTTP du supervisor-service. Chaque appel transporte l'horloge de Lamport du philosophe
 * et recale celle-ci sur la réponse du superviseur.
 */
public class SupervisorClient {

    record Answer(boolean granted) {
    }

    private final RestClient client;
    private final LamportClock clock;
    private final int philosopherId;

    public SupervisorClient(RestClient client, LamportClock clock, int philosopherId) {
        this.client = client;
        this.clock = clock;
        this.philosopherId = philosopherId;
    }

    public boolean isAvailable(int fork) {
        return granted(client.get().uri("/naive/forks/{fork}/available", fork));
    }

    public boolean acquire(int fork) {
        return granted(client.post().uri("/naive/forks/{fork}/acquire?philosopher={id}", fork, philosopherId));
    }

    public void release(int fork) {
        call(client.post().uri("/naive/forks/{fork}/release?philosopher={id}", fork, philosopherId), Void.class);
    }

    public boolean acquirePair(String variant, int first, int second) {
        return granted(client.post().uri("/{variant}/forks/pair/acquire?first={a}&second={b}&philosopher={id}",
                variant, first, second, philosopherId));
    }

    public void releasePair(String variant, int first, int second) {
        call(client.post().uri("/{variant}/forks/pair/release?first={a}&second={b}&philosopher={id}",
                variant, first, second, philosopherId), Void.class);
    }

    private boolean granted(RestClient.RequestHeadersSpec<?> request) {
        Answer answer = call(request, Answer.class);
        return answer != null && answer.granted();
    }

    private <T> T call(RestClient.RequestHeadersSpec<?> request, Class<T> type) {
        ResponseEntity<T> response = request
                .header(HttpSupport.LAMPORT_HEADER, Long.toString(clock.tick()))
                .retrieve()
                .toEntity(type);
        String supervisorTime = response.getHeaders().getFirst(HttpSupport.LAMPORT_HEADER);
        if (supervisorTime != null) {
            clock.update(Long.parseLong(supervisorTime));
        }
        return response.getBody();
    }
}
