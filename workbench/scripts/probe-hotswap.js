// Can a loaded tile be replaced in place, without reloading the page?
//
// This is the make-or-break question for tile-level hot updates: if the viewer
// keeps showing a cached texture forever, only a full frame reload works.
//
// Usage: node probe-hotswap.js <viewerUrl> <tileFile> <mapRootUrl>
const fs = require("fs");
const path = require("path");
const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8199/";
// A tile that the far view actually loads, plus where it lives on disk.
const TILE_FILE = process.argv[3] || "D:/fixB/web/maps/source/tiles/1/x-1/z0.png";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "hotswap");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// A tiny solid magenta PNG, so a swapped tile is unmistakable.
function magentPng() {
  const zlib = require("zlib");
  const w = 8, h = 8;
  const raw = Buffer.alloc((w * 3 + 1) * h);
  for (let y = 0; y < h; y++) {
    const off = y * (w * 3 + 1);
    raw[off] = 0;
    for (let x = 0; x < w; x++) {
      raw[off + 1 + x * 3] = 255;
      raw[off + 2 + x * 3] = 0;
      raw[off + 3 + x * 3] = 255;
    }
  }
  const crcTable = [];
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crcTable[n] = c >>> 0;
  }
  const crc32 = (buf) => {
    let c = 0xffffffff;
    for (const b of buf) c = crcTable[(c ^ b) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  };
  const chunk = (type, data) => {
    const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, "ascii"), data]);
    const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 2; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw)), chunk("IEND", Buffer.alloc(0))
  ]);
}

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: EDGE,
    headless: "new",
    args: ["--no-sandbox", "--disable-dev-shm-usage", "--window-size=1400,900",
           "--enable-unsafe-swiftshader", "--use-gl=angle", "--use-angle=swiftshader"]
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1400, height: 900 });

  const tileRequests = [];
  const errors = [];
  page.on("request", (r) => { if (r.url().includes("/tiles/")) tileRequests.push(r.url()); });
  page.on("pageerror", (e) => errors.push(String((e && e.message) || e)));

  await page.goto(URL, { waitUntil: "networkidle2", timeout: 90000 });
  // A far camera loads the lowres tiles, which is the layer a hot update would touch.
  await page.evaluate(() => { location.hash = "#source:0:0:0:4000:0:0:0:0:perspective"; });
  await sleep(6000);

  // Mark the page so a reload is detectable.
  await page.evaluate(() => { window.__noReload = (window.__noReload || 0) + 1; });
  const markerBefore = await page.evaluate(() => window.__noReload);

  // What tiles does the viewer actually hold?
  const loaded = await page.evaluate(() => {
    const mv = window.bluemap && window.bluemap.mapViewer;
    const map = mv && mv.map;
    if (!map) return { error: "no map" };
    const out = [];
    const managers = [];
    if (map.hiresTileManager) managers.push(["hires", map.hiresTileManager]);
    (map.lowresTileManager || []).forEach((m, i) => managers.push(["lowres" + i, m]));
    for (const [kind, mgr] of managers) {
      const scene = mgr && mgr.scene;
      // The loader caches textures; look at what its material holds.
      const kids = (scene && scene.children) || [];
      for (const c of kids) {
        const url = c.userData && c.userData.tileUrl;
        const u = c.material && c.material.uniforms;
        const tex = u && u.textureImage && u.textureImage.value;
        out.push({
          kind,
          url: url || null,
          hasTexture: !!tex,
          imageSrc: tex && tex.image && (tex.image.currentSrc || tex.image.src) ? (tex.image.currentSrc || tex.image.src) : null
        });
      }
    }
    return { count: out.length, tiles: out.slice(0, 8), mapDataRoot: map.data && map.data.mapDataRoot };
  });

  // Change one tile on disk.
  const before = fs.readFileSync(TILE_FILE);
  fs.writeFileSync(TILE_FILE, magentPng());

  // Ask the viewer to re-fetch the tiles it is holding, replacing the images in place.
  const swapped = await page.evaluate(async () => {
    const mv = window.bluemap && window.bluemap.mapViewer;
    const map = mv && mv.map;
    const done = [];
    const managers = [];
    if (map.hiresTileManager) managers.push(map.hiresTileManager);
    (map.lowresTileManager || []).forEach((m) => managers.push(m));
    const stamp = Date.now();
    for (const mgr of managers) {
      for (const c of ((mgr.scene && mgr.scene.children) || [])) {
        const url = c.userData && c.userData.tileUrl;
        const u = c.material && c.material.uniforms;
        const tex = u && u.textureImage && u.textureImage.value;
        if (!url || !tex) continue;
        // Same tile, new query string: this is what defeats the browser cache.
        const bust = url + (url.includes("?") ? "&" : "?") + "v=" + stamp;
        try {
          const img = await new Promise((res, rej) => {
            const i = new Image();
            i.crossOrigin = "anonymous";
            i.onload = () => res(i);
            i.onerror = rej;
            i.src = bust;
          });
          tex.image = img;
          tex.needsUpdate = true;
          done.push({ url, swapped: true });
        } catch (e) {
          done.push({ url, swapped: false, error: String(e.message || e) });
        }
      }
    }
    return done;
  });

  await sleep(2500);
  const markerAfter = await page.evaluate(() => window.__noReload);

  // Put the original tile back so the test leaves no trace.
  fs.writeFileSync(TILE_FILE, before);

  const out = {
    url: URL,
    tileFile: TILE_FILE,
    loaded,
    swappedCount: swapped.length,
    swappedOk: swapped.filter((s) => s.swapped).length,
    swappedSample: swapped.slice(0, 6),
    reloadedPage: markerBefore !== markerAfter,
    markerBefore,
    markerAfter,
    tileRequestsAfterChange: tileRequests.filter((u) => u.includes("v=")).length,
    totalTileRequests: tileRequests.length,
    errors: errors.slice(0, 10)
  };
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(out, null, 2));
  await browser.close();
  console.log(JSON.stringify(out, null, 2));
})().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
