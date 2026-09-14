// Two questions at once, both answered by watching the real viewer:
//  1. Does a script registered in webapp.conf actually execute? (decides the hot-update design)
//  2. Does entering free-flight reset the camera? (the reported black screen)
// Usage: node probe-inject.js [url]
const fs = require("fs");
const path = require("path");

const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8199/";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "inject");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function main() {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: EDGE,
    headless: "new",
    args: ["--no-sandbox", "--disable-dev-shm-usage", "--window-size=1280,860",
           "--enable-unsafe-swiftshader", "--use-gl=angle", "--use-angle=swiftshader"]
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1280, height: 860 });

  const loaded = [];
  page.on("response", (r) => { if (/\.(js|css)$/.test(r.url())) loaded.push(r.status() + " " + r.url().replace(URL.replace(/\/$/, ""), "")); });
  await page.goto(URL, { waitUntil: "networkidle2", timeout: 90000 });
  await sleep(3000);

  const injection = await page.evaluate(() => ({
    ok: window.__VM_OK === true,
    mark: window.__VM_MARK || null,
    styleVar: getComputedStyle(document.documentElement).getPropertyValue("--vm-injected").trim()
  }));

  // Reach into the shadow roots to find the real view controls.
  await page.evaluate(`window.__deep = function (sel) {
    const out = [];
    const walk = (r) => {
      for (const el of r.querySelectorAll(sel)) out.push(el);
      for (const el of r.querySelectorAll("*")) if (el.shadowRoot) walk(el.shadowRoot);
    };
    walk(document);
    return out;
  };`);

  const controls = await page.evaluate(() =>
    window.__deep("button, a, [role=button]").map((el) => ({
      title: el.getAttribute("title") || "",
      aria: el.getAttribute("aria-label") || "",
      cls: String(el.className || "").slice(0, 50),
      text: (el.textContent || "").trim().slice(0, 30)
    })));

  async function hash() { return page.evaluate(() => location.hash); }

  // Baseline: a specific camera, no clicking at all. If this drifts, the page rewrites the hash by itself.
  const trace = [];
  await page.evaluate(() => { location.hash = "#source:640:-256:640:1200:0:0:0:0:perspective"; });
  for (const t of [0, 500, 1000, 2000, 3000]) {
    if (t) await sleep(t === 500 ? 500 : 500);
    trace.push({ at: t, hash: await hash(), shot: t === 3000 ? "baseline" : null });
  }
  const baseline = await page.screenshot();
  fs.writeFileSync(path.join(OUT, "baseline.png"), baseline);

  // Now click free-flight and keep watching the hash.
  const clicked = await page.evaluate(() => {
    const hit = window.__deep("button, a, [role=button]").find((el) =>
      /free[\s-]?flight|freeflight/i.test((el.getAttribute("title") || "") + " " + (el.getAttribute("aria-label") || "")));
    if (!hit) return null;
    const label = hit.getAttribute("title") || hit.getAttribute("aria-label") || "";
    hit.click();
    return label;
  });

  const afterFree = [];
  for (let i = 0; i < 6; i++) {
    await sleep(500);
    afterFree.push({ at: (i + 1) * 500, hash: await hash() });
  }
  const freeShot = await page.screenshot();
  fs.writeFileSync(path.join(OUT, "after-free.png"), freeShot);

  const out = { injection, controls, trace, clicked, afterFree, scriptsLoaded: loaded };
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(out, null, 2));
  await browser.close();
  console.log(JSON.stringify({
    injection,
    clicked,
    trace,
    afterFree,
    controlTitles: controls.map((c) => c.title || c.aria).filter(Boolean)
  }, null, 2));
}

main().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
