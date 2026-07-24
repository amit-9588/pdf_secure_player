package in.oneeq.securepdf.service;

import in.oneeq.securepdf.config.SecurePdfProperties;
import in.oneeq.securepdf.model.DocKind;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Normalises any supported upload into a single {@code original.pdf} so that the
 * downstream render → encrypt → manifest pipeline stays completely unaware of
 * the source format.
 *
 * <ul>
 *   <li>PDF  → copied through unchanged.</li>
 *   <li>TXT  → laid out as monospaced text with PDFBox (pure Java).</li>
 *   <li>HTML → rendered by headless Chrome ({@code --print-to-pdf}).</li>
 *   <li>Office (docx/pptx/...) → converted by LibreOffice ({@code soffice}).</li>
 * </ul>
 */
@Service
public class DocumentConverter {

    private static final Logger log = LoggerFactory.getLogger(DocumentConverter.class);

    private final SecurePdfProperties props;
    private final StorageService storage;

    public DocumentConverter(SecurePdfProperties props, StorageService storage) {
        this.props = props;
        this.storage = storage;
    }

    /** Produce {@code original.pdf} for the book from its stored source file. */
    public void toNormalizedPdf(String bookId, DocKind kind, String sourceExt) throws Exception {
        Path source = storage.sourceFile(bookId, sourceExt);
        Path outPdf = storage.originalPdf(bookId);

        switch (kind) {
            case PDF -> Files.copy(source, outPdf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            case TXT -> textToPdf(source, outPdf);
            case HTML -> htmlToPdf(source, outPdf);
            case DOCX, DOC, PPTX, PPT -> officeToPdf(source, outPdf);
        }

        if (!Files.exists(outPdf) || Files.size(outPdf) == 0) {
            throw new IllegalStateException("Conversion produced no PDF for kind " + kind);
        }
    }

    // --- TXT (pure Java) ---------------------------------------------------

    private void textToPdf(Path source, Path outPdf) throws IOException {
        String text = Files.readString(source, StandardCharsets.UTF_8);

        final PDRectangle pageSize = PDRectangle.LETTER;
        final float margin = 50f;
        final float fontSize = 10f;
        final float leading = 13f;
        final PDType1Font font = new PDType1Font(Standard14Fonts.FontName.COURIER);

        // Courier is monospaced: every glyph advances 600/1000 em.
        final float charWidth = 0.6f * fontSize;
        final int maxChars = Math.max(1, (int) ((pageSize.getWidth() - 2 * margin) / charWidth));
        final float usableHeight = pageSize.getHeight() - 2 * margin;
        final int linesPerPage = Math.max(1, (int) (usableHeight / leading));

        List<String> lines = wrap(text, maxChars);

        try (PDDocument doc = new PDDocument()) {
            int idx = 0;
            while (idx < lines.size()) {
                PDPage page = new PDPage(pageSize);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(font, fontSize);
                    cs.beginText();
                    cs.newLineAtOffset(margin, pageSize.getHeight() - margin);
                    for (int l = 0; l < linesPerPage && idx < lines.size(); l++, idx++) {
                        cs.showText(sanitize(lines.get(idx)));
                        cs.newLineAtOffset(0, -leading);
                    }
                    cs.endText();
                }
            }
            if (doc.getNumberOfPages() == 0) {
                doc.addPage(new PDPage(pageSize)); // never emit a 0-page PDF
            }
            doc.save(outPdf.toFile());
        }
    }

    /** Split on newlines, expand tabs, then hard-wrap to {@code maxChars}. */
    private static List<String> wrap(String text, int maxChars) {
        List<String> out = new ArrayList<>();
        for (String raw : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            String line = raw.replace("\t", "    ");
            if (line.isEmpty()) {
                out.add("");
                continue;
            }
            for (int i = 0; i < line.length(); i += maxChars) {
                out.add(line.substring(i, Math.min(line.length(), i + maxChars)));
            }
        }
        return out;
    }

    /** Standard-14 Courier uses WinAnsi; replace anything it can't encode. */
    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 32 && c < 127 ? c : (c == 32 ? ' ' : approx(c)));
        }
        return sb.toString();
    }

    private static char approx(char c) {
        return switch (c) {
            case '‘', '’' -> '\'';
            case '“', '”' -> '"';
            case '–', '—' -> '-';
            case '…' -> '.';
            case ' ' -> ' ';
            default -> c < 256 ? c : '?';
        };
    }

    // --- HTML (headless Chrome) -------------------------------------------

    private void htmlToPdf(Path source, Path outPdf) throws Exception {
        // A throwaway profile dir keeps us out of the user's real Chrome data.
        Path profile = Files.createTempDirectory("securepdf-chrome-");
        Files.deleteIfExists(outPdf);
        List<String> command = List.of(
                props.getChromeBinary(),
                "--headless=new",
                "--disable-gpu",
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-background-networking",
                "--disable-component-update",
                "--disable-sync",
                "--mute-audio",
                "--no-pdf-header-footer",
                "--run-all-compositor-stages-before-draw",
                "--virtual-time-budget=5000",
                "--user-data-dir=" + profile,
                "--print-to-pdf=" + outPdf.toAbsolutePath(),
                source.toAbsolutePath().toUri().toString()
        );

        log.info("Running chrome converter: {}", command.get(0));
        Process p;
        try {
            p = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
        } catch (IOException e) {
            deleteRecursive(profile);
            throw new IllegalStateException("Chrome binary not found or not executable: "
                    + props.getChromeBinary() + " (" + e.getMessage() + ")", e);
        }

        try {
            // Chrome prints the PDF but often does not self-terminate, so we wait
            // for the output file to appear and stop growing rather than for exit.
            boolean produced = waitForStableFile(outPdf, props.getConversionTimeoutSeconds());
            if (!produced) {
                throw new IllegalStateException("Chrome did not produce a PDF within "
                        + props.getConversionTimeoutSeconds() + "s");
            }
        } finally {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroyForcibly();
            deleteRecursive(profile);
        }
    }

    /** Poll until {@code file} exists and its size is non-zero and stable. */
    private static boolean waitForStableFile(Path file, int timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;
        long lastSize = -1;
        int stableTicks = 0;
        while (System.nanoTime() < deadline) {
            Thread.sleep(200);
            long size = Files.exists(file) ? sizeOf(file) : -1;
            if (size > 0 && size == lastSize) {
                if (++stableTicks >= 2) { // stable for ~400ms
                    return true;
                }
            } else {
                stableTicks = 0;
            }
            lastSize = size;
        }
        return false;
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return -1;
        }
    }

    // --- Office (LibreOffice) ---------------------------------------------

    private void officeToPdf(Path source, Path outPdf) throws Exception {
        Path outDir = Files.createTempDirectory("securepdf-lo-");
        Path profile = Files.createTempDirectory("securepdf-lo-profile-");
        try {
            runProcess("soffice", List.of(
                    props.getSofficeBinary(),
                    "--headless",
                    "--norestore",
                    "-env:UserInstallation=file://" + profile.toAbsolutePath(),
                    "--convert-to", "pdf",
                    "--outdir", outDir.toAbsolutePath().toString(),
                    source.toAbsolutePath().toString()
            ));
            // soffice names the output <sourceBaseName>.pdf
            String base = source.getFileName().toString();
            int dot = base.lastIndexOf('.');
            String pdfName = (dot > 0 ? base.substring(0, dot) : base) + ".pdf";
            Path produced = outDir.resolve(pdfName);
            if (!Files.exists(produced)) {
                throw new IllegalStateException("LibreOffice did not produce " + pdfName);
            }
            Files.copy(produced, outPdf, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            deleteRecursive(outDir);
            deleteRecursive(profile);
        }
    }

    // --- process helper ----------------------------------------------------

    private void runProcess(String name, List<String> command) throws Exception {
        log.info("Running {} converter: {}", name, command.get(0));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IllegalStateException(name + " binary not found or not executable: "
                    + command.get(0) + " (" + e.getMessage() + ")", e);
        }

        // Drain output on a daemon thread so a full pipe buffer can never block
        // the process and defeat the timeout below.
        StringBuilder output = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (var reader = p.inputReader()) {
                reader.lines().forEach(line -> output.append(line).append('\n'));
            } catch (IOException ignored) {
            }
        });
        drain.setDaemon(true);
        drain.start();

        boolean finished = p.waitFor(props.getConversionTimeoutSeconds(), TimeUnit.SECONDS);
        if (!finished) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroyForcibly();
            throw new IllegalStateException(name + " conversion timed out after "
                    + props.getConversionTimeoutSeconds() + "s");
        }
        drain.join(2000);
        if (p.exitValue() != 0) {
            throw new IllegalStateException(name + " conversion failed (exit "
                    + p.exitValue() + "): " + output.toString().trim());
        }
    }

    private static void deleteRecursive(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(pth -> {
                        try {
                            Files.deleteIfExists(pth);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
