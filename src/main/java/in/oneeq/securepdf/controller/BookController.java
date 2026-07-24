package in.oneeq.securepdf.controller;

import in.oneeq.securepdf.model.Manifest;
import in.oneeq.securepdf.model.ProcessingStatus;
import in.oneeq.securepdf.service.BookService;
import in.oneeq.securepdf.service.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/**
 * Public surface of the secure PDF player.
 *
 * <p>JSON responses follow the {@code {success, data}} envelope. The original
 * PDF is never exposed — only the manifest, the encrypted page blobs, and the
 * key (behind the auth filter).
 */
@RestController
@RequestMapping("/api/v1/books")
public class BookController {

    private final BookService bookService;
    private final StorageService storage;

    public BookController(BookService bookService, StorageService storage) {
        this.bookService = bookService;
        this.storage = storage;
    }

    /** 1. Upload — stores original privately and starts processing. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(value = "title", required = false) String title)
            throws IOException {
        String bookId = bookService.upload(file, title);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
                ok(Map.of("bookId", bookId, "status", ProcessingStatus.PROCESSING.name())));
    }

    /** Processing status poll. Includes the failure reason when status=FAILED. */
    @GetMapping("/{bookId}")
    public ResponseEntity<?> status(@PathVariable String bookId) {
        ProcessingStatus status = bookService.status(requireBook(bookId));
        if (status == null) {
            throw notFound();
        }
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("bookId", bookId);
        data.put("status", status.name());
        if (status == ProcessingStatus.FAILED) {
            data.put("error", bookService.error(bookId));
        }
        return ResponseEntity.ok(ok(data));
    }

    /** 4. Manifest — page count + file list, no key material. */
    @GetMapping("/{bookId}/manifest")
    public ResponseEntity<?> manifest(@PathVariable String bookId) {
        requireReady(bookId);
        Manifest manifest = storage.readManifest(bookId);
        return ResponseEntity.ok(ok(manifest));
    }

    /** 5. A single encrypted page: IV || ciphertext || tag. */
    @GetMapping("/{bookId}/pages/{pageNumber}")
    public ResponseEntity<byte[]> page(@PathVariable String bookId,
                                       @PathVariable int pageNumber) {
        requireReady(bookId);
        Manifest manifest = storage.readManifest(bookId);
        if (pageNumber < 1 || pageNumber > manifest.getPages()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such page");
        }
        Path path = storage.pageFile(bookId, pageNumber);
        if (!Files.exists(path)) {
            throw notFound();
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(bytes);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Read failed", e);
        }
    }

    /**
     * The AES key, base64-encoded. THIS is the security-critical endpoint: a
     * real deployment enforces per-user entitlement and short-lived tokens here
     * before releasing the key.
     */
    @GetMapping("/{bookId}/key")
    public ResponseEntity<?> key(@PathVariable String bookId) {
        requireReady(bookId);
        String keyB64 = Base64.getEncoder().encodeToString(storage.readKey(bookId));
        return ResponseEntity.ok(ok(Map.of(
                "alg", "AES-GCM",
                "keyBits", 256,
                "ivBytes", 12,
                "key", keyB64)));
    }

    // --- helpers --------------------------------------------------------

    private String requireBook(String bookId) {
        if (!storage.exists(bookId)) {
            throw notFound();
        }
        return bookId;
    }

    private void requireReady(String bookId) {
        requireBook(bookId);
        ProcessingStatus status = bookService.status(bookId);
        if (status != ProcessingStatus.READY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Book not ready (status=" + status + ")");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found");
    }

    private static Map<String, Object> ok(Object data) {
        return Map.of("success", true, "data", data);
    }
}
