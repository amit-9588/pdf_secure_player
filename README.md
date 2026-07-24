# Secure PDF Player

An implementation of [`Secure_PDF_Streaming_Architecture.md`](./Secure_PDF_Streaming_Architecture.md):
deliver a PDF the way HLS delivers video. Each page is rendered to an image,
**AES-256-GCM encrypted independently**, and served on demand. The browser
fetches a manifest, pulls the key once (behind auth), then decrypts and renders
pages to a `<canvas>` as you read — keeping only a small window of pages in
memory.

> Like the doc says: this raises the cost of copying and gives you real access
> control, but it **cannot** stop an authorized user from screenshotting.

## Stack

- **Backend:** Spring Boot 3.3.5 / Java 21, Apache PDFBox for rendering, JCE for AES-GCM.
- **Storage:** local filesystem (`./data/books/`). No cloud account needed.
- **Viewer:** plain HTML + WebCrypto, served from `static/` (same origin).

## Supported document types

Every upload is **normalised to a PDF first**, then the same render → encrypt →
manifest pipeline runs. So the security/streaming path never knows the source
format.

| Type | Converter | Extra dependency |
|------|-----------|------------------|
| `pdf` | passthrough | — |
| `txt` `md` `csv` `log` | PDFBox monospaced layout (pure Java) | — |
| `html` `htm` | headless **Chrome** `--print-to-pdf` (throwaway profile, never your real one) | Google Chrome |
| `docx` `doc` `pptx` `ppt` | **LibreOffice** `soffice --convert-to pdf` | LibreOffice |

Unsupported extensions are rejected at upload with HTTP 415. If a conversion
fails (e.g. LibreOffice not installed), the book goes to `FAILED` and the reason
is returned by the status endpoint and shown in the viewer.

Install the optional converters if you need them:

```bash
brew install --cask libreoffice   # docx/pptx
# Chrome is auto-detected at the default macOS path; override with SECUREPDF_CHROME
```

## Run

```bash
mvn spring-boot:run
# then open http://localhost:8090
```

In the page: pick a PDF → **Upload & process** → it polls until `READY` and opens
the book. Or type an existing book id and **Open**. Arrow keys / buttons navigate.
The `auth` field holds the bearer token (default `demo-secret-token`).

## On-disk layout

```
data/books/<bookId>/
  original.pdf     private, never served
  key.bin          raw AES key, served only via /key
  status           PROCESSING | READY | FAILED
  manifest.json    { bookId, title, pages, imageFormat, files[] }
  page-1.enc …     IV(12) || ciphertext || GCM tag(16)
```

## API

| Method | Path                              | Purpose                              |
|--------|-----------------------------------|--------------------------------------|
| POST   | `/api/v1/books`                   | upload PDF (multipart `file`)        |
| GET    | `/api/v1/books/{id}`              | processing status                    |
| GET    | `/api/v1/books/{id}/manifest`     | page count + file list               |
| GET    | `/api/v1/books/{id}/pages/{n}`    | one encrypted page blob              |
| GET    | `/api/v1/books/{id}/key`          | AES key (**the authorization gate**) |

All `/api/v1/books/**` calls require `Authorization: Bearer <token>`.

## Where to harden for production

- **`BearerTokenFilter` / the `/key` endpoint** — swap the static token for
  PoshDesk-style JWT and enforce *per-user, per-book, short-lived* entitlement
  before releasing the key. This is the single most important seam.
- **Storage** — move `books/` to a private S3 bucket; serve `.enc` via
  CloudFront signed URLs. `StorageService` is the only class that touches paths.
- **Key handling** — consider per-session key wrapping instead of returning the
  raw book key, and rotate keys on revocation.
- **Rendering** — switch `image-format` to WebP (add a TwelveMonkeys ImageIO
  plugin) for smaller pages.

## Config (`application.yml` / env)

| Key                       | Env                  | Default            |
|---------------------------|----------------------|--------------------|
| `securepdf.storage-dir`   | `SECUREPDF_STORAGE`  | `./data/books`     |
| `securepdf.render-dpi`    | `SECUREPDF_DPI`      | `200`              |
| `securepdf.auth-token`    | `SECUREPDF_TOKEN`    | `demo-secret-token`|
| `securepdf.chrome-binary` | `SECUREPDF_CHROME`   | macOS Chrome path  |
| `securepdf.soffice-binary`| `SECUREPDF_SOFFICE`  | `soffice`          |
| `securepdf.conversion-timeout-seconds` | `SECUREPDF_CONVERT_TIMEOUT` | `60` |
| `server.port`             | —                    | `8090`             |
