// The decisive test for the reported black screen, read from the viewer's own state
// instead of from screenshots (a software renderer makes screenshots unreliable here).
//
// Controls carry Chinese titles in this build, e.g. "自由视野 / 观察者模式".
// Usage: node probe-freestate.js [url]
const fs = require("fs");
const path = require("path");
const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8199/";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "freestate");
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

  // What is actually reachable from the page?
  const shape = await page.evaluate(() => {
    const bm = window.bluemap;
    const out = { hasBluemap: !!bm, bluemapKeys: bm ? Object.keys(bm) : [] };
    try { out.mapViewerKeys = bm && bm.mapViewer ? Object.keys(bm.mapViewer) : []; } catch (e) { out.mapViewerKeys = ["<throws>"]; }
    try { out.controlsKeys = bm && bm.mapViewer && bm.mapViewer.controls ? Object.keys(bm.mapViewer.controls) : []; } catch (e) { out.controlsKeys = ["<throws>"]; }
    return out;
  });

  // A compact, comparable snapshot of where the camera is and what the viewer thinks it shows.
  const snap = () => page.evaluate(() => {
    const bm = window.bluemap;
    const mv = bm && bm.mapViewer;
    const s = { hash: location.hash };
    try {
      s.mapId = mv.data && mv.data.map ? mv.data.map.id : null;
      s.mapState = mv.data ? mv.data.mapState : null;
      s.loadedCenter = mv.data && mv.data.loadedCenter ? [mv.data.loadedCenter.x, mv.data.loadedCenter.y] : null;
      s.hiresDistance = mv.data ? mv.data.loadedHiresViewDistance : null;
      s.lowresDistance = mv.data ? mv.data.loadedLowresViewDistance : null;
    } catch (e) { s.dataError = String(e.message); }
    try {
      const c = mv.controls;
      if (c) {
        s.controlState = c.state;
        s.position = c.position ? [c.position.x, c.position.y, c.position.z] : null;
        s.distance = c.distance;
        s.rotation = c.rotation; s.angle = c.angle; s.tilt = c.tilt; s.ortho = c.ortho;
      }
    } catch (e) { s.controlError = String(e.message); }
    try {
      const tm = mv.map && mv.map.hiresTileManager;
      s.hiresTiles = tm && tm.tiles ? tm.tiles.length : (tm ? "no-tiles-field" : null);
      const lm = mv.map && mv.map.lowresTileManager;
      s.lowresManagers = lm ? lm.length : null;
      if (lm && lm.length) {
        s.lowresTileCounts = lm.map((m) => (m && m.tiles ? m.tiles.length : -1));
        s.lowresScenes = lm.map((m) => (m && m.scene ? m.scene.children.length : -1));
      }
    } catch (e) { s.tileError = String(e.message); }
    return s;
  });

  const before = await snap();

  // Reveal the hover-only menu, then click the free-flight control by its title.
  await page.mouse.move(700, 450); await sleep(300);
  await page.mouse.move(60, 60); await sleep(1000);

  const clicked = await page.evaluate(() => {
    const all = Array.from(document.querySelectorAll("*")).filter((el) => {
      const t = el.getAttribute("title") || "";
      return /自由视野|free\s*flight/i.test(t);
    });
    if (!all.length) return { found: 0 };
    const el = all[all.length - 1];
    el.click();
    return { found: all.length, title: el.getAttribute("title"), tag: el.tagName.toLowerCase() };
  });

  const trace = [];
  for (let i = 1; i <= 8; i++) {
    await sleep(400);
    const s = await snap();
    trace.push({ at: i * 400, ...s });
  }
  const shot = await page.screenshot();
  fs.writeFileSync(path.join(OUT, "after-free.png"), shot);

  const out = { shape, before, clicked, trace, errors: errors.slice(0, 20) };
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(out, null, 2));
  await browser.close();
  console.log(JSON.stringify(out, null, 2));
})().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
