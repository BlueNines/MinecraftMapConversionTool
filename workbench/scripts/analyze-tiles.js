// Are the lowres tiles actually empty? This does not involve a browser at all,
// so it cannot be blamed on the software renderer.
// Usage: node analyze-tiles.js <mapRoot>
const fs = require("fs");
const path = require("path");
const MODS = process.env.REPRO_MODULES || "C:/Users/28315/AppData/Local/Temp/repro/node_modules";
const { PNG } = require(MODS + "/pngjs");

const ROOT = process.argv[2] || "D:/fixB/web/maps/source/tiles";

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith(".png")) out.push(p);
  }
  return out;
}

function analyze(file) {
  const png = PNG.sync.read(fs.readFileSync(file));
  const n = png.width * png.height;
  let opaque = 0, colored = 0;
  for (let i = 0; i < n; i++) {
    const a = png.data[i * 4 + 3];
    if (a > 0) {
      opaque++;
      const r = png.data[i * 4], g = png.data[i * 4 + 1], b = png.data[i * 4 + 2];
      if (r || g || b) colored++;
    }
  }
  return { w: png.width, h: png.height, opaquePct: +((opaque / n) * 100).toFixed(1), coloredPct: +((colored / n) * 100).toFixed(1) };
}

for (const lod of ["1", "2", "3"]) {
  const dir = path.join(ROOT, lod);
  if (!fs.existsSync(dir)) { console.log(`lod ${lod}: MISSING`); continue; }
  const files = walk(dir);
  const rows = files.map((f) => ({ f: path.relative(ROOT, f), ...analyze(f) }));
  rows.sort((a, b) => b.coloredPct - a.coloredPct);
  const drawn = rows.filter((r) => r.coloredPct > 1).length;
  console.log(`\n=== lod ${lod}: ${files.length} tiles, ${drawn} with real content ===`);
  rows.slice(0, 5).forEach((r) => console.log(`  ${r.f}  ${r.w}x${r.h}  opaque=${r.opaquePct}%  colored=${r.coloredPct}%`));
  if (rows.length > 5) {
    const last = rows[rows.length - 1];
    console.log(`  ...  lowest: ${last.f} colored=${last.coloredPct}%`);
  }
}
