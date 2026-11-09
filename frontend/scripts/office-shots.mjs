// Visual check of the 3D Virtual Office: company view, each requested department, and one agent.
// Usage: node scripts/office-shots.mjs <outDir> [keys...]   e.g. node scripts/office-shots.mjs /tmp/out 4 2 7
import { chromium } from "playwright-core";
import fs from "node:fs";

const env = Object.fromEntries(
  fs.readFileSync(new URL("../../.env", import.meta.url), "utf8").split("\n")
    .filter((l) => l.includes("=") && !l.startsWith("#")).map((l) => [l.slice(0, l.indexOf("=")), l.slice(l.indexOf("=") + 1)]),
);
const [outDir, ...keys] = process.argv.slice(2);
const base = "http://localhost:5180";
const browser = await chromium.launch({ executablePath: "/usr/bin/google-chrome", headless: true, args: ["--use-angle=swiftshader", "--enable-unsafe-swiftshader"] });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: 1 });
const errors = [];
page.on("pageerror", (e) => errors.push(String(e)));
page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
await page.goto(base);
await page.fill('input[type="email"]', env.AIW_DEV_TEST_EMAIL);
await page.fill('input[type="password"]', env.AIW_DEV_TEST_PASSWORD);
await page.click('button[type="submit"]');
await page.waitForURL(/\/w\//, { timeout: 10000 });
const ws = page.url().match(/\/w\/([^/]+)/)[1];
const projects = await page.evaluate(async (w) => (await fetch(`/api/v1/workspaces/${w}/projects`)).json(), ws);
await page.goto(`${base}/w/${ws}/p/${projects[0].id}/office`);
await page.waitForTimeout(5000);
if (process.env.MEETING) {
  // Convene a real meeting (Lead + three agents) and watch Meeting Room A while it runs.
  const res = await page.evaluate(async (p) => {
    const agents = await (await fetch(`/api/v1/projects/${p}/agents`)).json();
    const lead = agents.find((a) => a.lead);
    const others = agents.filter((a) => !a.lead && a.status === "ACTIVE").slice(0, 4).map((a) => a.id);
    await fetch("/api/v1/auth/csrf");
    const xsrf = decodeURIComponent(document.cookie.split("; ").find((c) => c.startsWith("XSRF-TOKEN="))?.slice(11) ?? "");
    const r = await fetch(`/api/v1/projects/${p}/meetings`, { method: "POST", headers: { "Content-Type": "application/json", "X-XSRF-TOKEN": xsrf },
      body: JSON.stringify({ title: "Visual check · stand-up", agenda: "Check the office staging.", organizerId: lead.id, participantIds: [lead.id, ...others], rounds: 3 }) });
    return r.status;
  }, projects[0].id);
  console.log("convene", res);
  await page.selectOption("select", "roomA");
  for (let i = 0; i < 4; i++) {
    await page.waitForTimeout(i === 0 ? 2000 : 2500);
    await page.screenshot({ path: `${outDir}/meeting-${i}.png` });
  }
  await page.keyboard.press("Escape");
}
await page.screenshot({ path: `${outDir}/company.png` });
for (const k of keys) {
  await page.keyboard.press(k);
  await page.waitForTimeout(2500);
  await page.screenshot({ path: `${outDir}/room-${k}.png` });
}
if (keys.length) {
  // Open the first agent of the last room from the list view, then come back to the floor.
  await page.getByRole("radio", { name: "List" }).or(page.getByRole("button", { name: "List" })).first().click();
  await page.locator("tbody button").first().click();
  await page.getByRole("radio", { name: "Floor" }).or(page.getByRole("button", { name: "Floor" })).first().click();
  await page.waitForTimeout(5000);
  await page.screenshot({ path: `${outDir}/agent.png` });
}
console.log(errors.length ? errors.join("\n") : "no runtime errors");
await browser.close();
