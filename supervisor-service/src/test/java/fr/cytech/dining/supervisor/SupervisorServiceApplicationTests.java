package fr.cytech.dining.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.common.HttpSupport;
import fr.cytech.dining.supervisor.SupervisorController.Answer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SupervisorServiceApplicationTests {

    @Value("${local.server.port}")
    int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.create("http://localhost:" + port);
        client.post().uri("/forks/reset").retrieve().toBodilessEntity();
    }

    private boolean granted(String uri) {
        return client.post().uri(uri).retrieve().body(Answer.class).granted();
    }

    @Test
    void varianteNaiveInterrogerPuisPrendrePuisRendre() {
        assertThat(client.get().uri("/naive/forks/2/available").retrieve().body(Answer.class).granted()).isTrue();
        assertThat(granted("/naive/forks/2/acquire?philosopher=1")).isTrue();
        assertThat(client.get().uri("/naive/forks/2/available").retrieve().body(Answer.class).granted()).isFalse();
        assertThat(granted("/naive/forks/2/acquire?philosopher=3")).isFalse();

        client.post().uri("/naive/forks/2/release?philosopher=1").retrieve().toBodilessEntity();

        assertThat(granted("/naive/forks/2/acquire?philosopher=3")).isTrue();
    }

    @Test
    void varianteVerrouilleeToutOuRien() {
        assertThat(granted("/locked/forks/pair/acquire?first=0&second=1&philosopher=0")).isTrue();
        assertThat(granted("/locked/forks/pair/acquire?first=1&second=2&philosopher=1")).isFalse();
        assertThat(granted("/locked/forks/pair/acquire?first=2&second=3&philosopher=2")).isTrue();

        client.post().uri("/locked/forks/pair/release?first=0&second=1&philosopher=0").retrieve()
                .toBodilessEntity();

        assertThat(granted("/locked/forks/pair/acquire?first=0&second=1&philosopher=4")).isTrue();
    }

    @Test
    void lesTroisVariantesOntDesEtatsSepares() {
        granted("/naive/forks/0/acquire?philosopher=0");
        granted("/unsafe/forks/pair/acquire?first=0&second=1&philosopher=1");

        String forks = client.get().uri("/forks").retrieve().body(String.class);

        assertThat(forks).contains("\"naive\":{\"0\":0}").contains("\"unsafe\":{\"0\":1,\"1\":1}")
                .contains("\"locked\":{}");
    }

    @Test
    void leSuperviseurRenvoieUneHorlogeDeLamportPosterieureACelleDuPhilosophe() {
        ResponseEntity<Answer> response = client.post().uri("/naive/forks/1/acquire?philosopher=0")
                .header(HttpSupport.LAMPORT_HEADER, "41")
                .retrieve().toEntity(Answer.class);

        long supervisorTime = Long.parseLong(response.getHeaders().getFirst(HttpSupport.LAMPORT_HEADER));
        assertThat(supervisorTime).isGreaterThan(41);
    }

    @Test
    void uneFourchetteOuUneVarianteInconnueDonneUneErreur400() {
        assertThatThrownBy(() -> granted("/naive/forks/99/acquire?philosopher=0"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
        assertThatThrownBy(() -> granted("/magique/forks/pair/acquire?first=0&second=1&philosopher=0"))
                .isInstanceOf(HttpClientErrorException.BadRequest.class);
    }
}
