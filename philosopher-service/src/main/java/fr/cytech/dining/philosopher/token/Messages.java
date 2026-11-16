package fr.cytech.dining.philosopher.token;

/**
 * Les trois messages du protocole par jetons. Chacun transporte l'horloge de Lamport de
 * l'émetteur et le numéro de sa dernière demande terminée ({@code senderCompleted}), ce qui
 * permet au destinataire d'oublier une demande dont il aurait manqué la libération.
 */
public final class Messages {

    private Messages() {
    }

    /** « J'ai faim, voici ma demande » : diffusé à tous les autres philosophes. */
    public record Request(ForkRequest request, long senderClock, long senderCompleted) {
    }

    /** « J'ai fini de manger, oubliez ma demande » : diffusé à tous les autres philosophes. */
    public record Release(int philosopherId, long sequence, long senderClock) {
    }

    /**
     * Transfert du jeton d'une fourchette. {@code version} augmente de 1 à chaque transfert :
     * un jeton reçu deux fois (retransmission) est reconnu et ignoré.
     */
    public record Token(int fork, long version, int from, long senderClock, long senderCompleted) {
    }
}
