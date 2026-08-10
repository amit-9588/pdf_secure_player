# Secure PDF Streaming Architecture

## Goal

Deliver PDF content similarly to HLS video streaming: users fetch only
the content they need while reading instead of downloading one complete
PDF.

> Note: This improves access control and raises the cost of unauthorized
> copying, but it cannot prevent screenshots or reverse engineering by
> an authorized user.

## 1. Upload

``` text
User
  │
  ▼
Upload book.pdf
  │
  ▼
Backend API
```

The backend stores the original PDF in a private bucket and starts a
processing job.

## 2. Processing

``` text
book.pdf
   │
   ▼
PDF Processor
```

The processor:

1.  Reads the PDF.
2.  Counts pages.
3.  Renders each page (for example WebP images).
4.  Encrypts each page independently (AES-GCM).
5.  Generates a manifest.
6.  Uploads all artifacts.

## 3. Storage Layout

``` text
books/
  book123/
    original.pdf        (private)
    manifest.json
    page1.enc
    page2.enc
    page3.enc
```

Example manifest:

``` json
{
  "bookId":"book123",
  "pages":3,
  "files":[
    "page1.enc",
    "page2.enc",
    "page3.enc"
  ]
}
```

## 4. Browser Opens Book

``` text
Browser
   │
Login
   │
Backend
   │
Returns book metadata
```

Browser requests `manifest.json`, learns the page count, but does not
download every page.

## 5. Reading

``` text
Current page = 1

GET page1.enc
     │
Decrypt in memory
     │
Render to Canvas
```

When the user scrolls:

``` text
GET page2.enc
```

The viewer keeps only nearby pages cached.

## 6. Caching

``` text
Current page = 20

Cache:
18
19
20
21
22
```

Older pages are discarded from memory.

## 7. End-to-End Flow

``` text
User Uploads PDF
        │
Backend
        │
Store original privately
        │
PDF Processing Worker
        │
Split into pages
        │
Render pages
        │
Encrypt pages
        │
Create manifest.json
        │
Upload to S3
        │
CloudFront
        │
Browser requests manifest
        │
Browser requests encrypted pages on demand
        │
Decrypt in memory
        │
Render on HTML Canvas
```

## Advantages

-   Original PDF is not directly served.
-   Only viewed content is transferred.
-   Works with signed URLs or authenticated APIs.
-   Easy to revoke access.
-   CDN-friendly.

## Limitations

-   Cannot stop screenshots.
-   A determined attacker may reverse engineer the client.
-   Security depends on protecting encryption keys and short-lived
    authorization.

## 8. Advanced Security: Preventing Replay Attacks & Bot Abuse

While encrypting the chunks prevents casual theft, attackers may attempt to intercept the requests or use terminal tools (like `curl` or Postman) to scrape the API.

### Preventing Replay Attacks (Signed Cookies)
To prevent request playback without increasing load on your backend server:
1. When the user logs in and opens the book, the backend issues a **Signed Cookie** (or Signed URL) that is cryptographically validated at the CDN Edge (e.g., CloudFront).
2. The cookie is extremely short-lived (e.g., 5-10 minutes) and restricted to the specific book's path (`/books/123/*`).
3. If an attacker intercepts the request and replays it later, the CDN instantly rejects it. The backend server does zero work during playback.

### Blocking Postman / Terminal Tools
Because tools like Postman can spoof HTTP headers, standard header validation (CORS, Origin, Referer) is insufficient.
1. **WAF Bot Management:** The most robust defense is a Web Application Firewall (like Cloudflare Bot Management) which blocks non-browser TLS/TCP fingerprints before they hit the CDN.
2. **Invisible Browser Challenges:** Incorporating tools like reCAPTCHA v3 or Cloudflare Turnstile on the frontend generates a proof-of-work token that terminal tools cannot solve (because they cannot execute JS). This token is required to obtain the Signed Cookie.

## 9. Optimization: Batching (Static Chunking)

Fetching one file per page (Single-Page mode) can result in hundreds of CDN requests, which increases CDN request-based billing costs.

**How it works:**
The backend groups pages together during processing. For a batch size of 5:
- `chunk-1.enc` contains a JSON map of pages 1-5 encoded as base64 images, encrypted as one AES-GCM blob.
- `manifest.json` lists the chunks.
- When the user scrolls to page 3, the browser fetches `chunk-1.enc`, decrypts it, and extracts all 5 pages into the memory cache simultaneously.

**Pros:** Reduces CDN HTTP requests by 80% (for a batch size of 5). Easy to implement. Highly cacheable.
**Cons:** If a user jumps to a random page, they are forced to download the entire chunk (including pages they might not read), slightly increasing bandwidth usage and time-to-first-paint.

## 10. Optimization: Byte-Range Requests (Dynamic Chunking)

To achieve a true "sliding window" cache without the rigid boundaries of static chunks, the system can use HTTP `Range` requests.

**How it works:**
- The backend encrypts every single page individually (each with its own IV and Tag).
- It concatenates these raw bytes into one massive binary file (`book.dat`).
- `manifest.json` acts as a byte map, recording the exact start and end byte offsets for each encrypted page within `book.dat`.
- When the user scrolls to page 15, the client fetches exactly the bytes it needs: `Range: bytes=45000-52000`.

**Pros:** Perfect efficiency. The client can request a sliding window of exactly what it needs without downloading unread pages.
**Cons:** Slightly more complex client-side decoding. Slightly more complex to reverse-engineer than static chunks, adding a minor layer of obfuscation.

## 11. Database Metadata & Feature Expansions

While `manifest.json` contains the structural data required by the viewer, it is highly recommended to store summary metadata in your application's primary database (e.g., PostgreSQL/MongoDB) for fast querying.

**Recommended Database Fields:**
- `total_pages` (Integer): Useful for UI displays before the book is opened.
- `total_bytes` (BigInt): The exact size of the `book.dat` file.
- `processing_mode` (String): e.g., `BYTE_RANGE` or `BATCHED`.

### Feature: Resuming Reading Progress
You do not need to calculate dynamic "byte rates" to resume a user's progress. 
1. **Save Progress:** The frontend periodically pings an API (e.g., `POST /api/progress`) to save `last_read_page = 7`.
2. **Resume:** When the user returns, the backend instructs the viewer to start at page 7.
3. **Lookup:** The viewer downloads the `manifest.json`, looks up page 7, and instantly sees it mapped to bytes `45000-52000`. It fires the exact `Range` request and renders the page instantly, skipping pages 1-6 entirely.

### Feature: Offline Reading Support
If you want to grant a user offline access to a book:
1. The client requests the entire `book.dat` without the `Range` header and saves the large file locally (e.g., in IndexedDB).
2. The client also saves the `manifest.json` and the AES key locally.
3. When the user reads offline, the JavaScript viewer uses `Blob.slice(start, end)` on the locally stored `book.dat` file to extract the exact encrypted bytes for the current page, decrypting and rendering them exactly as it would over the network.
