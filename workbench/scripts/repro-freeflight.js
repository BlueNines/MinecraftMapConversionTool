// Reproduce the reported "source map goes black ~1s after entering free-flight view".
// Measures the actual composited pixels: a black screen is a nearly uniform image.
// Usage: node repro-freeflight.js [url]
const fs = require("fs");
const path = require("path");

const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");
const { PNG } = require(MODS + "/pngjs");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8770/";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "shots");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Quantise to 4 bits per channel: enough to separate "a picture is drawn" from "flat black".
function stats(buffer) {
  const png = PNG.sync.read(Buffer.from(buffer));
  const counts = new Map();
  const n = png.width * png.height;
  let sum = 0;
  for (let i = 0; i < n; i++) {
    const r = png.data[i * 4] >> 4, g = png.data[i * 4 + 1] >> 4, b = png.data[i * 4 + 2] >> 4;
    sum += (png.data[i * 4] + png.data[i * 4 + 1] + png.data[i * 4 + 2]) / 3;
    const key = (r << 8) | (g << 4) | b;
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  let modal = 0;
  for (const v of counts.values()) if (v > modal) modal = v;
  return {
    colors: counts.size,
    modalShare: +(modal / n).toFixed(4),
    nonModalShare: +(1 - modal / n).toFixed(4),
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
           "--ignore-gpu-blocklist", "--enable-features=Vulkan"]
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 860 });

  const errors = [], failed = [], contextLost = [];
  page.on("pageerror", (e) => errors.push(String((e && e.message) || e)));
  page.on("console", (m) => { if (m.type() === "error") errors.push("console: " + m.text()); });
  page.on("requestfailed", (r) => failed.push(r.url() + " :: " + ((r.failure() && r.failure().errorText) || "?")));
  page.on("response", (r) => { if (r.status() >= 400) failed.push(r.status() + " " + r.url()); });
  await page.evaluateOnNewDocument(() => {
    window.__ctxLost = [];
    const orig = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (...a) {
      const ctx = orig.apply(this, a);
      if (ctx && ctx.canvas && a[0] && String(a[0]).startsWith("webgl")) {
        ctx.canvas.addEventListener("webglcontextlost", (e) => window.__ctxLost.push("lost:" + String(e.statusMessage || "")));
        ctx.canvas.addEventListener("webglcontextrestored", () => window.__ctxLost.push("restored"));
      }
      return ctx;
    };
  });

  const report = { url: URL, steps: [], requests: [] };
  page.on("response", (r) => { if (/\.(png|prbm|prbm\.gz|json)$/.test(r.url())) report.requests.push(r.status() + " " + r.url().replace(URL.replace(/\/$/, ""), "")); });

  await page.goto(URL, { waitUntil: "networkidle2", timeout: 90000 });

  async function shot(tag) {
    const buf = await page.screenshot();
    fs.writeFileSync(path.join(OUT, tag + ".png"), buf);
    return stats(buf);
  }

  // Wait until the viewer has actually drawn something.
  let loaded = false;
  for (let i = 0; i < 60; i++) {
    await sleep(500);
    const s = stats(await page.screenshot());
    if (s.nonModalShare > 0.05) { loaded = true; break; }
  }
  report.loaded = loaded;
  report.canvas = await page.evaluate(() => {
    const c = document.querySelector("canvas");
    return c ? { w: c.width, h: c.height, cw: c.clientWidth, ch: c.clientHeight } : null;
  });
  report.hashBefore = await page.evaluate(() => location.hash);

  // BlueMap's UI lives inside shadow roots, so a plain querySelectorAll sees nothing.
  // This is the helper every control lookup below goes through.
  await page.evaluate(`window.__deep = function (sel, root) {
    const out = [];
    const walk = (r) => {
      for (const el of r.querySelectorAll(sel)) out.push(el);
      for (const el of r.querySelectorAll("*")) if (el.shadowRoot) walk(el.shadowRoot);
    };
    walk(root || document);
    return out;
  };`);

  // Every control BlueMap exposes, so the click hits the real one.
  report.controls = await page.evaluate(() =>
    window.__deep("button, a, [role=button]").map((el) => ({
      title: el.getAttribute("title") || "",
      aria: el.getAttribute("aria-label") || "",
      cls: String(el.className || "").slice(0, 70),
      text: (el.textContent || "").trim().slice(0, 40)
    })));

  report.steps.push({ tag: "01-before", ...(await shot("01-before")), hash: await page.evaluate(() => location.hash) });

  // Click the free-flight control (title/aria is our only stable handle across versions).
  report.clicked = await page.evaluate(() => {
    const hit = window.__deep("button, a, [role=button]").find((el) =>
      /free[\s-]?flight|freeflight/i.test((el.getAttribute("title") || "") + " " + (el.getAttribute("aria-label") || "")));
    if (!hit) return null;
    const label = hit.getAttribute("title") || hit.getAttribute("aria-label") || "";
    hit.click();
    return label;
  });

  const marks = [[200, "02-t0.2s"], [800, "03-t1.0s"], [1000, "04-t2.0s"], [1000, "05-t3.0s"], [2000, "06-t5.0s"], [5000, "07-t10.0s"]];
  for (const [delay, tag] of marks) {
    await sleep(delay);
    report.steps.push({ tag, ...(await shot(tag)), hash: await page.evaluate(() => location.hash) });
  }

  report.errors = errors.slice(0, 40);
  report.failedRequests = Array.from(new Set(failed)).slice(0, 40);
  report.webglEvents = await page.evaluate(() => window.__ctxLost || []);
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(report, null, 2));
  await browser.close();

  console.log(JSON.stringify({
    loaded, canvas: report.canvas, clicked: report.clicked,
    steps: report.steps, errorCount: errors.length, failed: report.failedRequests.slice(0, 12)
  }, null, 2));
}

main().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
