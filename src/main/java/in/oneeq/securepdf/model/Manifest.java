package in.oneeq.securepdf.model;

import java.util.List;

/**
 * The public description of a processed book. Served to the browser so it can
 * learn the page count and page file names without downloading every page.
 * Deliberately contains NO key material.
 */
public class Manifest {

    private String bookId;
    private String title;
    private int pages;
    private String imageFormat;
    private List<String> files;

    public Manifest() {
    }

    public Manifest(String bookId, String title, int pages, String imageFormat, List<String> files) {
        this.bookId = bookId;
        this.title = title;
        this.pages = pages;
        this.imageFormat = imageFormat;
        this.files = files;
    }

    public String getBookId() {
        return bookId;
    }

    public void setBookId(String bookId) {
        this.bookId = bookId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public int getPages() {
        return pages;
    }

    public void setPages(int pages) {
        this.pages = pages;
    }

    public String getImageFormat() {
        return imageFormat;
    }

    public void setImageFormat(String imageFormat) {
        this.imageFormat = imageFormat;
    }

    public List<String> getFiles() {
        return files;
    }

    public void setFiles(List<String> files) {
        this.files = files;
    }
}
