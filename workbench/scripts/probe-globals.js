// Ask the running viewer directly instead of trusting screenshots.
// 1. Are any internals reachable from window (so tile state can be measured)?
// 2. Do the view controls appear only on hover? (they were invisible to earlier probes)
// Usage: node probe-globals.js [url]
const fs = require("fs");
const path = require("path");
const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8199/";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "globals");
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

  const globals = await page.evaluate(() => {
    const keys = new Set();
    for (const k of Object.getOwnPropertyNames(window)) {
      if (/blue|map|viewer|three|scene/i.test(k)) keys.add(k);
    }
    const described = {};
    for (const k of keys) {
      try {
        const v = window[k];
        described[k] = v === null ? "null" : Array.isArray(v) ? "array[" + v.length + "]" : typeof v;
      } catch (e) { described[k] = "<throws>"; }
    }
    return described;
  });

  // Custom elements are how the viewer embeds itself; their shadow roots hold the UI.
  const elements = await page.evaluate(() => {
    const out = [];
    const walk = (root, depth) => {
      for (const el of root.querySelectorAll("*")) {
        const tag = el.tagName.toLowerCase();
        if (tag.includes("-") || el.shadowRoot) {
          out.push({
            tag, depth,
            shadow: !!el.shadowRoot,
            children: el.shadowRoot ? el.shadowRoot.querySelectorAll("*").length : el.children.length,
            cls: String(el.className || "").slice(0, 40)
          });
        }
        if (el.shadowRoot) walk(el.shadowRoot, depth + 1);
      }
    };
    walk(document, 0);
    return out.slice(0, 40);
  });

  // Nudge the pointer: BlueMap's menu bar is hover-revealed.
  await page.mouse.move(700, 450);
  await sleep(300);
  await page.mouse.move(60, 60);
  await sleep(1200);
  const afterHover = await page.evaluate(() => {
    const out = [];
    const walk = (r) => {
      for (const el of r.querySelectorAll("button, a, [role=button], [title], [aria-label]")) {
        const t = el.getAttribute("title") || el.getAttribute("aria-label") || "";
        if (t) out.push(t);
      }
      for (const el of r.querySelectorAll("*")) if (el.shadowRoot) walk(el.shadowRoot);
    };
    walk(document);
    return out;
  });
  await page.screenshot({ path: path.join(OUT, "hover.png") });

  const out = { globals, elements, afterHover, errors: errors.slice(0, 20) };
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(out, null, 2));
  await browser.close();
  console.log(JSON.stringify(out, null, 2));
})().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
