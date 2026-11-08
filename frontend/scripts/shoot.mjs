// Visual check: logs in with the local dev test account and screenshots pages.
// Usage: node scripts/shoot.mjs <outDir> <path> [<path>...]   (paths may use {ws} and {p})
import { chromium } from "playwright-core";
import fs from "node:fs";

const env = Object.fromEntries(
  fs.readFileSync(new URL("../../.env", import.meta.url), "utf8").split("\n")
    .filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
const [outDir, ...paths] = process.argv.slice(2);
const base = "http://localhost:5180";
const browser = await chromium.launch({ executablePath: "/usr/bin/google-chrome", headless: true });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
await page.goto(base);
await page.fill('input[type="email"]', env.AIW_DEV_TEST_EMAIL);
await page.fill('input[type="password"]', env.AIW_DEV_TEST_PASSWORD);
await page.click('button[type="submit"]');
await page.waitForURL(/\/w\//, { timeout: 10000 });
const ws = page.url().match(/\/w\/([^/]+)/)[1];
const projects = await page.evaluate(async (w) => (await fetch(`/api/v1/workspaces/${w}/projects`)).json(), ws);
const p = projects[0]?.id ?? "none";
for (const path of paths) {
  const url = base + path.replace("{ws}", ws).replace("{p}", p);
  await page.goto(url);
  await page.waitForTimeout(1500);
  const name = path.replace(/[{}\/]/g, "_").replace(/^_+/, "") || "root";
  await page.screenshot({ path: `${outDir}/${name}.png` });
  console.log(`${outDir}/${name}.png`);
}
await browser.close();
