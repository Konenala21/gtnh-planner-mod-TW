# Up to: a machine count that is what you have

Written 2026-10-17. The mod's side of the website's Up to rule. The website's `docs/up-to.md` (branch `up-to-card`,
worktree `C:\Users\jack\ff-upto`) is the design both follow: the rule, how it reaches the engine, the guard, the push,
the read-out and the card. This file says where each part lives here, and what is done. The two solvers must give the
same answer, as for every feature (`cross-repo-features.md`).

## The rule

A card's machine count has one of three rules:

- **Any**: the plan picks how many (no count set).
- **Up to N**, the default for every set count: the machines run as hard as the rest of the plan lets them, never above
  N. No forced flow makes up what they cannot do; they slow down instead, as in the game.
- **Exactly N**: today's pin. N machines flat out, the rest solved around them, any gap shown as a forced flow.

A custom rate card's set count is always Exactly (its dial). A fluid tank never has one. Every count set before the rule,
in saves and in plans from the website, reads as Up to, old website Build plans included. There is no notice about it.

## The model

- `Node.machineCountFixed` still says a count is set; `Node.machineCountExact` says it is Exactly (`isMachineCountExact`
  is false without a count, and so for a fluid tank). Unsetting the count lets go of Exactly too, so a count set again
  is Up to, as on the website.
- `MachineGroup.pinned` says a shared machine's count is set; `MachineGroup.exact` says it is Exactly. An unpinned group
  with a capacity stays the plain cap it is today.
- `data/flowchart/PinRule` (the website's `pin-rule.ts`) reads and sets the rule for both: `PinRule.of(node)`,
  `PinRule.of(group)`, `PinRule.set(...)`, `next()` for the rule word's click, `label` for its words. The board and the
  solver input read the rule through it, never the flags.

## Saves and copies

- `Serializer` writes `machineCountExact: true` on an Exactly card only; a save without it is Up to. A machine group's
  `exact` is Gson's, and is null rather than false when not Exactly, so a save of a plan with no Exactly reads and writes
  byte for byte as before (undo snapshots and the cloud's game data included).
- Undo, plan codes, chat links and the tour's sandbox go through `Serializer`. Copy and paste (`BoardSession.ClipNode`),
  combining cards into a shared machine, duplicating one, and the tour's rebuild carry the flag.

## Plans both ways

- In (`importer/`): `FfPlanParser` reads `solvePinExact` (true only when it says so) onto `FfNode.solvePinExact`.
  `FfSettings.map` and `FfCards.power` set Exactly for a Solve plan's pin that says so; a Build plan's counts come over
  Up to. A shared card's pin is its first recipe's, and `FfCards.share` gives it to the group. Custom rate cards come
  over as before (Exactly by the rule), tanks unpinned.
- Out (`library/PlanExport`, also what `cloud/map/Fresh` writes): `solvePin` for a set count, `solvePinExact: true` only
  for Exactly. The cloud's field-by-field merge (`PlanWrite.merge`) adds and removes it with the rest. A game-made shared
  machine rides in the plan's game data (its group, `exact` with it); one the website made is read from its card.
- `dev/SolveInputJson` writes a pool's `upTo: true` only for an Up to pool, as the website's `__fixtures__/load.ts`
  reads it; older dumps read the same.

## The solver contract (no behaviour yet)

- `SolveInput.PoolIn.upTo`, read only when `exact` is false. The 2- and 3-argument constructors mean a plain cap.
- `SolutionView.upTo()`: one `UpToReadout` per Up to pool, by its first machine; `UpToReason` is FULL, BOTTLENECK,
  STARVED, CLOGGED, UNUSED or LIMITED. Empty until the solver fills it. Not in the corpus digest yet (it reads public
  fields and `spares()`), so the goldens do not move until the solver port adds it on both sides.
- `SolverMessage.PIN_UP_TO`: what holds a drawer back when its Up to machines cannot do more (`LIMIT_PIN`).

## Still to do

- The solver (`balancer/`): `SolveInput.of` turning an Up to card into a one-machine pool and an Up to group into an Up
  to pool (today every set count is still an exact pin, so the solve has not changed), `ModelBuilder` pools,
  `SolveContext` anchoring and the guard, the push between `TradeStage` and `FlowMinStage` and inside `FastPathStage`,
  the floors on the trades, `Enumerator.evaluateSupport`, `DrawerRelaxation` naming the card, `Unanchored`, the
  read-out and its island merge. The corpus is re-dumped once both sides agree.
- The board: the card's count and rule word, its bar and reason, the overview and the notices (the website's pass 3
  mockup, `mockups/up-to-card/`).
