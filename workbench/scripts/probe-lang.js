// Force the viewer into Chinese, then dump the text it actually shows.
// The menu is DOM (not the WebGL canvas), so this is readable even though the
// scene itself renders black under the headless software renderer.
// Usage: node probe-lang.js <viewerUrl> <lang>
const fs = require("fs");
const path = require("path");
const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const puppeteer = require(MODS + "/puppeteer-core");

const EDGE = "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe";
const URL = process.argv[2] || "http://127.0.0.1:8199/";
const LANG = process.argv[3] || "zh-CN";
const OUT = process.env.SHOTS || path.join(process.env.TEMP, "repro", "lang");
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: EDGE,
    headless: "new",
    args: ["--no-sandbox", "--disable-dev-shm-usage", "--window-size=1400,900",
           "--lang=" + LANG, "--enable-unsafe-swiftshader", "--use-gl=angle", "--use-angle=swiftshader"]
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1400, height: 900 });
  await page.evaluateOnNewDocument((lang) => {
    Object.defineProperty(navigator, "language", { get: () => lang });
    Object.defineProperty(navigator, "languages", { get: () => [lang] });
  }, LANG);

  await page.goto(URL, { waitUntil: "networkidle2", timeout: 90000 });
  await sleep(4500);

  // i18n reachable? Then missing keys can be filled at runtime.
  const i18n = await page.evaluate(() => {
    const app = window.bluemap;
    const out = { hasBluemap: !!app };
    if (!app) return out;
    const i = app.i18n || (app.appState && app.appState.i18n);
    out.hasI18n = !!i;
    if (i) {
      out.keys = Object.keys(i).slice(0, 40);
      try {
        out.languages = i.languages ? Object.keys(i.languages) : null;
        out.current = i.currentLanguage || i.language || null;
        // What do the two keys missing from zh-CN actually resolve to?
        if (typeof i.$t === "function") {
          out.probe = {
            chunkBorders: i.$t("chunkBorders.button"),
            clipboard: i.$t("blockTooltip.clipboard"),
            menu: i.$t("menu.title")
          };
        }
      } catch (e) { out.i18nError = String(e.message); }
    }
    return out;
  });

  // Reveal the menu, then open every entry and collect the text shown.
  await page.mouse.move(700, 450); await sleep(250);
  await page.mouse.move(40, 30); await sleep(1200);

  const clickByTitle = (re) => page.evaluate((src) => {
    const rx = new RegExp(src, "i");
    const all = Array.from(document.querySelectorAll("*"));
    const hit = all.filter((el) => rx.test(el.getAttribute("title") || "") || rx.test(el.getAttribute("aria-label") || ""));
    if (!hit.length) return null;
    const el = hit[0];
    const label = el.getAttribute("title") || el.getAttribute("aria-label") || "";
    el.click();
    return label;
  }, re.source);

  const visited = [];
  const menu = await clickByTitle(/菜单|menu/i);
  await sleep(1200);
  visited.push({ opened: menu, text: await page.evaluate(() => document.body.innerText) });

  // Each menu row opens a panel; collect them one at a time.
  for (let i = 0; i < 10; i++) {
    const opened = await page.evaluate((idx) => {
      const rows = Array.from(document.querySelectorAll("[title], .menu-button, .menuButton, button, [role=button]"));
      const el = rows[idx];
      if (!el) return null;
      const label = el.getAttribute("title") || el.textContent.trim().slice(0, 30);
      el.click();
      return label;
    }, i);
    if (!opened) break;
    await sleep(700);
    const text = await page.evaluate(() => document.body.innerText);
    visited.push({ opened, text });
    await page.keyboard.press("Escape");
    await sleep(400);
    await page.mouse.move(40, 30); await sleep(300);
  }

  const out = { url: URL, lang: LANG, i18n, visited };
  fs.writeFileSync(path.join(OUT, "report.json"), JSON.stringify(out, null, 2));
  await browser.close();

  console.log(JSON.stringify(i18n, null, 2));
  const seen = new Set();
  for (const v of visited) {
    for (const line of String(v.text || "").split("\n")) {
      const s = line.trim();
      if (s && !seen.has(s)) { seen.add(s); }
    }
  }
  console.log("--- all visible UI strings ---");
  console.log([...seen].join("\n"));
})().catch((e) => { console.error("PROBE FAILED: " + ((e && e.stack) || e)); process.exit(1); });
