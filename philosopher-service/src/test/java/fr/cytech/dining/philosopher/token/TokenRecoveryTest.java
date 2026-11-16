package fr.cytech.dining.philosopher.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.philosopher.token.TokenNode.State;

/**
 * Résilience : arrêt puis redémarrage d'un philosophe. Le redémarrage est simulé en créant un
 * nouveau {@link TokenNode} sur la même mémoire stable.
 */
class TokenRecoveryTest {

    /** Mémoire stable en mémoire vive : suffit pour simuler un redémarrage dans un test. */
    private static final class MemoryStore implements TokenStateStore {
        private State saved;
        private int saves;

        @Override
        public Optional<State> load() {
            return Optional.ofNullable(saved);
        }

        @Override
        public void save(State state) {
            saved = state;
            saves++;
        }
    }

    private final PeerTransport transport = mock(PeerTransport.class);
    private final MemoryStore store = new MemoryStore();

    private TokenNode start() {
        return new TokenNode(1, 5, new LamportClock(), transport, store);
    }

    private static Messages.Request requestFrom(int philosopher, long timestamp, Integer... forks) {
        return new Messages.Request(new ForkRequest(philosopher, 1, timestamp, List.of(forks)), timestamp, 0);
    }

    @Test
    @DisplayName("Sûreté : un philosophe redémarré ne recrée pas le jeton qu'il avait donné")
    void pasDeJetonRecreeApresRedemarrage() {
        TokenNode before = start();
        before.onRequest(requestFrom(0, 5, 0, 1)); // donne le jeton de la fourchette 1 au philosophe 0

        TokenNode after = start(); // panne puis redémarrage

        assertThat(after.snapshot().heldTokens()).as("le jeton 1 est chez le philosophe 0, pas ici").isEmpty();
    }

    @Test
    void lesJetonsDetenusSontRetrouvesApresRedemarrage() {
        TokenNode before = start();
        before.onToken(new Messages.Token(2, 4, 2, 9, 0));

        TokenNode after = start();

        assertThat(after.snapshot().heldTokens()).containsEntry(1, 0L).containsEntry(2, 4L);
    }

    @Test
    @DisplayName("Un jeton envoyé mais pas acquitté est renvoyé après le redémarrage")
    void leJetonEnVolEstRenvoye() {
        TokenNode before = start();
        before.onRequest(requestFrom(0, 5, 0, 1)); // l'envoi part, la panne survient avant l'accusé
        assertThat(before.snapshot().tokensInFlight()).containsEntry(1, 0);
        clearInvocations(transport);

        TokenNode after = start();
        after.recover();

        ArgumentCaptor<Messages.Token> resent = ArgumentCaptor.forClass(Messages.Token.class);
        verify(transport).sendToken(eq(0), resent.capture(), any());
        assertThat(resent.getValue().fork()).isEqualTo(1);
        assertThat(resent.getValue().version()).as("même version : le destinataire écartera un doublon").isEqualTo(1);
    }

    @Test
    void unJetonAcquitteNEstPlusRenvoye() {
        TokenNode before = start();
        before.onRequest(requestFrom(0, 5, 0, 1));
        ArgumentCaptor<Runnable> acknowledged = ArgumentCaptor.forClass(Runnable.class);
        verify(transport).sendToken(eq(0), any(), acknowledged.capture());
        acknowledged.getValue().run(); // le philosophe 0 confirme la réception
        clearInvocations(transport);

        TokenNode after = start();
        after.recover();

        assertThat(after.snapshot().tokensInFlight()).isEmpty();
        verify(transport, never()).sendToken(anyInt(), any(), any());
    }

    @Test
    @DisplayName("Une panne en plein repas : la demande est abandonnée et les autres en sont informés")
    void panneEnMangeant() {
        TokenNode before = start();
        before.request(List.of(1, 2));
        before.onToken(new Messages.Token(2, 1, 2, 3, 0));
        assertThat(before.snapshot().state()).isEqualTo(State.EATING);
        clearInvocations(transport);

        TokenNode after = start();
        after.recover();

        assertThat(after.snapshot().state()).isEqualTo(State.IDLE);
        ArgumentCaptor<Messages.Release> release = ArgumentCaptor.forClass(Messages.Release.class);
        verify(transport, times(4)).sendRelease(anyInt(), release.capture());
        assertThat(release.getValue().sequence()).isEqualTo(1);
        assertThat(after.snapshot().heldTokens()).containsOnlyKeys(1, 2); // les fourchettes ne sont pas perdues
    }

    @Test
    void laNumerotationDesDemandesEtLHorlogeReprennentOuEllesEnEtaient() {
        TokenNode before = start();
        before.onRequest(requestFrom(3, 500, 3, 4)); // horloge > 500
        before.request(List.of(1, 2));               // demande n° 1
        long clockBefore = before.snapshot().clock();

        TokenNode after = start();
        after.request(List.of(1, 2));

        assertThat(after.snapshot().currentRequest().sequence()).isEqualTo(2);
        assertThat(after.snapshot().currentRequest().timestamp()).isGreaterThan(clockBefore);
    }

    @Test
    void lEtatEstSauvegardeAvantQueLeJetonNeParte() {
        TokenNode node = start();
        int savesBefore = store.saves;

        node.onRequest(requestFrom(0, 5, 0, 1));

        assertThat(store.saves).isGreaterThan(savesBefore);
        assertThat(store.saved.held()).doesNotContainKey(1);
        assertThat(store.saved.inFlight()).hasSize(1);
    }

    @Test
    void laSauvegardeSurFichierSurvitAUnRedemarrage(@TempDir Path directory) {
        Path file = directory.resolve("etat").resolve("philosopher-1.json");
        TokenNode before = new TokenNode(1, 5, new LamportClock(), transport, new FileTokenStateStore(file));
        before.onToken(new Messages.Token(2, 4, 2, 9, 0));
        before.onRequest(requestFrom(0, 12, 0, 1));

        TokenNode after = new TokenNode(1, 5, new LamportClock(), transport, new FileTokenStateStore(file));

        assertThat(file).exists();
        assertThat(after.snapshot().heldTokens()).containsOnlyKeys(2);
        assertThat(after.snapshot().tokensInFlight()).containsEntry(1, 0);
        assertThat(after.snapshot().clock()).isGreaterThan(12);
    }

    @Test
    void sansFichierOnPartDeLEtatInitial(@TempDir Path directory) {
        FileTokenStateStore fileStore = new FileTokenStateStore(directory.resolve("philosopher-1.json"));

        assertThat(fileStore.load()).isEmpty();
        TokenNode node = new TokenNode(1, 5, new LamportClock(), transport, fileStore);
        assertThat(node.snapshot().heldTokens()).containsOnlyKeys(1);
        assertThat(fileStore.load()).isPresent(); // l'état initial est écrit dès le premier démarrage
    }
}
