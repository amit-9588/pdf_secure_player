/*
 * Secure PDF Player — browser viewer.
 *
 * Flow: open a book -> fetch manifest (page count) and the AES key once ->
 * as the user navigates, fetch each page's encrypted blob on demand, decrypt
 * it in memory with WebCrypto, and paint it to the canvas. Only a small window
 * of pages around the current one is kept decoded in memory; the rest are
 * discarded — mirroring the HLS-style "stream what you're reading" model.
 */

const WINDOW = 2; // keep pages [current-2 .. current+2] cached + prefetched

const state = {
  bookId: null,
  pages: 0,
  cryptoKey: null,     // CryptoKey imported from the /key endpoint
  current: 1,
  cache: new Map(),    // pageNumber -> ImageBitmap
  inflight: new Map(), // pageNumber -> Promise<ImageBitmap>
};

const $ = (id) => document.getElementById(id);
const canvas = $("pageCanvas");
const ctx = canvas.getContext("2d");

function msg(text, isError) {
  const el = $("msg");
  el.textContent = text || "";
  el.style.color = isError ? "#ff8a8a" : "#ffcf7a";
}

function authHeaders() {
  return { Authorization: "Bearer " + $("token").value.trim() };
}

async function api(path, opts = {}) {
  const res = await fetch(path, { ...opts, headers: { ...authHeaders(), ...(opts.headers || {}) } });
  if (!res.ok) {
    let detail = res.status + " " + res.statusText;
    try { const j = await res.json(); if (j.error || j.message) detail = j.error || j.message; } catch (_) {}
    throw new Error(detail);
  }
  return res;
}

// --- upload ----------------------------------------------------------------

async function upload() {
  const file = $("file").files[0];
  if (!file) { msg("Choose a PDF first.", true); return; }

  msg("Uploading " + file.name + " …");
  const form = new FormData();
  form.append("file", file);
  form.append("title", file.name);

  try {
    const res = await api("/api/v1/books", { method: "POST", body: form });
    const { data } = await res.json();
    $("bookId").value = data.bookId;
    msg("Uploaded. Processing book " + data.bookId + " …");
    await waitUntilReady(data.bookId);
    await openBook(data.bookId);
  } catch (e) {
    msg("Upload failed: " + e.message, true);
  }
}

async function waitUntilReady(bookId) {
  for (let i = 0; i < 600; i++) { // up to ~5 min
    const res = await api("/api/v1/books/" + bookId);
    const { data } = await res.json();
    if (data.status === "READY") return;
    if (data.status === "FAILED") throw new Error(data.error || "processing failed");
    msg("Processing… (" + data.status + ")");
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error("processing timed out");
}

// --- open / key ------------------------------------------------------------

async function openBook(bookId) {
  try {
    msg("Opening book …");
    resetCache();
    state.bookId = bookId;

    const manifest = (await (await api(`/api/v1/books/${bookId}/manifest`)).json()).data;
    state.pages = manifest.pages;
    state.manifest = manifest;

    if (manifest.processingMode === "BYTE_RANGE" || !manifest.processingMode) {
      const binRes = await api(`/api/v1/books/${bookId}/manifest.bin`);
      const buffer = await binRes.arrayBuffer();
      const view = new DataView(buffer);
      state.pageSizes = new Uint32Array(manifest.pages);
      for (let i = 0; i < manifest.pages; i++) {
        state.pageSizes[i] = view.getUint32(i * 4, true); // true = little-endian
      }
    }

    const keyInfo = (await (await api(`/api/v1/books/${bookId}/key`)).json()).data;
    const rawKey = base64ToBytes(keyInfo.key);
    state.cryptoKey = await crypto.subtle.importKey(
      "raw", rawKey, { name: "AES-GCM" }, false, ["decrypt"]
    );

    state.current = 1;
    msg(`Loaded "${manifest.title}" — ${manifest.pages} pages. (Mode: ${manifest.processingMode || 'SINGLE_PAGE'})`);
    await showPage(1);
  } catch (e) {
    msg("Open failed: " + e.message, true);
  }
}

// --- page fetch + decrypt --------------------------------------------------

const inflightChunks = new Map(); // For BATCHED mode dedup

async function getPage(pageNumber) {
  if (pageNumber < 1 || pageNumber > state.pages) return null;
  if (state.cache.has(pageNumber)) return state.cache.get(pageNumber);
  if (state.inflight.has(pageNumber)) return state.inflight.get(pageNumber);

  const promise = (async () => {
    const mode = state.manifest.processingMode || "SINGLE_PAGE";

    if (mode === "BATCHED") {
      // Find which chunk contains this page
      let targetChunk = null;
      for (const [chunkName, pages] of Object.entries(state.manifest.chunkMap)) {
        if (pages.includes(pageNumber)) { targetChunk = chunkName; break; }
      }
      if (!targetChunk) throw new Error("Page not found in any chunk");

      // Deduplicate chunk fetches
      if (!inflightChunks.has(targetChunk)) {
        const chunkPromise = (async () => {
          const res = await api(`/api/v1/books/${state.bookId}/chunks/${targetChunk}`);
          const blob = new Uint8Array(await res.arrayBuffer());
          const iv = blob.slice(0, 12);
          const ciphertext = blob.slice(12);
          const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, state.cryptoKey, ciphertext);
          
          // plain is JSON: { "1": "base64...", "2": "base64..." }
          const text = new TextDecoder().decode(plain);
          const chunkData = JSON.parse(text);
          
          // Cache all pages from this chunk
          for (const [pStr, b64] of Object.entries(chunkData)) {
            const pNum = parseInt(pStr);
            const imgBytes = base64ToBytes(b64);
            const bitmap = await createImageBitmap(new Blob([imgBytes]));
            state.cache.set(pNum, bitmap);
          }
        })();
        inflightChunks.set(targetChunk, chunkPromise);
        await chunkPromise;
        inflightChunks.delete(targetChunk);
      } else {
        await inflightChunks.get(targetChunk);
      }
      return state.cache.get(pageNumber);

    } else if (mode === "BYTE_RANGE") {
      // Byte Range: Sliding window fetch over book.dat using Binary Manifest sizes
      if (!state.pageSizes || state.pageSizes.length < pageNumber) {
         throw new Error("Invalid binary manifest for page " + pageNumber);
      }
      
      let start = 0;
      for (let i = 0; i < pageNumber - 1; i++) {
        start += state.pageSizes[i];
      }
      let end = start + state.pageSizes[pageNumber - 1] - 1;
      
      const rangeStr = `${start}-${end}`;
      
      const res = await api(`/api/v1/books/${state.bookId}/book.dat`, {
        headers: { "Range": `bytes=${rangeStr}` }
      });
      const blob = new Uint8Array(await res.arrayBuffer());
      const iv = blob.slice(0, 12);
      const ciphertext = blob.slice(12);
      const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, state.cryptoKey, ciphertext);
      
      const bitmap = await createImageBitmap(new Blob([plain]));
      state.cache.set(pageNumber, bitmap);
      return bitmap;

    } else {
      // SINGLE_PAGE mode (Existing)
      const res = await api(`/api/v1/books/${state.bookId}/pages/${pageNumber}`);
      const blob = new Uint8Array(await res.arrayBuffer());
      const iv = blob.slice(0, 12);
      const ciphertext = blob.slice(12);
      const plain = await crypto.subtle.decrypt({ name: "AES-GCM", iv }, state.cryptoKey, ciphertext);
      
      const bitmap = await createImageBitmap(new Blob([plain]));
      state.cache.set(pageNumber, bitmap);
      return bitmap;
    }
  })();

  state.inflight.set(pageNumber, promise);
  try {
    return await promise;
  } finally {
    state.inflight.delete(pageNumber);
  }
}

async function showPage(pageNumber) {
  if (pageNumber < 1 || pageNumber > state.pages) return;
  state.current = pageNumber;
  updateNav();

  const bitmap = await getPage(pageNumber);
  if (!bitmap || state.current !== pageNumber) return; // navigated away meanwhile

  drawBitmap(bitmap);
  prefetchWindow();
  evictOutsideWindow();
  updateCacheInfo();
}

function drawBitmap(bitmap) {
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.drawImage(bitmap, 0, 0);
}

// --- sliding cache window --------------------------------------------------

function prefetchWindow() {
  for (let p = state.current - WINDOW; p <= state.current + WINDOW; p++) {
    if (p >= 1 && p <= state.pages && !state.cache.has(p)) {
      getPage(p).catch(() => {}); // best-effort neighbour prefetch
    }
  }
}

function evictOutsideWindow() {
  for (const p of state.cache.keys()) {
    if (p < state.current - WINDOW || p > state.current + WINDOW) {
      const bmp = state.cache.get(p);
      if (bmp && bmp.close) bmp.close(); // free the decoded image
      state.cache.delete(p);
    }
  }
}

function resetCache() {
  for (const bmp of state.cache.values()) if (bmp && bmp.close) bmp.close();
  state.cache.clear();
  state.inflight.clear();
}

// --- ui --------------------------------------------------------------------

function updateNav() {
  $("pageInfo").textContent = state.pages ? `Page ${state.current} / ${state.pages}` : "—";
  $("prevBtn").disabled = state.current <= 1;
  $("nextBtn").disabled = state.current >= state.pages;
}

function updateCacheInfo() {
  const cached = [...state.cache.keys()].sort((a, b) => a - b).join(", ");
  $("cacheInfo").textContent = "cached pages: [" + cached + "]";
}

// --- helpers ---------------------------------------------------------------

function base64ToBytes(b64) {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

// --- wiring ----------------------------------------------------------------

$("uploadBtn").addEventListener("click", upload);
$("openBtn").addEventListener("click", () => {
  const id = $("bookId").value.trim();
  if (id) openBook(id);
});
$("prevBtn").addEventListener("click", () => showPage(state.current - 1));
$("nextBtn").addEventListener("click", () => showPage(state.current + 1));

document.addEventListener("keydown", (e) => {
  if (!state.bookId) return;
  if (e.key === "ArrowLeft") showPage(state.current - 1);
  if (e.key === "ArrowRight") showPage(state.current + 1);
});

updateNav();
