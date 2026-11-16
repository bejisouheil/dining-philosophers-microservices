package fr.cytech.dining.philosopher.token;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sauvegarde l'état du philosophe dans un fichier JSON. L'écriture se fait dans un fichier
 * temporaire renommé ensuite : une panne en pleine écriture laisse l'ancien état intact.
 */
public class FileTokenStateStore implements TokenStateStore {

    private final Path file;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public FileTokenStateStore(Path file) {
        this.file = file;
    }

    @Override
    public Optional<State> load() {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(mapper.readValue(file.toFile(), State.class));
    }

    @Override
    public void save(State state) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            mapper.writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            // Continuer sans sauvegarde mettrait la sûreté en danger après une panne : on s'arrête.
            throw new UncheckedIOException("Impossible de sauvegarder l'état du philosophe dans " + file, e);
        }
    }
}
