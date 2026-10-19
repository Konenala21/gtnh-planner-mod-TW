/**
 * Holds a plan the game wrote (`tools/dev/mc.sh call 'exportff?file=<path>'`) against the website's own project schema
 * (its factoryProjectSchema), as its library and plan links parse a posted plan. PLAN_FILE names the plan. Run with
 * vitest.eec.config.mjs beside it.
 */
import fs from "node:fs";
import { expect, it } from "vitest";
import { factoryProjectSchema } from "@/lib/model/schemas";

it("the plan fits the website's schema", () => {
  if (!process.env.PLAN_FILE) return;
  const plan = JSON.parse(fs.readFileSync(process.env.PLAN_FILE, "utf8"));
  const parsed = factoryProjectSchema.safeParse(plan);
  if (!parsed.success) console.log(JSON.stringify(parsed.error.issues, null, 1));
  expect(parsed.success).toBe(true);
});
