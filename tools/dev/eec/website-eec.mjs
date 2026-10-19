#!/usr/bin/env node
/**
 * Runs the website's own dataset pipeline (tools/dataset-pipeline/scripts/normalize-oracle-export.mjs, as on its
 * origin/main) over a raw Extreme Entity Crusher export, and writes the crusher recipes it builds: what the mod's
 * machines/MobDrops is held against (MobDropsTest).
 *
 *   node tools/dev/eec/website-eec.mjs <raw.json> <out.json> [website checkout]
 *
 * <raw.json> is the exporter's shape: { domains: [ { id: "mobDrops", machine, infernal, mobs } ] }. The game writes
 * one with `tools/dev/mc.sh call 'eec?dump=<file>'` (PLANNH_GTNH=1 PLANNH_MOBS=1). The website checkout defaults to
 * ~/gtnh-factory-flow; its scripts are read from origin/main (the checkout may be behind) into
 * a temporary folder that borrows its node_modules.
 *
 * <out.json>: [ { mob, name, eut, minimumTier, durationTicks, notes, outputs: [{ kind, id, amount, chance }], eec } ]
 */
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

const [rawPath, outPath, checkoutArg] = process.argv.slice(2);
if (!rawPath || !outPath) {
  console.error("Usage: website-eec.mjs <raw.json> <out.json> [website checkout]");
  process.exit(2);
}
const checkout = path.resolve(checkoutArg ?? path.join(os.homedir(), "gtnh-factory-flow"));
const scriptsDir = "tools/dataset-pipeline/scripts";

const temp = fs.mkdtempSync(path.join(os.tmpdir(), "website-eec-"));
try {
  const listed = execFileSync("git", ["-C", checkout, "ls-tree", "--name-only", `origin/main`, `${scriptsDir}/`], {
    encoding: "utf8",
  })
    .split("\n")
    .filter((name) => name.endsWith(".mjs"));
  fs.mkdirSync(path.join(temp, scriptsDir), { recursive: true });
  for (const name of listed) {
    const body = execFileSync("git", ["-C", checkout, "show", `origin/main:${name}`], { maxBuffer: 64 << 20 });
    fs.writeFileSync(path.join(temp, name), body);
  }
  fs.symlinkSync(path.join(checkout, "node_modules"), path.join(temp, "node_modules"), "junction");
  const output = path.join(temp, "recipes.json");
  execFileSync(process.execPath, [path.join(temp, scriptsDir, "normalize-oracle-export.mjs"), path.resolve(rawPath), output], {
    env: { ...process.env, GTNH_DATASET_VERSION_ID: "mod-check", GTNH_DATASET_VERSION_LABEL: "mod-check" },
    stdio: ["ignore", "ignore", "inherit"],
  });
  const dataset = JSON.parse(fs.readFileSync(output, "utf8"));
  const recipes = (dataset.recipes ?? [])
    .filter((recipe) => recipe.category === "kubatech-eec")
    .map((recipe) => ({
      mob: recipe.metadata.eec.mob,
      name: recipe.name,
      eut: recipe.eut,
      minimumTier: recipe.minimumTier,
      durationTicks: recipe.durationTicks,
      notes: recipe.notes ?? null,
      outputs: recipe.outputs.map((output) => ({
        kind: output.kind,
        id: output.id,
        amount: output.amount,
        chance: output.chance ?? null,
      })),
      eec: recipe.metadata.eec,
    }));
  fs.writeFileSync(outPath, JSON.stringify(recipes, null, 1) + "\n");
  console.log(`${recipes.length} crusher recipes -> ${outPath}`);
} finally {
  // The borrowed node_modules link goes first, on its own, so nothing below could reach the checkout's own.
  const link = path.join(temp, "node_modules");
  if (fs.existsSync(link)) {
    try {
      fs.unlinkSync(link);
    } catch {
      fs.rmdirSync(link);
    }
  }
  fs.rmSync(temp, { recursive: true, force: true });
}
