# Tairoto

*Tairoto* is Tahitian for lagoon. This is one, for Android, seen from above:
a school of *ʻatoti* (green chromis) and a *honu* (green sea turtle) over white
coral sand, under moving caustic light. It is built to grow into one lagoon
shared by several devices on a table, fish swimming from one screen to the next.

> Work in progress, kept local for now. Milestones 1 (the solo lagoon),
> 2 (food and feeding), 3 (the bottom dwellers) and 4 (arranging the lagoon) of 10.

Tahitian names are checked against a reference before they are used, and the
source is noted next to each species in `Species.kt`.

## What is in it

- **The creatures of the lagoon**, each by its Tahitian name (sources in `Species.kt`):
  five schools of small fish — ʻatoti (green chromis), manini (convict surgeonfish),
  taape (bluestripe snapper), ʻouma (yellowstripe goatfish, with its barbels) and
  mamo (scissortail sergeant) — the honu (green sea turtle), the fai manu
  (ocellated eagle ray) that flaps, glides and buries itself in the sand, the feʻe
  (day octopus) that walks on soft arms, camouflages, inks and jets away, and the
  uʻa (hermit crab) that steps on planted feet and hides in its shell.
- **A lagoon floor to arrange**, each piece by its Tahitian name (sources in
  `Decor.kt`): toʻa (a Porites coral head, knobbly, with Christmas tree worms),
  toʻapū (branching Acropora coral), pahua (the giant clam, its mantle blue,
  green or gold) and ʻōfaʻi (a stone crusted with coralline algae). They are
  part of the lagoon, not scenery: fish swim over them or around them,
  crabs and the octopus walk round them, food settles on top, the turtle may
  rest on a coral head, the octopus makes its den against one, frightened
  chromis dive in among the branches of a toʻapū, and the clam snaps shut
  (with a puff) when a shadow or a knock comes near, then opens again slowly.
- **Arranging**: the pencil button turns the lagoon into an edit mode: drag a
  decoration to move it, twist with two fingers (or tap ↻) to turn it, take it
  away, place new ones from the picker's Decorations tab, or take up the net
  and tap a creature to scoop it out. Every change can be undone and redone
  (a whole drag is one change; undoing a netted creature puts it back where
  it was caught).
- **The lagoon is kept**: saved a moment after every change and when the app
  goes to the background, every creature where it is at that moment, and
  restored at the next launch (a versioned file that tolerates damage and
  species it does not know).
- **Adaptive layout**: on a tablet, an unfolded foldable or a desktop window the
  species picker and the settings open as a pane beside the running lagoon; on a
  phone or a cover screen the picker is a sheet over it. Folding and unfolding
  move them between the two, and the lagoon widens for a bigger window
  (Navigation 3 scenes: supporting pane, bottom sheet).

- **Food and feeding**: drag to sprinkle flakes, hold for a pellet; a scent that grows
  with the food and the feeders draws fish from further away; messy bites, crumbs,
  decomposition, every milligram accounted for; a current grid stirred by tails.
- **C++ simulation** (`engine/src/main/cpp/sim`): schooling with a spatial grid,
  beat-and-glide swimming, a spine that bends into turns, a caudal fin on a
  damped spring; a turtle that cruises, rests on the sand, climbs to breathe
  and flees when tapped. Fixed 120 Hz steps, deterministic for a seed.
- **Vulkan renderer** (`engine/src/main/cpp/render`, `shaders/`): an HDR scene
  pass (procedural sand, pebbles, two-layer caustics, soft depth-scaled
  shadows, marine snow, creatures painted entirely in shaders) and a composite
  pass (surface refraction, sun glints, tap ripples, ACES tone mapping, grade,
  vignette, dither). No textures anywhere.
- **Top-down 2.5D**: millimetres on the table and depth below the surface,
  with a shared projection (`Camera.kt`, `camera.h`, `common.glsl`).
- **Quality tiers** Low / High / Ultra / CI, with an automatic governor fed by
  frame rate and thermal headroom; ADPF performance hints from the render thread.
- **Kotlin core** (`core/world`): geometry, camera, species catalogue, entity
  ownership with epochs, a `Link` abstraction (loopback for now), the session.
- **Compose app**: full-bleed tank, glass HUD, creature picker with drawn
  species portraits, settings (Navigation 3, DataStore).

## Build and test

```sh
./gradlew :app:assembleDebug
./gradlew :core:world:test                  # Kotlin core (JVM)
./gradlew :app:testDebugUnitTest            # view model (JVM, fake engine)
./gradlew :engine:nativeHostTest            # C++ simulation and meshes, on the host
./gradlew :app:validateDebugScreenshotTest  # Compose screenshots
./gradlew :engine:connectedDebugAndroidTest :app:connectedDebugAndroidTest  # on a device
scripts/fold-tests.sh                       # on a foldable emulator: folds and unfolds it (espresso-device)
```

Previews for every major form factor (phone, foldable, tablet, desktop) are in
`app/src/main/kotlin/.../ui/preview`; the screenshot tests render the same content.

Deterministic launch for screenshots (debug builds):

```sh
adb shell am start -n com.jpcottin.tairoto/.MainActivity --ez tairoto.ci true --el tairoto.seed 7
# also: --es tairoto.quality low|high|ultra|ci  --ez tairoto.stats true  --ez tairoto.hints false
#       --es tairoto.population "day_octopus:1,hermit_crab:2"  --ef tairoto.zoom 2.5   (a close look)
#       --es tairoto.decor "coral_head:1,giant_clam:2"   (these decorations, in a column down the middle)
#       --ez tairoto.fresh true   (a new lagoon, the saved one left alone; ci, population and decor imply it)
```

## Gestures

Tap the water: a ripple, and the fish nearby dart away. Double tap: zoom.
Two fingers: pan and pinch. Mouse: drag to pan, wheel to zoom. One finger
dragging sprinkles flakes; hold for a pellet.

Arranging (the pencil button): tap a decoration to select it, drag it to move
it, twist two fingers to turn it; drag over open water to pan. With the net:
tap a creature. Back or Done to finish.
