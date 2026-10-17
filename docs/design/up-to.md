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

## The solver contract

- `SolveInput.PoolIn.upTo`, read only when `exact` is false. The 2- and 3-argument constructors mean a plain cap.
  `capacity` is a double: a website plan's count may be fractional; `SolveInputJson` writes a whole one as before.
- `SolutionView.upTo()`: one `UpToReadout` per Up to pool, by its first machine; `UpToReason` is FULL, BOTTLENECK,
  STARVED, CLOGGED, UNUSED or LIMITED. Empty when the plan has no Up to pool.
- `DrawerReadout.banked`: per BUFFER of rule ANY, what it banks; null without Up to pools or such a buffer.
- `SolverMessage.PIN_UP_TO`: what holds a drawer back when its Up to machines cannot do more (`LIMIT_PIN`, the card
  among the shortfall's `limitingNodes`).

## The solver

The website's engine changes (`src/lib/solver/plan/` on `up-to-card`: `guard.ts`, `push.ts`, `readout.ts` and the rest)
ported to `balancer/`, name for name where the Java already mirrored it:

- `SolveInput.of` (AUTO only; the older modes read a set count as a pin, as before): a card's set count is Up to unless
  `PinRule` says Exactly (a custom rate card always is); an Up to count is a one-machine pool `PoolIn([id], count,
  false, true)` with no fixed count, Exactly the fixed count it always was. A set shared machine is a pool, `exact` for
  Exactly and `upTo` otherwise; a capacity nobody set stays the plain cap. Fluid tanks never have a count. Pools are
  sorted by first machine, as the website sorts them. A recipe on a shared machine keeps a count of its own if it has
  one (the website's sections never have one).
- `SolveContext`: `upToPools`, `drawnPorts`, `bankPorts`, the guard (`guard`, `guardReady`, `guardOff`) and
  `committedTrades`; an Up to pool anchors; what a buffer banks opens no gate. `commit` and `validate` measure dust
  against at least 1, so an all-but-zero point is noise, never a negative.
- `UpTo`: the guard (`prepareGuard`, run by the `GuardStage` prelude, applied by `ModelBuilder.guard()` in every AUTO
  model and the drawer relaxation, left out by `diagnosePins`) and the push (stage A, the max-min rounds with their
  certificates and probes, `bandAt`). The bands ride on `Solver.Trades.floors` as rows (`Solver.Terms`), holding the
  same values the website holds. `PushStage` runs between `TradeStage` and `FlowMinStage`, then
  `TradeStage.settleDrawers`; `FastPathStage` with Up to pools closes only the forced crossings and pushes;
  `Enumerator.evaluateSupportWith` pushes every support it settles, `applyChoice` keeps what the chosen point was
  settled under, and the swaps skip a gate whose every port is a bank or guarded shut.
- `UpToRead`: the read-out with the website's budget and reasons (`Solver.feedPort` gives a probe its free ends), merged
  across islands by node in `Balancer`. `SolutionView.unneeded` leaves Up to machines out. `Unanchored` already counted
  every set count, Up to or Exactly.

## Checking it against the website

- `UpToTest` is the website's `upto.test.ts` case for case, on the same plans (`UpToPlans`, ids hashed as the website's
  export hashes them). `SolverPathTest` (reversed variables) and `SolverIslandTest` take Up to plans too.
- `UpToParityTest` solves every plan under `src/test/resources/solver-corpus-upto/` and compares its whole answer with
  the website's by the golden rules; `upTo` and `banked` are in the digest only when present, so the goldens did not
  move. The website writes the folder: `UPTO_EXPORT_DIR=<this folder> npx vitest run up-to-export` on `up-to-card`
  (`parity/up-to-export.test.ts`: every hand-built Up to plan, and every corpus plan with each exact count Up to). Three
  plans answer alike only to the LP's tolerance (the website's committed point breaks one of its own rows by less than
  HiGHS's tolerance, near a millionth of a unit a second); the test names them with why and compares their read-outs
  and counts.
- gtnh-flow's `number` pins read Exactly (`GtnhFlowLoader`): those charts state demands, and the goldens stand.

## The board (the website's pass 3 card, `mockups/up-to-card/`)

- `ui/card/CardFace` is the pure view-model (the website's `clean-model.ts` count and foot, `card-tips.ts`'s words,
  `up-to-reason.ts`, `notices.ts`'s holders), tested in `CardFaceTest`; the board's card, the minimap and the world
  draw from it (`PlanSnapshot.Card.count`, `foot`).
- The card (`CardLayout`, `CardPaint`): every port a tile of one shape (`portTile`; a free end, open or forced, only
  changes colour: `freeTile`), power a tile among the inputs, the machine in its own sunk well (`well`, at least 120
  board px of rails, 80 px art) with the count under the picture: what runs big and white (an Up to card's read-out
  `reached`, else the solved count), under it the rule word (dotted, a click opens Any / Up to / Exactly,
  `RecipeCard.openRule`) and the set number in gold; Any says "(set count)" or nothing (a crop "crops planted"). A
  click on the count types it (`openPin`, Any becomes Up to, empty is Any), the wheel steps it (`CardFace.wheel`: from
  Any, Up to at what runs rounded up; below one, Any), the middle button is Any. A custom rate card is always Exactly
  (no menu); a fluid tank has no count. Rows and wire anchors did not move (`ROW` 42, tiles 40 tall).
- The bar along the foot (`CardLayout.footY`, `CardPaint.foot`, Part `FOOT` with its tip): an Up to card with a
  read-out (fill reached / capacity, the reason's word in its colour, one line shortened to fit, the fraction; a
  bottleneck runs on striped to `wants`), an Exactly card with an input forced in at one of its rows (FORCED IN, from
  the card's extra rows). A shared machine's bar reads every recipe's rows.
- Writes: `BoardSession.pin` keeps the rule (Any becomes Up to), `setRule` sets it at a count, `rule`/`setCount` read
  it (a shared machine's is its group's), all through `PinRule`. A set count never asks for one (`unsetParts` drops
  it), so an idle Up to card says UNUSED on its bar, not "(set count)".
- The overview's machine lines: "×4.5 of" (tinted by the reason when not full), the rule key ≤ for Up to and = for
  Exactly (a click steps Any, Up to, Exactly), the set count gold in the field; `MachineLine` carries `rule`, `set`,
  `runs` and the read-out.
- Notices: a drawer short of its rate says what it gets and what holds it back ("The Benzene drawer gets 1.2/s of 2/s.
  The Distillation Tower is full."), the limiting cards in its Show me.
- The tour pins the electrolyzer and the mixer Exactly (their rule word, then Exactly) before it shows forced flows;
  `starts.json` carries the electrolyzer's flag in every later start.
- `BoardSession.upTo(id)` reads the solver's read-outs by every machine of a pool; `call upto` puts stand-ins in their
  place (and drawer shortfalls) to look at before the solver fills them.

## Still to do

- The tour: a full run and `call 'tutorial?export=1'` now that pins solve as Up to (its forced-flow steps pin Exactly).
- The three parity plans that differ below the LP tolerance (listed in `UpToParityTest`): the website keeps an exact basis solution that leaves a row slightly broken (`refineWithBasis` in its `lp-engine.ts`).
