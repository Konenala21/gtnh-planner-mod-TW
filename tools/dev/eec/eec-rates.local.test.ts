/**
 * The website's crusher maths on the mod's mobs: each case's recipe (as the website's own pipeline built it from the
 * game's mob table, website-eec.mjs's output) run through its getOverclockedRecipeStats and getMachineOutputMultiplier
 * at the case's settings, for holding against the mod's card (`tools/dev/mc.sh call 'eec?mob=<key>&voltage&amp&set'`).
 * Run with the website's vitest through vitest.eec.config.mjs beside it (its header says how).
 *
 * EEC_RECIPES: website-eec.mjs's output. EEC_CASES: [{ mob, voltage, amps, tiers: { eecLooting: "3", ... } }].
 * EEC_RATES_OUT: where the answers go, one per case: tier, ticks a kill, EU/t, overclocks, each output's multiplier
 * and its rate a second on one machine.
 */
import fs from "node:fs";
import { it } from "vitest";
import { getOverclockedRecipeStats } from "@/lib/solver/overclock";
import { getMachineOutputMultiplier } from "@/lib/solver/machine-effects";
import type { Recipe } from "@/lib/model/types";

type Built = {
  mob: string;
  name: string;
  eut: number;
  minimumTier: string;
  durationTicks: number;
  outputs: { kind: string; id: string; amount: number; chance: number | null }[];
  eec: Record<string, unknown>;
};
type Case = { mob: string; voltage: string; amps: number; tiers: Record<string, string> };

it("writes the website's crusher numbers", () => {
  const built: Built[] = JSON.parse(fs.readFileSync(process.env.EEC_RECIPES!, "utf8"));
  const cases: Case[] = JSON.parse(fs.readFileSync(process.env.EEC_CASES!, "utf8"));
  const out = cases.map((c) => {
    const b = built.find((entry) => entry.mob === c.mob);
    if (!b) return { mob: c.mob, error: "no such mob" };
    const recipe = {
      id: `mod:${b.mob}`,
      name: b.name,
      kind: "gregtech_machine",
      category: "kubatech-eec",
      machineType: "Extreme Entity Crusher",
      minimumTier: b.minimumTier,
      durationTicks: b.durationTicks,
      eut: b.eut,
      inputs: [],
      outputs: b.outputs.map((o) => ({ kind: o.kind, id: o.id, amount: o.amount, ...(o.chance != null ? { chance: o.chance } : {}) })),
      metadata: { eec: b.eec },
      source: { recipeMap: "Extreme Entity Crusher" },
    } as unknown as Recipe;
    const node = {
      overclockTier: c.voltage,
      hatchVoltageTier: c.voltage,
      hatchAmps: c.amps,
      powerInputMode: "amps" as const,
      coilTier: undefined,
      machineHandlerId: undefined,
      machineConfigTiers: c.tiers,
    };
    const stats = getOverclockedRecipeStats(recipe, node as never);
    const multipliers = recipe.outputs.map((o) => getMachineOutputMultiplier(recipe, node as never, o, stats.tier));
    return {
      mob: c.mob,
      tier: stats.tier,
      ticksPerKill: stats.durationTicks,
      eut: stats.eut,
      overclocks: stats.overclockSteps,
      multipliers,
      perSecond: recipe.outputs.map(
        (o, i) => `${o.id} ${(o.amount * (o.chance ?? 1) * multipliers[i] * 20) / stats.durationTicks}/s`,
      ),
    };
  });
  fs.writeFileSync(process.env.EEC_RATES_OUT!, JSON.stringify(out, null, 1) + "\n");
});
