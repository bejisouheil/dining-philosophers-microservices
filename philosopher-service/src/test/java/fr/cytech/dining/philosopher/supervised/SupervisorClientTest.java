package fr.cytech.dining.philosopher.supervised;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import fr.cytech.dining.common.HttpSupport;
import fr.cytech.dining.common.LamportClock;

class SupervisorClientTest {

    private MockRestServiceServer server;
    private LamportClock clock;
    private SupervisorClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://supervisor");
        server = MockRestServiceServer.bindTo(builder).build();
        clock = new LamportClock();
        client = new SupervisorClient(builder.build(), clock, 3);
    }

    private static HttpHeaders lamport(long time) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpSupport.LAMPORT_HEADER, Long.toString(time));
        return headers;
    }

    @Test
    void laDemandeDeFourchetteTransporteLHorlogeEtLIdentifiant() {
        server.expect(requestTo("http://supervisor/naive/forks/2/acquire?philosopher=3"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpSupport.LAMPORT_HEADER, "1"))
                .andRespond(withSuccess("{\"granted\":true}", MediaType.APPLICATION_JSON).headers(lamport(50)));

        assertThat(client.acquire(2)).isTrue();
        assertThat(clock.current()).isEqualTo(51); // recalée sur le superviseur : max(1, 50) + 1
        server.verify();
    }

    @Test
    void unRefusEstTransmis() {
        server.expect(requestTo("http://supervisor/locked/forks/pair/acquire?first=3&second=4&philosopher=3"))
                .andRespond(withSuccess("{\"granted\":false}", MediaType.APPLICATION_JSON));

        assertThat(client.acquirePair("locked", 3, 4)).isFalse();
    }

    @Test
    void laDisponibiliteEstDemandeeEnGet() {
        server.expect(requestTo("http://supervisor/naive/forks/4/available"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"granted\":true}", MediaType.APPLICATION_JSON));

        assertThat(client.isAvailable(4)).isTrue();
    }

    @Test
    void laLiberationAppelleLeSuperviseur() {
        server.expect(requestTo("http://supervisor/unsafe/forks/pair/release?first=3&second=4&philosopher=3"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess());
        server.expect(requestTo("http://supervisor/naive/forks/3/release?philosopher=3"))
                .andRespond(withSuccess());

        client.releasePair("unsafe", 3, 4);
        client.release(3);

        server.verify();
    }
}
