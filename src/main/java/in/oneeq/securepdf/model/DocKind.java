package in.oneeq.securepdf.model;

import java.util.Locale;

/**
 * Supported upload types. Everything is normalised to a PDF before the
 * render+encrypt pipeline runs, so the encryption/streaming path never has to
 * know which of these it started from.
 */
public enum DocKind {
    PDF,
    TXT,   // txt, md, csv, log — rendered as monospaced text
    HTML,  // html, htm — rendered via headless Chrome
    DOCX, DOC, PPTX, PPT; // Office — converted via LibreOffice

    /** @return the kind for a file extension, or {@code null} if unsupported. */
    public static DocKind fromExtension(String ext) {
        if (ext == null) {
            return null;
        }
        return switch (ext.toLowerCase(Locale.ROOT)) {
            case "pdf" -> PDF;
            case "txt", "text", "md", "markdown", "csv", "log" -> TXT;
            case "html", "htm" -> HTML;
            case "docx" -> DOCX;
            case "doc" -> DOC;
            case "pptx" -> PPTX;
            case "ppt" -> PPT;
            default -> null;
        };
    }

    public boolean needsLibreOffice() {
        return this == DOCX || this == DOC || this == PPTX || this == PPT;
    }
}
