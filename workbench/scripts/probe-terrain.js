// Verify the free-flight black screen root cause directly.
//
// BlueMap's own code does:  y = terrainHeightAt(x,z) + 3 || currentY
// If terrainHeightAt returns 0 the "+3" is 3, which is truthy, so the fallback never runs
// and the camera is placed at y=3 - underground. This samples the function itself.
// Usage: node probe-terrain.js [url]
const fs = require("fs");
const path = require("path");
const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8199/";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "terrain");
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

  await page.goto(URL, { waitUntil: "networkidle2", timeout: 90000 });

  // Sweep the camera across the map so tiles for each spot actually load first.
  const spots = [];
  for (const x of [-256, -64, 0, 64, 256, 640]) {
    for (const z of [-256, -64, 0, 64, 256, 640]) spots.push([x, z]);
  }

  for (const [x, z] of spots) {
    await page.evaluate((h) => { location.hash = h; }, `#source:${x}:0:${z}:800:0:0:0:0:perspective`);
    await sleep(250);
  }
  await sleep(3000);

  const probe = await page.evaluate((spots) => {
    const mv = window.bluemap && window.bluemap.mapViewer;
    if (!mv || !mv.map) return { error: "no map object" };
    const map = mv.map;
    const has = typeof map.terrainHeightAt === "function";
    const rows = spots.map(([x, z]) => {
      let v, err = null;
      try { v = map.terrainHeightAt(x, z); } catch (e) { err = String(e.message); }
      return { x, z, h: v === undefined ? "undefined" : v, err };
    });
    // A sanity value the free-flight code would compute.
    const zeroish = rows.filter((r) => !r.h || r.h === 0 || r.h === "undefined").length;
    return { hasTerrainHeightAt: has, zeroish, total: rows.length, rows };
  }, spots);

  const out = { url: URL, probe, errors: errors.slice(0, 10) };
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(out, null, 2));
  await browser.close();

  // Print a compact grid, that is all we need to see.
  if (probe.rows) {
    const xs = [...new Set(probe.rows.map((r) => r.x))].sort((a, b) => a - b);
    const zs = [...new Set(probe.rows.map((r) => r.z))].sort((a, b) => a - b);
    console.log("terrainHeightAt(x,z)   rows=x, cols=z");
    console.log("        " + zs.map((z) => String(z).padStart(7)).join(""));
    for (const x of xs) {
      const line = zs.map((z) => {
        const r = probe.rows.find((q) => q.x === x && q.z === z) || {};
        return String(r.h).padStart(7);
      }).join("");
      console.log(String(x).padStart(7) + " " + line);
    }
  }
  console.log(JSON.stringify({ hasTerrainHeightAt: probe.hasTerrainHeightAt, zeroish: probe.zeroish, total: probe.total }, null, 2));
})().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
