// Visual check of the Kanban drag-and-drop without changing data: a refused drop, the block dialog closed with Back,
// and the task edit form. Usage: W=2600 node scripts/kanban-check.mjs <outDir> <project name part>
// (a wide viewport keeps every column on screen, so drags land where intended).
import { chromium } from "playwright-core";
import fs from "node:fs";
const env = Object.fromEntries(fs.readFileSync(new URL("../../.env", import.meta.url), "utf8").split("\n")
  .filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]));
const [outDir, projectName] = process.argv.slice(2);
const base = "http://localhost:5180";
const browser = await chromium.launch({ executablePath: "/usr/bin/google-chrome", headless: true });
const page = await browser.newPage({ viewport: { width: Number(process.env.W ?? 1440), height: 900 } });
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
await page.goto(base);
await page.fill('input[type="email"]', env.AIW_DEV_TEST_EMAIL);
await page.fill('input[type="password"]', env.AIW_DEV_TEST_PASSWORD);
await page.click('button[type="submit"]');
await page.waitForURL(/\/w\//);
const found = await page.evaluate(async (name) => {
  for (const w of await (await fetch("/api/v1/workspaces")).json()) {
    const p = (await (await fetch(`/api/v1/workspaces/${w.id}/projects`)).json()).find((x) => x.name.toLowerCase().includes(name));
    if (p) return { ws: w.id, p };
  }
  return null;
}, projectName.toLowerCase());
if (!found) throw new Error("This account cannot see a project named " + projectName);
const { ws, p } = found;
await page.goto(`${base}/w/${ws}/p/${p.id}/tasks`);
await page.waitForTimeout(2000);
await page.screenshot({ path: `${outDir}/kanban.png` });
const card = page.locator("section:has(h3:text-is('Assigned')) button[draggable=true]").last();
const notice = async () => (await page.locator("[role=status]").count()) ? await page.locator("[role=status]").innerText() : "(no notice)";
await card.dragTo(page.locator("section:has(h3:text-is('In progress'))"), { targetPosition: { x: 20, y: 60 } });
await page.waitForTimeout(300);
console.log("drop on In progress:", await notice());
await page.getByRole("button", { name: "Dismiss" }).click(); // the notice shifts the board; clear it before the next drag
await page.waitForTimeout(200);
await card.dragTo(page.locator("section:has(h3:text-is('Blocked'))"));
await page.waitForTimeout(300);
await page.screenshot({ path: `${outDir}/kanban-block-dialog.png` });
console.log("drop on Blocked opens:", await page.locator("[role=dialog]").getAttribute("aria-label").catch(() => "(no dialog)"));
await page.locator("[role=dialog]").getByRole("button", { name: "Back" }).click();
console.log("after Back, dialogs:", await page.locator("[role=dialog]").count());
console.log(errors.length ? errors.join("\n") : "no runtime errors");
await browser.close();
