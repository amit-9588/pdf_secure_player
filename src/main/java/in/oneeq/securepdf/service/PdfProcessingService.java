package in.oneeq.securepdf.service;

import in.oneeq.securepdf.config.SecurePdfProperties;
import in.oneeq.securepdf.model.DocKind;
import in.oneeq.securepdf.model.Manifest;
import in.oneeq.securepdf.model.ProcessingStatus;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The processing worker from the architecture doc: render → encrypt → manifest.
 * Runs off the request thread so uploads return immediately.
 */
@Service
public class PdfProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PdfProcessingService.class);

    private final SecurePdfProperties props;
    private final StorageService storage;
    private final CryptoService crypto;
    private final DocumentConverter converter;

    public PdfProcessingService(SecurePdfProperties props, StorageService storage,
                                CryptoService crypto, DocumentConverter converter) {
        this.props = props;
        this.storage = storage;
        this.crypto = crypto;
        this.converter = converter;
    }

    @Async("pdfProcessingExecutor")
    public void process(String bookId, String title, DocKind kind, String sourceExt) {
        log.info("Processing book {} ('{}') kind={}", bookId, title, kind);
        try {
            // Step 0: normalise whatever was uploaded into original.pdf.
            converter.toNormalizedPdf(bookId, kind, sourceExt);

            // One AES-256 key per book, kept private on disk.
            SecretKey key = crypto.generateKey();
            storage.writeKey(bookId, key.getEncoded());

            List<String> files = new ArrayList<>();
            int pageCount;

            try (PDDocument document = Loader.loadPDF(storage.originalPdf(bookId).toFile())) {
                pageCount = document.getNumberOfPages();
                PDFRenderer renderer = new PDFRenderer(document);

                for (int i = 0; i < pageCount; i++) {
                    int pageNumber = i + 1;

                    BufferedImage image = renderer.renderImageWithDPI(
                            i, props.getRenderDpi(), ImageType.RGB);

                    byte[] rendered = toBytes(image, props.getImageFormat());
                    byte[] encrypted = crypto.encrypt(key, rendered);

                    java.nio.file.Files.write(storage.pageFile(bookId, pageNumber), encrypted);
                    files.add(storage.pageFileName(pageNumber));

                    log.debug("Book {} page {}/{} -> {} bytes encrypted",
                            bookId, pageNumber, pageCount, encrypted.length);
                }
            }

            Manifest manifest = new Manifest(bookId, title, pageCount, props.getImageFormat(), files);
            storage.writeManifest(manifest);
            storage.writeStatus(bookId, ProcessingStatus.READY);
            log.info("Book {} ready: {} pages", bookId, pageCount);

        } catch (Exception e) {
            log.error("Processing failed for book {}", bookId, e);
            storage.writeError(bookId, rootMessage(e));
            storage.writeStatus(bookId, ProcessingStatus.FAILED);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return (msg == null || msg.isBlank()) ? cur.getClass().getSimpleName() : msg;
    }

    private static byte[] toBytes(BufferedImage image, String format) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, baos)) {
            throw new IllegalStateException("No ImageIO writer for format: " + format);
        }
        return baos.toByteArray();
    }
}
