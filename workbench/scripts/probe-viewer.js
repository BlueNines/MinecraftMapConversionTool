// Sweep the viewer camera distance and measure what is actually drawn.
// The point is to separate "the renderer cannot draw at all" (a headless artefact)
// from "only the far view is blank" (the reported bug).
// Usage: node probe-viewer.js [url]
const fs = require("fs");
const path = require("path");

const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");
const { PNG } = require(MODS + "/pngjs");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8199/";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "sweep");

// The viewer reads its camera from the URL hash: map:x:y:z:distance:rotation:angle:tilt:ortho:view
const VIEWS = [
  ["perspective-d100", "perspective", 100],
  ["perspective-d400", "perspective", 400],
  ["perspective-d1500", "perspective", 1500],
  ["perspective-d4000", "perspective", 4000],
  ["free-d1500", "free", 1500],
  ["free-d4000", "free", 4000],
  ["flat-d1500", "flat", 1500]
];

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function stats(buffer) {
  const png = PNG.sync.read(Buffer.from(buffer));
  const counts = new Map();
  const n = png.width * png.height;
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const r = png.data[i * 4], g = png.data[i * 4 + 1], b = png.data[i * 4 + 2];
    sum += (r + g + b) / 3;
    const key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  let modalKey = 0, modal = 0;
  for (const [k, v] of counts) if (v > modal) { modal = v; modalKey = k; }
  const to255 = (q) => q * 17;
  return {
    colors: counts.size,
    modalShare: +(modal / n).toFixed(4),
    modalRGB: [to255((modalKey >> 8) & 15), to255((modalKey >> 4) & 15), to255(modalKey & 15)],
    meanLuma: +(sum / n).toFixed(2)
  };
}

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: EDGE,
    headless: "new",
    args: ["--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,860",
           "--enable-unsafe-swiftshader", "--use-gl=angle", "--use-angle=swiftshader",
           "--ignore-gpu-blocklist"]
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 860 });

  const errors = [], failed = [];
  let tileRequests = [];
  page.on("pageerror", (e) => errors.push(String((e && e.message) || e)));
  page.on("console", (m) => { if (m.type() === "error") errors.push("console: " + m.text()); });
  page.on("requestfailed", (r) => failed.push(r.url()));
  page.on("response", (r) => {
    const u = r.url();
    if (u.includes("/tiles/")) tileRequests.push(r.status() + " " + u.slice(u.indexOf("/tiles/")));
    else if (r.status() >= 400) failed.push(r.status() + " " + u);
  });

  await page.goto(URL, { waitUntil: "networkidle2", timeout: 90000 });

  // Which renderer did we actually get? A headless software renderer is a real confound.
  const renderer = await page.evaluate(() => {
    const c = document.createElement("canvas");
    const gl = c.getContext("webgl2") || c.getContext("webgl");
    if (!gl) return "no-webgl";
    const dbg = gl.getExtension("WEBGL_debug_renderer_info");
    return dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);
  });

  const rows = [];
  for (const [tag, view, distance] of VIEWS) {
    tileRequests = [];
    await page.evaluate((h) => { location.hash = h; }, `#source:0:0:0:${distance}:0:0:0:0:${view}`);
    // Give the camera tween and the tile loaders time to settle.
    await sleep(3500);
    const buf = await page.screenshot();
    fs.writeFileSync(path.join(OUT, tag + ".png"), buf);
    const s = stats(buf);
    rows.push({
      tag, view, distance, ...s, hash: await page.evaluate(() => location.hash),
      tiles200: tileRequests.filter((x) => x.startsWith("200")).length,
      tiles204: tileRequests.filter((x) => x.startsWith("204")).length,
      tilesFail: tileRequests.filter((x) => !/^(200|204)/.test(x)).length
    });
  }

  const out = { url: URL, renderer, rows, errors: errors.slice(0, 30), failed: Array.from(new Set(failed)).slice(0, 30) };
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(out, null, 2));
  await browser.close();
  console.log(JSON.stringify(out, null, 2));
}

main().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
