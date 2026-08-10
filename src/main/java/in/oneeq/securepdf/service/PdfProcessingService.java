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

            String mode = props.getProcessingMode();
            List<String> files = new ArrayList<>();
            java.util.Map<String, java.util.List<Integer>> chunkMap = new java.util.HashMap<>();
            java.util.Map<Integer, String> byteRanges = new java.util.HashMap<>();
            int pageCount;

            try (PDDocument document = Loader.loadPDF(storage.originalPdf(bookId).toFile())) {
                pageCount = document.getNumberOfPages();
                PDFRenderer renderer = new PDFRenderer(document);

                if ("SINGLE_PAGE".equalsIgnoreCase(mode)) {
                    // [EXISTING LOGIC] 1 request = 1 page.
                    for (int i = 0; i < pageCount; i++) {
                        int pageNumber = i + 1;
                        BufferedImage image = renderer.renderImageWithDPI(i, props.getRenderDpi(), ImageType.RGB);
                        byte[] rendered = toBytes(image, props.getImageFormat());
                        byte[] encrypted = crypto.encrypt(key, rendered);
                        java.nio.file.Files.write(storage.pageFile(bookId, pageNumber), encrypted);
                        files.add(storage.pageFileName(pageNumber));
                    }
                } else if ("BATCHED".equalsIgnoreCase(mode)) {
                    // [NEW LOGIC: STATIC CHUNKING]
                    // Group multiple pages into a single chunk file (JSON map of base64 images)
                    // This reduces CDN request costs at the expense of slightly higher latency for random access.
                    int batchSize = props.getBatchSize();
                    com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                    for (int i = 0; i < pageCount; i += batchSize) {
                        int startPage = i + 1;
                        int endPage = Math.min(startPage + batchSize - 1, pageCount);
                        String chunkName = "chunk-" + startPage + "-" + endPage + ".enc";
                        java.util.Map<String, String> chunkData = new java.util.HashMap<>();
                        java.util.List<Integer> pagesInChunk = new java.util.ArrayList<>();
                        
                        for (int p = startPage; p <= endPage; p++) {
                            BufferedImage image = renderer.renderImageWithDPI(p - 1, props.getRenderDpi(), ImageType.RGB);
                            byte[] rendered = toBytes(image, props.getImageFormat());
                            chunkData.put(String.valueOf(p), java.util.Base64.getEncoder().encodeToString(rendered));
                            pagesInChunk.add(p);
                        }
                        
                        byte[] chunkJsonBytes = mapper.writeValueAsBytes(chunkData);
                        byte[] encrypted = crypto.encrypt(key, chunkJsonBytes);
                        java.nio.file.Files.write(storage.chunkFile(bookId, chunkName), encrypted);
                        
                        files.add(chunkName);
                        chunkMap.put(chunkName, pagesInChunk);
                    }
                } else if ("BYTE_RANGE".equalsIgnoreCase(mode) || mode == null) {
                    // [NEW LOGIC: DYNAMIC CHUNKING (HTTP RANGE)]
                    // Encrypt each page independently but concatenate them into a single file (book.dat).
                    // The client uses HTTP Range headers to fetch exact bytes (sliding window).
                    mode = "BYTE_RANGE";
                    java.io.ByteArrayOutputStream datStream = new java.io.ByteArrayOutputStream();
                    int currentOffset = 0;
                    for (int i = 0; i < pageCount; i++) {
                        int pageNumber = i + 1;
                        BufferedImage image = renderer.renderImageWithDPI(i, props.getRenderDpi(), ImageType.RGB);
                        byte[] rendered = toBytes(image, props.getImageFormat());
                        byte[] encrypted = crypto.encrypt(key, rendered);
                        
                        datStream.write(encrypted);
                        int endOffset = currentOffset + encrypted.length - 1;
                        byteRanges.put(pageNumber, currentOffset + "-" + endOffset);
                        currentOffset += encrypted.length;
                    }
                    java.nio.file.Files.write(storage.bookDatFile(bookId), datStream.toByteArray());
                    files.add("book.dat");
                }
            }

            Manifest manifest = new Manifest(bookId, title, pageCount, props.getImageFormat(), files, mode, chunkMap, byteRanges);
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
