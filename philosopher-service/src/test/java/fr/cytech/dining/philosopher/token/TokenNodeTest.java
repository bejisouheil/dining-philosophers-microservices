package fr.cytech.dining.philosopher.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.cytech.dining.common.LamportClock;
import fr.cytech.dining.philosopher.token.TokenNode.State;

/**
 * Tests unitaires du protocole par jetons : un seul philosophe (le n° 1 sur 5), le réseau est
 * remplacé par un faux transport Mockito. On vérifie chaque règle isolément.
 */
class TokenNodeTest {

    private static final int COUNT = 5;
    private static final List<Integer> MY_FORKS = List.of(1, 2);

    private PeerTransport transport;
    private LamportClock clock;
    private TokenNode node; // philosophe 1 : détient au départ le jeton de la fourchette 1

    @BeforeEach
    void setUp() {
        transport = mock(PeerTransport.class);
        clock = new LamportClock();
        node = new TokenNode(1, COUNT, clock, transport);
    }

    private static Messages.Request requestFrom(int philosopher, long sequence, long timestamp, Integer... forks) {
        return new Messages.Request(new ForkRequest(philosopher, sequence, timestamp, List.of(forks)), timestamp,
                sequence - 1);
    }

    private static Messages.Token token(int fork, long version, int from) {
        return new Messages.Token(fork, version, from, 1, 0);
    }

    @Nested
    @DisplayName("Demande horodatée et diffusion")
    class Demande {

        @Test
        void auDepartChaquePhilosopheDetientLeJetonDeSaFourchette() {
            assertThat(node.snapshot().heldTokens()).containsOnlyKeys(1);
            assertThat(node.snapshot().state()).isEqualTo(State.IDLE);
        }

        @Test
        void laDemandeEstDiffuseeATousLesAutresAvecUneDateDeLamport() {
            node.request(MY_FORKS);

            ArgumentCaptor<Messages.Request> sent = ArgumentCaptor.forClass(Messages.Request.class);
            for (int peer : new int[] {0, 2, 3, 4}) {
                verify(transport).sendRequest(eq(peer), sent.capture());
            }
            verify(transport, never()).sendRequest(eq(1), any());
            ForkRequest request = sent.getValue().request();
            assertThat(request.philosopherId()).isEqualTo(1);
            assertThat(request.sequence()).isEqualTo(1);
            assertThat(request.timestamp()).isEqualTo(1);
            assertThat(request.forks()).containsExactly(1, 2);
            assertThat(node.snapshot().state()).isEqualTo(State.WAITING);
        }

        @Test
        void uneDemandeRecueEstMemoriseeDansLOrdreDePriorite() {
            node.onRequest(requestFrom(3, 1, 9, 3, 4));
            node.onRequest(requestFrom(4, 1, 5, 3, 4));
            node.onRequest(requestFrom(0, 1, 9, 4, 0)); // même date que le philosophe 3 : l'identifiant départage

            assertThat(node.snapshot().pendingRequests()).extracting(ForkRequest::philosopherId)
                    .containsExactly(4, 0, 3);
        }

        @Test
        void recevoirUneDemandeRecaleLHorlogeDoncMaProchaineDemandeEstDateeApres() {
            node.onRequest(requestFrom(3, 1, 100, 3, 4));

            node.request(MY_FORKS);

            assertThat(node.snapshot().currentRequest().timestamp()).isGreaterThan(100);
        }

        @Test
        void laRetransmissionRediffuseLaMemeDemandeTantQueLOnAttend() {
            node.request(MY_FORKS);
            long timestamp = node.snapshot().currentRequest().timestamp();
            clearInvocations(transport);

            node.retransmit();

            ArgumentCaptor<Messages.Request> sent = ArgumentCaptor.forClass(Messages.Request.class);
            verify(transport, times(4)).sendRequest(anyInt(), sent.capture());
            assertThat(sent.getValue().request().timestamp()).isEqualTo(timestamp); // la priorité ne change pas
            assertThat(sent.getValue().request().sequence()).isEqualTo(1);
        }

        @Test
        void pasDeRetransmissionQuandOnNAttendRien() {
            node.retransmit();
            verifyNoInteractions(transport);
        }
    }

    @Nested
    @DisplayName("Règle de transfert des jetons")
    class Transfert {

        @Test
        void unPhilosopheQuiNAPasFaimDonneSonJetonAuDemandeur() {
            node.onRequest(requestFrom(0, 1, 5, 0, 1));

            ArgumentCaptor<Messages.Token> sent = ArgumentCaptor.forClass(Messages.Token.class);
            verify(transport).sendToken(eq(0), sent.capture(), any());
            assertThat(sent.getValue().fork()).isEqualTo(1);
            assertThat(sent.getValue().version()).isEqualTo(1);
            assertThat(node.snapshot().heldTokens()).isEmpty();
        }

        @Test
        void unJetonDontPersonneNeVeutResteOuIlEst() {
            node.onRequest(requestFrom(3, 1, 5, 3, 4)); // ne concerne pas la fourchette 1

            verify(transport, never()).sendToken(anyInt(), any(), any());
            assertThat(node.snapshot().heldTokens()).containsOnlyKeys(1);
        }

        @Test
        void enAttenteJeGardeMonJetonFaceAUneDemandePlusRecente() {
            node.request(MY_FORKS); // date 1
            node.onRequest(requestFrom(0, 1, 50, 0, 1)); // date 50 : moins prioritaire

            verify(transport, never()).sendToken(anyInt(), any(), any());
            assertThat(node.snapshot().heldTokens()).containsOnlyKeys(1);
        }

        @Test
        void enAttenteJeCedeMonJetonAUneDemandePlusAncienne() {
            node.onRequest(requestFrom(3, 1, 20, 3, 4)); // fait avancer mon horloge au-delà de 20
            node.request(MY_FORKS);                      // ma demande est donc datée après 20
            node.onRequest(requestFrom(0, 1, 2, 0, 1));  // demande plus ancienne arrivée en retard

            verify(transport).sendToken(eq(0), any(), any()); // « tenir et attendre » est interdit
            assertThat(node.snapshot().state()).isEqualTo(State.WAITING);
        }

        @Test
        void aDateEgaleLePlusPetitIdentifiantLEmporte() {
            node.request(MY_FORKS); // date 1, identifiant 1
            node.onRequest(requestFrom(0, 1, 1, 0, 1)); // date 1, identifiant 0 : prioritaire

            verify(transport).sendToken(eq(0), any(), any());
        }

        @Test
        void leJetonVaAuDemandeurLePlusPrioritaireParmiPlusieurs() {
            // Un philosophe qui détient deux jetons sert chaque fourchette à celui qui la demande.
            TokenNode holder = new TokenNode(2, COUNT, new LamportClock(), transport); // détient la fourchette 2
            holder.onToken(token(3, 1, 3)); // et aussi la 3
            clearInvocations(transport);

            holder.onRequest(requestFrom(3, 1, 30, 3, 4));
            ArgumentCaptor<Messages.Token> first = ArgumentCaptor.forClass(Messages.Token.class);
            verify(transport).sendToken(eq(3), first.capture(), any());
            assertThat(first.getValue().fork()).isEqualTo(3);

            holder.onRequest(requestFrom(1, 1, 10, 1, 2));
            ArgumentCaptor<Messages.Token> second = ArgumentCaptor.forClass(Messages.Token.class);
            verify(transport).sendToken(eq(1), second.capture(), any());
            assertThat(second.getValue().fork()).isEqualTo(2);
        }

        @Test
        void unJetonRecuSansBesoinEstRenvoyeAuPlusPrioritaire() {
            node.onRequest(requestFrom(3, 1, 5, 3, 4));
            clearInvocations(transport);

            node.onToken(token(4, 7, 0)); // je n'ai rien demandé, mais le philosophe 3 attend la fourchette 4

            ArgumentCaptor<Messages.Token> sent = ArgumentCaptor.forClass(Messages.Token.class);
            verify(transport).sendToken(eq(3), sent.capture(), any());
            assertThat(sent.getValue().fork()).isEqualTo(4);
            assertThat(sent.getValue().version()).isEqualTo(8); // la version augmente à chaque transfert
        }
    }

    @Nested
    @DisplayName("Manger : il faut les deux jetons")
    class Repas {

        @Test
        void onNeMangePasAvecUnSeulJeton() {
            CompletableFuture<Void> granted = node.request(MY_FORKS);

            assertThat(granted).isNotDone();
            assertThat(node.snapshot().state()).isEqualTo(State.WAITING);
        }

        @Test
        void onMangeDesQueLeSecondJetonArrive() {
            CompletableFuture<Void> granted = node.request(MY_FORKS);

            node.onToken(token(2, 1, 2));

            assertThat(granted).isCompleted();
            assertThat(node.snapshot().state()).isEqualTo(State.EATING);
            assertThat(node.snapshot().heldTokens()).containsOnlyKeys(1, 2);
        }

        @Test
        void pendantLeRepasOnNeDonneAucuneDeSesFourchettes() {
            node.request(MY_FORKS);
            node.onToken(token(2, 1, 2));
            clearInvocations(transport);

            node.onRequest(requestFrom(0, 1, 1, 0, 1)); // demande pourtant très prioritaire
            node.onRequest(requestFrom(2, 1, 1, 2, 3));

            verify(transport, never()).sendToken(anyInt(), any(), any());
        }

        @Test
        void aLaFinDuRepasLaLiberationEstDiffuseeEtLesJetonsPartentVersCeuxQuiAttendent() {
            node.request(MY_FORKS);
            node.onToken(token(2, 1, 2));
            node.onRequest(requestFrom(0, 1, 30, 0, 1));
            node.onRequest(requestFrom(2, 1, 40, 2, 3));
            clearInvocations(transport);

            node.release();

            verify(transport, times(4)).sendRelease(anyInt(), any());
            ArgumentCaptor<Messages.Token> toZero = ArgumentCaptor.forClass(Messages.Token.class);
            ArgumentCaptor<Messages.Token> toTwo = ArgumentCaptor.forClass(Messages.Token.class);
            verify(transport).sendToken(eq(0), toZero.capture(), any());
            verify(transport).sendToken(eq(2), toTwo.capture(), any());
            assertThat(toZero.getValue().fork()).isEqualTo(1);
            assertThat(toTwo.getValue().fork()).isEqualTo(2);
            assertThat(node.snapshot().state()).isEqualTo(State.IDLE);
            assertThat(node.snapshot().heldTokens()).isEmpty();
        }

        @Test
        void sansDemandeEnAttenteLesJetonsRestentApresLeRepas() {
            node.request(MY_FORKS);
            node.onToken(token(2, 1, 2));

            node.release();

            assertThat(node.snapshot().heldTokens()).containsOnlyKeys(1, 2);
            // Et on peut remanger tout de suite, sans aucun message de jeton.
            assertThat(node.request(MY_FORKS)).isCompleted();
        }

        @Test
        void abandonnerUneDemandeEnAttenteLibereLesAutres() {
            CompletableFuture<Void> granted = node.request(MY_FORKS);
            node.onRequest(requestFrom(0, 1, 50, 0, 1)); // attend derrière moi
            clearInvocations(transport);

            node.release(); // j'abandonne avant d'avoir mangé

            assertThat(granted).isCancelled();
            verify(transport, times(4)).sendRelease(anyInt(), any());
            verify(transport).sendToken(eq(0), any(), any());
        }
    }

    @Nested
    @DisplayName("Messages en double, en retard ou perdus")
    class Robustesse {

        @Test
        void unJetonRecuDeuxFoisNEstComptéQuUneFois() {
            node.onToken(token(2, 1, 2));
            node.onRequest(requestFrom(2, 1, 5, 2, 3)); // le jeton 2 repart vers le philosophe 2 (version 2)
            clearInvocations(transport);

            node.onToken(token(2, 1, 2)); // retransmission tardive de l'ancien jeton

            assertThat(node.snapshot().heldTokens()).doesNotContainKey(2); // pas de jeton fantôme
            verify(transport, never()).sendToken(anyInt(), any(), any());
        }

        @Test
        void uneDemandeRecueDeuxFoisNEstMemoriseeQuUneFois() {
            node.onRequest(requestFrom(3, 1, 5, 3, 4));
            node.onRequest(requestFrom(3, 1, 5, 3, 4));

            assertThat(node.snapshot().pendingRequests()).hasSize(1);
        }

        @Test
        void uneDemandeDejaTermineeArriveeEnRetardEstIgnoree() {
            node.onRelease(new Messages.Release(0, 1, 9));

            node.onRequest(requestFrom(0, 1, 5, 0, 1)); // retransmission d'une demande déjà finie

            assertThat(node.snapshot().pendingRequests()).isEmpty();
            verify(transport, never()).sendToken(anyInt(), any(), any());
        }

        @Test
        void laLiberationRetireLaDemandeDeLaFile() {
            node.onRequest(requestFrom(3, 1, 5, 3, 4));

            node.onRelease(new Messages.Release(3, 1, 9));

            assertThat(node.snapshot().pendingRequests()).isEmpty();
        }

        @Test
        void uneNouvelleDemandeRemplaceLAncienneDuMemePhilosopheSiLaLiberationSEstPerdue() {
            node.onRequest(requestFrom(3, 1, 5, 3, 4));

            node.onRequest(requestFrom(3, 2, 12, 3, 4)); // la libération n° 1 n'est jamais arrivée

            assertThat(node.snapshot().pendingRequests()).extracting(ForkRequest::sequence).containsExactly(2L);
        }

        @Test
        void unJetonAnnonceQueLEmetteurAFiniCeQuiEffaceSaDemandePerimee() {
            node.onRequest(requestFrom(3, 1, 5, 3, 4)); // puis la libération de 3 se perd

            node.onToken(new Messages.Token(4, 3, 3, 20, 1)); // 3 indique avoir terminé sa demande n° 1

            assertThat(node.snapshot().pendingRequests()).isEmpty();
            assertThat(node.snapshot().heldTokens()).containsKey(4); // le jeton n'est pas renvoyé à 3
        }
    }

    @Nested
    @DisplayName("Traitement des erreurs")
    class Erreurs {

        @Test
        void deuxDemandesALaFoisSontRefusees() {
            node.request(MY_FORKS);
            assertThatThrownBy(() -> node.request(MY_FORKS)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void lesFourchettesDoiventEtreDeuxDistinctesEtExistantes() {
            assertThatThrownBy(() -> node.request(List.of(1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> node.request(List.of(1, 1))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> node.request(List.of(1, COUNT))).isInstanceOf(IllegalArgumentException.class);
            assertThat(node.snapshot().state()).isEqualTo(State.IDLE);
        }

        @Test
        void libererSansDemandeEstUneErreur() {
            assertThatThrownBy(() -> node.release()).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void unSeulPhilosopheNePeutPasJouer() {
            assertThatThrownBy(() -> new TokenNode(0, 1, clock, transport))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
