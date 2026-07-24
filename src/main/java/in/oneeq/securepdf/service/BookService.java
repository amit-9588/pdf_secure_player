package in.oneeq.securepdf.service;

import in.oneeq.securepdf.model.DocKind;
import in.oneeq.securepdf.model.ProcessingStatus;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

@Service
public class BookService {

    private final StorageService storage;
    private final PdfProcessingService processor;

    public BookService(StorageService storage, PdfProcessingService processor) {
        this.storage = storage;
        this.processor = processor;
    }

    /**
     * Persist the uploaded document privately (with its real extension), mark
     * the book PROCESSING, and hand it to the async worker which will normalise
     * it to PDF and then render + encrypt. Returns the new bookId immediately.
     */
    public String upload(MultipartFile file, String title) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No file uploaded");
        }

        String ext = extensionOf(file.getOriginalFilename());
        DocKind kind = DocKind.fromExtension(ext);
        if (kind == null) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Unsupported file type: ." + ext + " (supported: pdf, txt/md/csv, html, docx, doc, pptx, ppt)");
        }

        String bookId = storage.newBookId();
        Files.createDirectories(storage.bookDir(bookId));
        file.transferTo(storage.sourceFile(bookId, ext));
        storage.writeStatus(bookId, ProcessingStatus.PROCESSING);

        String safeTitle = (title == null || title.isBlank())
                ? stripExtension(file.getOriginalFilename())
                : title;

        processor.process(bookId, safeTitle, kind, ext);
        return bookId;
    }

    public ProcessingStatus status(String bookId) {
        return storage.readStatus(bookId);
    }

    public String error(String bookId) {
        return storage.readError(bookId);
    }

    private static String extensionOf(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String stripExtension(String name) {
        if (name == null) {
            return "Untitled";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
