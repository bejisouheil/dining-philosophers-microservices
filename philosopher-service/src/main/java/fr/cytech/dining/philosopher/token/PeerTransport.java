package fr.cytech.dining.philosopher.token;

/**
 * Envoi des messages du protocole aux autres philosophes. Les envois sont asynchrones : aucune
 * de ces méthodes n'attend la réponse du destinataire.
 */
public interface PeerTransport {

    /** Demande : peut se perdre, elle sera rediffusée tant que le demandeur a faim. */
    void sendRequest(int to, Messages.Request message);

    /** Libération : retentée plusieurs fois ; une perte est rattrapée par {@code senderCompleted}. */
    void sendRelease(int to, Messages.Release message);

    /**
     * Jeton : ne doit jamais se perdre. L'envoi est retenté jusqu'à l'accusé de réception du
     * destinataire, puis {@code acknowledged} est appelé.
     */
    void sendToken(int to, Messages.Token message, Runnable acknowledged);
}
