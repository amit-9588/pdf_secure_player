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
