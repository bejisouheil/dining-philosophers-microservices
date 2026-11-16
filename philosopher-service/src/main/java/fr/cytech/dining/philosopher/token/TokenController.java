package fr.cytech.dining.philosopher.token;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Points d'entrée HTTP du protocole par jetons. Le message est traité avant de répondre : la
 * réponse 200 sert d'accusé de réception à l'émetteur.
 */
@RestController
@ConditionalOnProperty(name = "philosopher.mode", havingValue = "token")
public class TokenController {

    private final TokenNode node;

    public TokenController(TokenNode node) {
        this.node = node;
    }

    @PostMapping("/token/request")
    public void request(@RequestBody Messages.Request message) {
        node.onRequest(message);
    }

    @PostMapping("/token/release")
    public void release(@RequestBody Messages.Release message) {
        node.onRelease(message);
    }

    @PostMapping("/token/transfer")
    public void transfer(@RequestBody Messages.Token message) {
        node.onToken(message);
    }

    /** Diagnostic : jetons détenus, file des demandes mémorisées, compteurs de messages. */
    @GetMapping("/token/state")
    public TokenNode.Snapshot state() {
        return node.snapshot();
    }
}
