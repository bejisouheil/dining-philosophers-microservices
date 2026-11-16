package fr.cytech.dining.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.common.EventType;
import fr.cytech.dining.common.PhilosopherEvent;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MonitorServiceApplicationTests {

    @Value("${local.server.port}")
    int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        client.delete().uri("/api/events").retrieve().toBodilessEntity();
    }

    private HttpStatus post(PhilosopherEvent event) {
        return HttpStatus.valueOf(client.post().uri("/events").contentType(MediaType.APPLICATION_JSON).body(event)
                .retrieve().toBodilessEntity().getStatusCode().value());
    }

    @Test
    void lesEvenementsRecusAlimententLeTableauDeBord() {
        assertThat(post(new PhilosopherEvent(0, EventType.HUNGRY, 1, 1, 1_000, List.of(0, 1), "token")))
                .isEqualTo(HttpStatus.ACCEPTED);
        post(new PhilosopherEvent(0, EventType.EATING, 2, 2, 1_100, List.of(0, 1), "token"));
        post(new PhilosopherEvent(1, EventType.HUNGRY, 1, 1, 1_050, List.of(1, 2), "token"));

        Snapshot snapshot = client.get().uri("/api/snapshot").retrieve().body(Snapshot.class);

        assertThat(snapshot.philosophers()).hasSize(2);
        assertThat(snapshot.philosophers().get(0).state()).isEqualTo("EATING");
        assertThat(snapshot.philosophers().get(0).mode()).isEqualTo("token");
        assertThat(snapshot.philosophers().get(1).state()).isEqualTo("HUNGRY");
        assertThat(snapshot.violationCount()).isZero();
    }

    @Test
    void lHistoriqueEstTrieParHorlogeEtFiltrableParPhilosophe() {
        post(new PhilosopherEvent(1, EventType.HUNGRY, 5, 1, 1_000, List.of(), "spoiled"));
        post(new PhilosopherEvent(0, EventType.HUNGRY, 3, 1, 1_000, List.of(), "spoiled"));
        post(new PhilosopherEvent(0, EventType.EATING, 4, 2, 1_000, List.of(), "spoiled"));

        PhilosopherEvent[] all = client.get().uri("/api/events").retrieve().body(PhilosopherEvent[].class);
        PhilosopherEvent[] onlyZero = client.get().uri("/api/events?philosopher=0").retrieve()
                .body(PhilosopherEvent[].class);

        assertThat(all).extracting(PhilosopherEvent::lamportTime).containsExactly(3L, 4L, 5L);
        assertThat(onlyZero).hasSize(2);
    }

    @Test
    void leTableauDeBordEstServiALaRacine() {
        String html = client.get().uri("/").retrieve().body(String.class);
        assertThat(html).contains("Dîner des philosophes");
    }
}
