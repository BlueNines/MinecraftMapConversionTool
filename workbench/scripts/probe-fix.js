// Does the injected fix change the free-flight camera placement?
// Reads the camera from the viewer's own state, so a software renderer cannot mislead us.
// Usage: node probe-fix.js [url]
const fs = require("fs");
const path = require("path");
const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8199/";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "fix");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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
  const errors = [];
  page.on("pageerror", (e) => errors.push(String((e && e.message) || e)));
  page.on("console", (m) => { if (m.type() === "error") errors.push("console: " + m.text()); });

  await page.goto(URL, { waitUntil: "networkidle2", timeout: 90000 });
  await sleep(4000);

  const installed = await page.evaluate(() => {
    const s = window.__vantaloomViewer || window.__vantaloom;
    return s ? { patched: s.patched, highestGround: s.highestGround, decisions: s.decisions } : null;
  });

  // Same situation as the report: look at a far-away point from high up, then enter free flight.
  const camera = () => page.evaluate(() => {
    const c = window.bluemap.mapViewer._controlsManager;
    return { hash: location.hash, y: c.position.y, x: c.position.x, z: c.position.z, distance: c.distance };
  });

  await page.evaluate(() => { location.hash = "#source:0:0:0:1500:0:0:0:0:perspective"; });
  await sleep(2500);
  const before = await camera();

  const clicked = await page.evaluate(() => {
    const el = Array.from(document.querySelectorAll("*")).filter((e) => /自由视野|free\s*flight/i.test(e.getAttribute("title") || "")).pop();
    if (!el) return null;
    el.click();
    return el.getAttribute("title");
  });

  const after = [];
  for (let i = 1; i <= 4; i++) { await sleep(400); after.push(await camera()); }

  const decision = await page.evaluate(() => {
    const s = window.__vantaloomViewer || window.__vantaloom;
    return s ? { decisions: s.decisions, highestGround: s.highestGround, patched: s.patched } : null;
  });

  const out = { installed, before, clicked, after, decision, errors: errors.slice(0, 10) };
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(out, null, 2));
  await browser.close();
  console.log(JSON.stringify(out, null, 2));
})().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
