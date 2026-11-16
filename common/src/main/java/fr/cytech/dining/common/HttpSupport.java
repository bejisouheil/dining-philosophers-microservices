package fr.cytech.dining.common;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** Fabrique de clients HTTP avec délais d'attente : aucun appel réseau ne doit bloquer indéfiniment. */
public final class HttpSupport {

    /** En-tête qui transporte l'horloge de Lamport de l'émetteur dans les requêtes et les réponses. */
    public static final String LAMPORT_HEADER = "X-Lamport-Time";

    private HttpSupport() {
    }

    public static RestClient restClient(String baseUrl, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
    }
}
