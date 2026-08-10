package in.oneeq.securepdf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Externalised configuration for the secure PDF player.
 * Bound from the {@code securepdf.*} keys in application.yml.
 */
@Component
@ConfigurationProperties(prefix = "securepdf")
public class SecurePdfProperties {

    /** Root directory that holds the per-book artifact folders. */
    private String storageDir = "./data/books";

    /** DPI used when rasterising each PDF page. Higher = sharper + bigger files. */
    private int renderDpi = 200;

    /** Image format for rendered pages (png is natively supported by ImageIO). */
    private String imageFormat = "png";

    /** 
     * Streaming architecture mode:
     * SINGLE_PAGE: 1 file per page
     * BATCHED: grouping pages into chunks
     * BYTE_RANGE: single file, byte-range fetching
     */
    private String processingMode = "BYTE_RANGE";

    /** Number of pages per chunk in BATCHED mode */
    private int batchSize = 5;

    /**
     * Static bearer token gating the /api/v1/books/** endpoints. This is the
     * seam where a real deployment would plug in PoshDesk-style JWT auth and
     * enforce per-user, per-book, time-limited authorization.
     */
    private String authToken = "demo-secret-token";

    /** Chrome/Chromium binary used to render HTML uploads to PDF. */
    private String chromeBinary = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome";

    /** LibreOffice binary used to convert Office (docx/pptx/...) uploads to PDF. */
    private String sofficeBinary = "soffice";

    /** Hard timeout (seconds) for an external conversion process. */
    private int conversionTimeoutSeconds = 60;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public int getRenderDpi() {
        return renderDpi;
    }

    public void setRenderDpi(int renderDpi) {
        this.renderDpi = renderDpi;
    }

    public String getImageFormat() {
        return imageFormat;
    }

    public void setImageFormat(String imageFormat) {
        this.imageFormat = imageFormat;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getChromeBinary() {
        return chromeBinary;
    }

    public void setChromeBinary(String chromeBinary) {
        this.chromeBinary = chromeBinary;
    }

    public String getSofficeBinary() {
        return sofficeBinary;
    }

    public void setSofficeBinary(String sofficeBinary) {
        this.sofficeBinary = sofficeBinary;
    }

    public int getConversionTimeoutSeconds() {
        return conversionTimeoutSeconds;
    }

    public void setConversionTimeoutSeconds(int conversionTimeoutSeconds) {
        this.conversionTimeoutSeconds = conversionTimeoutSeconds;
    }

    public String getProcessingMode() {
        return processingMode;
    }

    public void setProcessingMode(String processingMode) {
        this.processingMode = processingMode;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
