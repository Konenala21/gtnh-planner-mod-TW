# Minimap, world links and the AR lens

The planner should be useful while you build, not only in its own screen. Five pieces, each usable on its own, in the
order they are built.

## 1. Settings, and names when zoomed out

- A gear key on the top bar, beside "?", opens the settings (`ui/popup/SettingsPanel`), stored in
  `config/gtnhplanner-settings.properties` (`ui/PlannerSettings`): a box in the middle of the screen, its sections
  down the left (Board, Sound, Minimap, In the world, Keys), each setting with the control that fits it (a switch, a
  row of choices, a slider, a little screen for the minimap's corner). The wheel over a control steps it.
- **Names when zoomed out**: past the glance zoom, every card's machine name and every drawer's resource name, small (a
  screen pixel per font pixel, two at large GUI scales) whatever the zoom, and no wider than what they name (a card's
  width, twice a drawer's; cut short with "..."). They are placed like map labels (`ui/canvas/ZoomedOutLabels`): drawn
  over everything in one pass, a card's above (or below) it, a source drawer's to its left, a product's to its right,
  each where it keeps clear of the other names (with room between two side by side) and of every card and drawer, and
  left out where there is no such place; cards first, then drawers by rate. Zooming in makes room for the rest. The
  minimap places them the same way.

## 2. The minimap

- While playing (no screen open, HUD shown), a corner of the screen shows the plan last open in the planner, as the
  board draws it zoomed out: cards (machine picture, the count in a dark pill), drawers (icon, the rate in a pill),
  wires as the board routed them, and with **Names when zoomed out** the same names, placed the same way.
- What the mouse or crosshair is on is highlighted in one colour everywhere (`Hyb.LIT`, a soft gold, drawn faint):
  the resource under the mouse on the board, the card under the crosshair on the minimap and over the world, and the
  wire under the crosshair over the world and on the minimap. The board's selection keeps its own colour.
- The board publishes a `PlanSnapshot` (cards, drawers, wire paths, view centre, links) whenever it rebuilds its models
  or routes; the minimap draws the latest one. A plan changed while the planner is closed (NEI's plan button) marks the
  map out of date until the planner opens again.
- Zoomed in from 35% (a step sooner than the board's glance zoom: the steps 0.35, 0.5, 0.71 and 1; `Minimap.WHOLE_FROM`), it draws whole cards and drawers as the board
  does (`PlanCardView.draw`, `PlanCardView.drawer`: no keys, menus or rules), without the zoomed-out names.
- Settings: on/off, size (Small, Medium, Large, Huge), shape (square, circle), corner, zoom, and "Centre on the machine
  you look at".
- See-through (the gear's Minimap, 0 to 80%): the whole map faded by it (`Hyb.fadeOut`), its items kept solid
  (`Hyb.keepItems`) where a faded card would drop them.
- Keys (Minecraft's Controls, category GTNH Planner): toggle the minimap, zoom in and out, pan (arrow keys), re-centre.
- Square maps clip with the scissor test; round ones mask their corners with the depth buffer.

## 3. Placing cards in the world

The plan and the world are separate: placing a card says where that part of the plan goes (or is). It never makes the
plan show what the machines are doing.

- A card goes on a spot: a block's place, usually an imaginary block in front of the face the crosshair is on (like
  placing a block, but nothing is placed and you can walk through it). Breaking or building blocks never moves or
  removes a placement.
- Every card has a place key (a map pin, between its menu key and gear; gold once placed). Left-click: the planner closes and the
  card's machine shows as a ghost on the spot in front of the face under the crosshair, its front toward you (a
  GregTech multiblock as its whole structure, see section 5, standing on that spot: the structure's bottom goes there,
  its controller as high above it as it is in the structure, and the whole structure is outlined); a click places it
  there, facing that way, and you stay in the world (`ui/world/LinkPicker`). The crosshair reaches 48 blocks while
  placing, past the game's own reach, to stand back from a big structure. Placed machines count as blocks for it
  (`LinkPicker.aim`): the next goes on top of or beside the last, as against a real block (a card's own spot is refused,
  "One is placed there already"). The ghost shows which way it faces, its front face lit gold with an arrow on the
  ground out of it (`WorldMarks.front`); a single block's model is turned to its facing (GregTech draws its machines
  facing west as items, so they are turned from there). While placing, R turns it a quarter and
  [ ] size a structure that comes in sizes; the title says its size in blocks ("3 x 4 x 3"). A card of several machines places one a click ("Place X: 2 of 3", up to 64): the
  first click replaces where the card was, each later one adds a spot, and right-click or Esc stops there, keeping the
  ones placed. Right-click or Esc before the first click cancels. Right-click on the key removes the card from the world.
  The card's menu also has **Show in the world** and **Remove from the world**.
- A card is placed on as many spots as it has machines and a spot holds one card: placing a card on a spot takes it
  from any other card, in any plan (`ui/world/WorldLinks`, `Node.worldLinks`: {dim, x, y, z, facing, size}, facing 0
  south, 1 west, 2 north, 3 east; size, when set, a structure's step; placements saved before facing face south).
- Adjusting a placed machine from the world (`ui/world/PlacementKeys`): hold sneak (Shift; whatever sneak is bound to)
  and the machine lit gold is in hand while sneak is held, outlined bright: R turns it a quarter about its middle, G
  picks it up to put down elsewhere (the picker, its facing and size kept; [ ] size it there), and Delete or Backspace
  takes it out of the world. Each is one undo step in its plan; a turn onto another machine's spot is refused. The
  machine lit is the one the plan over the world lights, with all its margins: its card under the crosshair, or its
  ghost (`PlanOverlay.highlighted`); with the overlay off, none. Moving is picking up and putting
  down only, so the minimap keeps its arrows and [ ]. Sneak rather than a binding of ours: in 1.7.10 a key drives one
  binding, so one on Shift would take it from sneaking; held is its binding pressed or its key down.
- The keys are listed in a narrow see-through strip at the right under the minimap, touching it (above it in a
  bottom corner, in its corner when it is off), while the plan is over the world, one word each: Shift + R Rotate,
  Shift + G Pick up, Shift + Del Remove, Shift + U Focus, Shift + Y Hide. The first three are dim while no machine is
  lit; Focus is gold while it is on. The last thing done ("Removed ...", "Focus: on") is its last line for four
  seconds, in place of the note at the top of the screen. The strip and the minimap share one backing, a little
  see-through (`Minimap.BACKING`); the minimap's frame is drawn as rings beside its fill, never over it, so the
  see-through stays even.
- Shift + Y (or Y alone: the overlay's key in Controls) hides and shows the whole plan over the world, not the
  minimap: its ghosts, cards, wires and the keys strip. Hidden, nothing of it shows or pops up, and Rotate, Pick up and
  Remove do nothing (nothing is lit to act on); the note "Plan over the world: off" shows at the top.
- While a machine is being placed or moved, the plan over the world stays: its card and wires go where it would be put
  down, following the crosshair as a card being dragged on the board, and the rest stay where they are; where it was
  is empty while it is in hand (all of a card's spots for its first placing, the one picked up for a move).
- From the world: the Link key (L) opens the planner in link mode (`ui/world/LinkTarget`) for the placed spot under
  the crosshair, or else the spot in front of the face looked at; clicking a card places it there alone, facing you (a
  multiblock standing on an empty spot, as above), clicking the card already there removes it, Esc cancels.
- Placements are saved with the plan, never copied with it, and undo like any edit, but are not changes to the plan:
  nothing re-solves and the minimap stays current.

## 4. Seeing placements in the world

- **Show in the world**: the card's block (a multiblock's whole structure) is outlined through walls, with a beam and its name and distance, for 30
  seconds (`ui/world/WorldView`, drawing in `WorldMarks`).
- With the overlay off, looking at a placed spot or structure (up to 64 blocks away, through blocks) outlines it, names
  its card and plan under the crosshair, and centres the minimap on that card (when set). With the overlay on, the
  overlay outlines it instead. A structure is known for cards of the plan last open; others are found by their block.

## 5. The plan over the world (AR)

`ui/world/PlanOverlay`, toggled with Y and in the settings ("Show the plan over the world (AR)", "How far to show it").
What shows is set in the gear's In the world, each also on a key held with sneak (Minecraft's Controls, GTNH Planner:
every one can be rebound; `PlannerKeys` reads them by key code, so a key shared with another binding, NEI's R recipe
key say, works for both; GTNH runs every binding on a pressed key anyway):

- **Cards** (Focus, Shift+U, on by default): the card of the machine you look at (its ghost under the
  crosshair, through blocks, or the card itself) pops up above it, growing from 60% of its size as it fades in
  (`PlanOverlay.attention`); no other card shows, and none while placing. Off, no cards show at all.
- **Labels** (Shift+I, on by default): each machine's label in its ghost on the screen (`PlanOverlay.label`): its name
  (two lines when long), its tier chip, the EU/t it draws (or makes, green) and its card's machine count. It always fits
  inside the ghost: as the ghost gets small the name goes first, then the power, then the count, and with not even its
  tier fitting at a letter pixel a screen pixel there is none.
- **Wire labels** (Always by default, Where you look, Off): each wire's tag, what it carries and how much: on every
  wire, only on the wires of the machine looked at (and the wire under the crosshair), or none.
- **Machines**: See-through (the ghosts below), Solid (opaque blocks, hidden behind real blocks as real ones are), or
  Hidden (wires, labels and cards only). The machine being placed always shows as a ghost.
- **Keys list**: the keys strip under the minimap, or not.

With Focus, Labels and wire labels off, only the machines and the wires show. Wires show at 60%, more for the card
looked at. The gear's In the world section gives each its key and one plain line on what it does (the owner, 2026-10-12: one
short sentence, no commas, no intro); every key
that switches something has its setting there, and the Keys section opens Controls. Only the machine looked at is outlined.
Cards are faded whole through `Hyb.fadeOut`, which every colour, text and picture `Hyb`
draws is multiplied by; items and fluids, which the game draws solid whatever the blending, drop out once a card is a
third faded.
Nothing shows until a card of the plan last open is placed.

- Each placed spot shows a ghost of the card's machine, see-through and behind real blocks. A GregTech multiblock shows
  its whole structure (`ui/gt/StructureGhosts`): built from its definition in BlockRenderer6343's fake world, as the
  card's picture is, once per machine and kept, then drawn block by block each frame against that world (so casings
  join and only outside faces draw), turned to the spot's facing. It is built as its card needs it, through GregTech's
  own structure channels as the Hologram Projector sets them (`StructureGhosts.Needs`, from the card's recipes): the
  card's coil on any coil machine, a distillation tower's height (its base and a layer for each fluid output, 3 to 12;
  the GT++ advanced tower too), an assembly line's length (a slice for each item input, 5 to 16; the advanced one too).
  The mega distillation tower's layers hold its outputs by a rule of their own, so it starts at its smallest; [ ] sets
  any of them. Each rule was read from the machine's own `construct`. It is drawn in two passes: every structure's nearest
  faces into the depth buffer first, then its colour where that depth is its own, so a structure shows as one solid
  see-through shape, never its insides through its walls, and a ghost in front hides the one behind (the depth stays,
  as a real block's would). Transparent texels are left out of both passes: GregTech draws a controller's front as
  mostly transparent overlays over the casing, and the constant alpha would paint their hidden colour. A block of the
  structure already standing in the world, the same block (and meta, but a machine's), is left out, so a structure
  half built shows what is left to build. Anything else shows its own block model, not when the machine is built there
  already. Display lists do not work here under Angelica (they come out invisible), hence drawing each frame.
- The card (Focus) stands just above its machine, never on it, so the spot or structure stays in view, drawn as the board
  draws it (`ui/card/CleanCardView`), with nothing to press, a line from it to the middle of the machine's top. A card
  placed on several spots stands over the one the crosshair meets, else its first; its wires run from the first; the
  others show the ghost alone. A card is drawn at its size within ten blocks of you and shrinks with distance beyond
  (always the whole card).
- Cards move out of each other's way as map labels do (`PlanOverlay.layout`): the one looked at first, then nearest
  first, each stays at home (just above its machine) while that is clear, else takes the clear place nearest it (aside,
  up, or down past its machine, which costs most), with a line from its nearest edge to its machine, ending in a small
  pin. Clear is on the screen and off the cards already placed, the shown wire tags, its own machine and the machine
  being placed or looked at. A
  card goes home again once that is clear by a good margin, and moves in a quick ease, never a glide; where it was is
  kept, so turning away and back finds the same arrangement.
- The crosshair is on a card when it is over the card itself or meets its spot or structure (its ghost), through
  blocks: the card is highlighted, drawn over the others, its spot or whole structure outlined, and the minimap highlights it and glides to it.
- The plan's wires between placed cards are drawn flat on the screen as the board draws them, between the block of
  the one nearest the other and the block of the other nearest that (a structure's own blocks, so a wire leaves from
  the side toward where it goes and meets the near side of where it arrives), from where it leaves the one block to
  where it meets the other (cut short at the camera): a thick line in the resource's colour on a dark
  edge, with arrowheads drifting slowly towards the card fed, lighter than the wire (set out in the world, four fifths of a block apart at two
  thirds of a block a second, so their pace holds as you move; all the way from end to end, fading in and out over a
  third of a block at each; they stop growing early as you come close), faint where nothing flows yet. Several wires between
  the same two cards run side by side in lanes (a line, its edges and a clear gap apart), so the two directions of a pair part. At its middle a tag says what
  goes along it: the icon, the rate in large type and the name small under it, on a plain soft dark backing, as big as
  the cards' ports there; on the line when the wire runs alone, beside its own lane, on the side away from the other
  wires, when two cards share several.
- The wire the crosshair is on (its tag, or its line within 24 GUI pixels, when no card is under the crosshair) is
  highlighted, and the minimap
  highlights the same wire and (with "Centre on the machine you look at") glides to it.

The earlier AR lens (every machine's live state, read from GregTech, with stats and charts) is shelved on the branch
`shelf/ar-machine-lens`.

## Safety

- Nothing in the world loads the plans (`Plan.loaded()`): loading them while NEI is still loading its recipes can
  fail a slot. A slot that cannot be read is kept as it was (`Graph.unreadable`), written back unchanged while it
  stays empty, and read again when it is opened.

## Order of work

1. Settings and zoomed-out names.
2. Plan snapshot and the minimap, with its keys and settings.
3. Placing cards: the data, the place key, the picker, placing from the world.
4. Show in the world, and looking at a placed block.
5. The plan over the world.

All five are built (2026-10-08). Keys (Controls, GTNH Planner): N minimap, [ and ] zoom, arrows pan, Y the plan over
the world, L place a card on the block you look at.
