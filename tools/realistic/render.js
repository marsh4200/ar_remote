// Renders every icon in icons.svg.html to a transparent PNG for the app:
//   android/app/src/main/res/drawable-nodpi/real_<name>.png  (256 × 256)
// Run from the repo root:  node tools/realistic/render.js   (needs Playwright + Chromium)
// Then: python3 tools/make_icon_library.py  (adds them to the Composer's icon list)
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const SIZE = 256;
const root = path.resolve(__dirname, '..', '..');
const out = path.join(root, 'android/app/src/main/res/drawable-nodpi');

(async () => {
  fs.mkdirSync(out, { recursive: true });
  for (const f of fs.readdirSync(out)) if (/^real_.*\.png$/.test(f)) fs.unlinkSync(path.join(out, f));
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1400, height: 1000 }, deviceScaleFactor: SIZE / 120 });
  await page.goto('file://' + path.join(__dirname, 'icons.svg.html'));
  // Transparent page: only the icon and its shadow end up in the PNG.
  await page.addStyleTag({ content: 'body{background:transparent!important}' });
  const figures = await page.$$('figure[data-name]');
  for (const fig of figures) {
    const name = await fig.getAttribute('data-name');
    const svg = await fig.$('svg');
    await svg.screenshot({ path: path.join(out, `real_${name}.png`), omitBackground: true });
  }
  await browser.close();
  console.log(`${figures.length} realistic icons rendered to ${path.relative(root, out)}`);
})();
