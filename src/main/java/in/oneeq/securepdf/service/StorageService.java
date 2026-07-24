package in.oneeq.securepdf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import in.oneeq.securepdf.config.SecurePdfProperties;
import in.oneeq.securepdf.model.Manifest;
import in.oneeq.securepdf.model.ProcessingStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Owns the on-disk artifact layout and every path it hands out. Nothing outside
 * this class builds book paths, so path-traversal defence lives in one place.
 *
 * <pre>
 * {storageDir}/{bookId}/
 *     original.pdf     (private, never served)
 *     key.bin          (raw AES key, private, served only via the /key API)
 *     status           (PROCESSING | READY | FAILED)
 *     manifest.json
 *     page-1.enc, page-2.enc, ...
 * </pre>
 */
@Service
public class StorageService {

    private final Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    public StorageService(SecurePdfProperties props) {
        this.root = Path.of(props.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage root " + root, e);
        }
    }

    public String newBookId() {
        return UUID.randomUUID().toString();
    }

    /** Resolve a book directory, rejecting any id that would escape the root. */
    public Path bookDir(String bookId) {
        Path dir = root.resolve(bookId).normalize();
        if (!dir.getParent().equals(root)) {
            throw new IllegalArgumentException("Invalid bookId: " + bookId);
        }
        return dir;
    }

    /** The raw uploaded file, kept with its real extension (private). */
    public Path sourceFile(String bookId, String ext) {
        return bookDir(bookId).resolve("source." + ext);
    }

    /** The normalised PDF that the render pipeline always consumes (private). */
    public Path originalPdf(String bookId) {
        return bookDir(bookId).resolve("original.pdf");
    }

    public Path keyFile(String bookId) {
        return bookDir(bookId).resolve("key.bin");
    }

    public Path manifestFile(String bookId) {
        return bookDir(bookId).resolve("manifest.json");
    }

    public Path statusFile(String bookId) {
        return bookDir(bookId).resolve("status");
    }

    public Path errorFile(String bookId) {
        return bookDir(bookId).resolve("error.txt");
    }

    public String pageFileName(int pageNumber) {
        return "page-" + pageNumber + ".enc";
    }

    public Path pageFile(String bookId, int pageNumber) {
        return bookDir(bookId).resolve(pageFileName(pageNumber));
    }

    public boolean exists(String bookId) {
        return Files.isDirectory(bookDir(bookId));
    }

    // --- status ---------------------------------------------------------

    public void writeStatus(String bookId, ProcessingStatus status) {
        try {
            Files.writeString(statusFile(bookId), status.name(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ProcessingStatus readStatus(String bookId) {
        try {
            Path f = statusFile(bookId);
            if (!Files.exists(f)) {
                return null;
            }
            return ProcessingStatus.valueOf(Files.readString(f, StandardCharsets.UTF_8).trim());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void writeError(String bookId, String message) {
        try {
            Files.writeString(errorFile(bookId), message == null ? "" : message, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readError(String bookId) {
        try {
            Path f = errorFile(bookId);
            return Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- manifest -------------------------------------------------------

    public void writeManifest(Manifest manifest) {
        try {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(manifestFile(manifest.getBookId()).toFile(), manifest);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Manifest readManifest(String bookId) {
        try {
            return mapper.readValue(manifestFile(bookId).toFile(), Manifest.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- key ------------------------------------------------------------

    public void writeKey(String bookId, byte[] rawKey) {
        try {
            Files.write(keyFile(bookId), rawKey);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public byte[] readKey(String bookId) {
        try {
            return Files.readAllBytes(keyFile(bookId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
