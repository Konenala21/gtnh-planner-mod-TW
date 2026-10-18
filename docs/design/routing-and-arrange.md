# Wire routing and Arrange

The overhaul of how the board draws its wires and lays itself out. The aim, in the owner's words: edges and Arrange
"excellent", wires that read cleanly (no wiggles, hooks or splayed ends, no needless length; diagonals are fine),
moving one thing re-routes instantly, and arranging a whole plan may take seconds behind a spinner.

Both are on the board since 2026-10-08: routing in `layout/WireRouter`, Arrange in `layout/arrange/` (a port of the
website's arrange, judged by this router).

## The yardstick

`layout/RouteMetrics` measures routed wires: crossings, overlap, bends (45, 90, sharp), length, excess over the
shortest a wire could be, backtrack (length run away from where a wire is going), jogs (sidesteps under three cells),
wrong sides (ends on a side facing away from the other box), runs through boxes, shared ends. `points()` folds them
into one number in pixels of wire, lower better: Factory Flow's route points (length; bends 35 and 80; a crossing
400) plus what those miss.

The benchmark routes every plan in the corpus where the website left it ("placed") and again after Arrange
("arranged"), measures it, and draws it to `build/bench/<label>/` with a summary table. The corpus is the importer's
fixtures, the plans a dev run kept from the Library, the website's examples and router corpus
(`../gtnh-factory-flow`), and the gtnh-flow charts (arranged only). All of it is tagged `bench` and left out of the
normal test run.

```bash
./gradlew test -Pbench=<label> --tests '*LayoutBenchmark*'            # whole corpus
./gradlew test -Pbench=<label> -PbenchOnly='Farm|Oil' --tests '*LayoutBenchmark*'
./gradlew test -Pbench=drag --tests '*DragBench*'                     # incremental re-routing while dragging
./gradlew test -Pbench=trace -PprofileBoard='Farm Power' -Pcrop='49,50' --tests '*RouterTrace*'
./gradlew test -Pbench=profile -PprofileBoard='Farm Power' -Pjfr --tests '*RouterProfile*'
```

A board straight from the game joins the corpus as `game/<name>`: with the dev game open on it,
`tools/dev/mc.sh call board > build/bench/boards/<name>.json` (the dump has every card's and drawer's world rect and
the boxes each wire joins, so it routes exactly as the board did). That is how to look into a spot someone points at
in a screenshot: `-PbenchOnly='game/<name>'`, and `RouterTrace` with `-PprofileBoard='game/<name>' -Pcrop=...` for a close-up.
`-Pstages` prints how long each step of Arrange takes. From a worktree, which has no website checkout beside it,
`-PbenchFf=C:/Users/jack/gtnh-factory-flow` points the corpus at one (copy `run/client/library-downloads` and
`build/bench/boards` over too, for the whole corpus).

`-Prouter.turn45=… -Prouter.turn90=… -Prouter.cross=… -Prouter.nearCost=… -Prouter.run=… -Prouter.rounds=…
-Prouter.near=… -Prouter.wide=…` override the router's prices for an experiment. `RouterTrace` prints the costliest
searches (and what each route paid for: steps, turns, junctions, corner crossings) and draws close-ups round the
boxes named in `-Pcrop` (box indexes).

## The router

`WireRouter` routes on a 10 px grid in eight directions. How it reads a board is in its class comment; the parts
that matter most:

- **Where wires meet boxes is planned first**, as on the website: every end's ideal place faces the other box, a
  box's ends are matched to places round its edge in that order (a monotone matching), two cells apart where there
  is room, so wires fan out without crossing at the box and two wires between the same boxes run side by side.
- **Exact A\* over (cell, heading)**, on a window round the wire's ends. The board's costs in the window are copied
  out for that wire into one small table (four numbers a cell: entering it along each of the four lines), with a
  shut border so the search needs no bounds checks. A Dijkstra out from the arrivals works out the least cost from
  every window cell to an arrival, crossings, boxes and rings included; the search's estimate is that plus the
  turning any way on must still do (the angle between the heading and the arrival's, or a turn off and back when in
  line but offset). With that estimate an exact search is as quick as the weighted one was, and a weighted search
  settles for dearer, which on a crowded board means wigglier, routes.
- **Prices**, in px of detour a wire will take to avoid each: a 45-degree bend 100, a right angle 200, a crossing
  200, running in the cell next to another wire 3 per cell, running on another wire's line 60 per cell. After any
  turn a wire runs on at least 3 cells (4 on a diagonal). Factory Flow's own prices (35, 80, 400) had long wires
  weaving round crossings in little V's; these were set against the benchmark (table below).
- **Incremental**: the router keeps the board and every route between calls. `reroute` tells boxes and wires apart
  by key and routes again only the wires at a box that moved (or is new), new wires, and wires running where a box
  now is or just was; the rest keep their routes exactly. A drag routes roughly (no widening, no rip-up) and the
  drop thoroughly; the grid keeps 400 px of slack round the board and grows (routes moved onto it) when a box is
  dragged past that. When most of the board changed it routes afresh.
- **On the board** (`ui/canvas/WireLayer`): one router per board, used only on the router's thread. The frame waits
  for a route expected back within 10 ms, so small and middling boards never show a wire out of place; on a big
  board a wire whose box moved stretches to follow it until its route comes back.

### Steering dots (the website's waypoints, 2026-10-18)

A player can pin dots on a wire that its route must pass through, in order: the website's edge `waypoints`
(website 1.10.0, `ResourceEdge` in its `FactoryFlow.tsx` and `grid-edge-router.ts`).

- **Kept** on the wire: `Edge.waypoints`, and for a drawer's wire on its link (`Drawer.Link.waypoints()`, not part of
  what the link is: two links to one port are equal however steered). Points are board px on the router's lines, the
  middles of its 10 px cells (`Waypoints.onLine`); saved only when there are some (`[[x, y], ...]`), undoable, copied
  with the wire (shifted by the paste), gone with it.
- **Routed** in legs, as on the website (`WireRouter.steered`): source to the first dot's cell, dot to dot, the last
  dot to the target. A leg arrives at a dot on any heading and the next leaves on that heading, so passing straight
  through is free and turning there is a turn; reversing needs two right angles, so an out-and-back reads as two lines
  three cells apart. The legs already found are on the board while the next is searched, so a wire sent back the way
  it came runs beside itself, not on top. A wire's ends are planned facing its nearest dot instead of the far box (the
  website's `exitParam` toward `stops[0]`). A dot in a box, or a leg that finds no way, and the wire routes as if it had
  no dots (the website's "try again without"). The grid grows to take a dot past it; `reroute` routes again a wire
  whose dots changed, and only it. A wire without dots routes exactly as before: across the whole corpus (76 rows,
  placed and arranged) every measure is the same and all 70 drawings are the same pixel for pixel below their
  header, which prints the route time (`build/bench/wp-before` against `wp-after`).
- **On the board** (`BoardCanvas`, `WireLayer`): a double-click on a wire pins a dot on the cell of the wire nearest
  the click, put among its dots in the order they lie along it (the website's insertion by arc position). A press on
  a dot past `DRAG_SLOP` carries it: it moves cell to cell, kept off cards and drawers and the ring of cells touching
  them (the website's `clampWaypointToClearSpace`), and the wire routes through it as it goes (roughly while carried,
  properly once let go, one undoable step). A second press on a dot within 400 ms takes it off. Dots draw over the
  wires, a touch wider than the wire in its colour in a dark ring, lit under the mouse; a picture of the plan leaves
  them out. The website freezes every wire while a dot is dragged; ours routes only the steered wire, quickly enough to
  follow.
- **Arrange** clears them, as the website's: a dot aimed at a layout that is gone. A wire wholly inside a group, which
  Arrange moves as one block, keeps its dots moved with it (the website's locked boards). The website's arrange also
  pins dots of its own walking a bridge round a third island's drawn ground; ours draws no island grounds, so it has
  none. Opening or shutting a group takes the dots off the wires at what it holds, as the website's pockets do.
- **Both ways**: the importer reads them (`FfPlanParser`, `FfConverter.stops`: moved as the cards were, onto the
  router's lines), the posting export writes them (`PlanExport.edge`) and shared plans carry them (`cloud/map`: the
  site's on its 20 px grid, `Waypoints.toSite`, and the game's own to the pixel in the wire's game data, read back while
  the website left the wire alone).

### Where it stands (2026-10-08, `build/bench/router-v1`)

Totals over the corpus; "placed" is 1024 wires on the website's own placements, "arranged" 1857 wires after the
current Arrange.

| | points | crossings | bends | jogs | backtrack | wrong sides | route time |
|---|---|---|---|---|---|---|---|
| old router, placed | 1,535,995 | 1052 | 2787 | 49 | 60,146 | 292 | 4.1 s |
| new router, placed | 884,551 | 563 | 1202 | 0 | 6,362 | 35 | 2.6 s |
| old router, arranged | 15,757,612 | 5679 | 5034 | 150 | 133,072 | 463 | 15.4 s |
| new router, arranged | 4,399,780 | 3973 | 3120 | 0 | 27,933 | 63 | 8.0 s |

How the prices were found (placed totals; every row exact search, minimum run 3 unless said):

| variant | points | crossings | bends | jogs |
|---|---|---|---|---|
| weighted search (1.2 near, 1.5 wide), FF prices | 1,125,160 | 581 | 3947 | 155 |
| exact search, FF prices, minimum run 1 | 967,486 | 555 | 2251 | 64 |
| minimum run 3 | 958,406 | 537 | 2179 | 0 |
| next-to-a-wire 3 px a cell (was 10) | 952,252 | 539 | 2111 | 0 |
| bends 50 / 100 | 942,220 | 541 | 1940 | 0 |
| crossing 300 | 926,631 | 545 | 1869 | 0 |
| bends 70 / 140, crossing 250 | 890,466 | 532 | 1438 | 0 |
| bends 100 / 200, crossing 200 (chosen) | 882,333 | 556 | 1205 | 0 |
| bends 150 / 300, crossing 250 | 884,992 | 558 | 1149 | 0 |
| chosen, one rip-up round instead of four | 884,551 | 563 | 1202 | 0 |

Dragging (`DragBench`, the busiest card and a quiet one, 40 steps then the drop): Platline, LUV Superconductor,
HV Oil and Untitled design re-route a step in 0.3 to 7.5 ms, FULL PLATLINE in 3 to 4 ms (worst 18), Farm Power (292
wires) in 13 to 45 ms (worst 89). After the drop the board measures the same as routing it afresh.

Still to do on routing: lanes (parallel wires spread by their widths in shared corridors, as the website packs them),
and Farm Power-sized boards (a full route there is about 2 s; most of it is long wires searched across wide windows).

## Arrange

`layout/arrange/` is a port of the website's arrange (its `board-arrange*.ts`, `route-judge.ts` and the router
tuning's arrange dials), judged by our router instead of the website's:

- **The column pass** (`ColumnArrange`, board-arrange.ts): satellites (a drawer whose every wire meets one machine
  rides its side at the port it serves), islands (connected parts laid out apart, placed by the same engine as
  meta-cards), cycles broken (two-card loops stacked, DFS back edges, co-feeders turned), longest-path layers slid
  toward their wires, big recycle rings folded back over the top, shared storages between their partners, bands
  along a spanning-tree trunk, rows settled by isotonic regression, sweeps and neighbour swaps to uncross, rows
  straightened onto their heaviest wire, column gaps grown by the wires they carry; unwired cards on a shelf.
- **The challenger** (`Optimize`, board-arrange-optimize.ts): the column pass, then simulated annealing over swaps,
  column hops, moves to a partner's side and nudges, scored by a proxy of the routes (`Proxy`) plus sprawl and the
  air owed between strangers (`Air`); the router judges the best few.
- **The free placement** (`FreeArrange`, board-arrange-free.ts): stress layout, then legalised onto the grid and
  annealed with drawers placed by pattern (one partner: beside it; two: between them).
- **Choosing** (`Arrange`): the three candidates are routed by `RouterJudge` (our router and `RouteMetrics`' points,
  plus the air), the best two are polished (cards on crossing wires tried beside their partners or swapped in their
  column, a move kept when the routed board scores better; quick verdicts re-route only what a move touched), and the
  better polished board wins. The challenger and the free placement search at once, and so do the two polishes.
- **On the board** (`BoardCanvas.arrange`): it runs on its own thread, the Arrange key counts up and a second press
  stops it; when it is done the cards glide to their places, one undoable step. The old ELK Arrange
  (`BoardArrange`, `AutoLayout`) and the ELK dependency are gone.

Against the old Arrange and the plans' own placements (the website's, or the owner's), routed by the new router
(`build/bench/arr3`; points and crossings, lower better):

| board | placed | old Arrange | new Arrange | new Arrange time |
|---|---|---|---|---|
| Titanium Line Chembath | 7,098 | 10,717 | 4,236 / 0 crossings | 1.3 s |
| Titanium Line (tour) | 2,049 | 4,311 | 767 / 0 | 0.2 s |
| Sulfuric Acid | 11,484 | 11,115 | 3,764 / 0 | 1.1 s |
| HV Oil | 34,677 | 28,858 | 14,978 / 9 | 3.7 s |
| Platline | 16,934 | 32,959 | 13,894 / 2 | 4.3 s |
| the owner's plan (58 boxes, 79 wires) | 36,914 / 13 crossings | 136,637 / 144 | 24,295 / 5 | 4.6 s |
| FULL PLATLINE | 118,900 | 80,653 | 19,730 / 1 | 13 s (before the last speed-ups) |
| Farm Power (206 boxes, 292 wires) | 496,992 | 535,965 | 201,483 / 208 | 26 s |

The free placement wins most boards, the challenger the rest; the plain column pass rarely. Still to do: speed on
big boards (Farm Power takes 26 s, half of it the free placement's 300,000 trials), and the spacing tuned to our cards (320 wide to the
website's 380) and our router's rings.
