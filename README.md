# AnkiCopy — gamepad-driven Anki + on-device Granite AI bridge

Standalone Accessibility Service. No MacroDroid/Tasker/AutoInput, no PC/
Termux/Ollama dependency. Runs three Granite GGUF models **entirely
on-device** via a bundled llama.cpp (JNI), and shows explanations as a
floating overlay while you review in AnkiDroid.

## Current button mapping
| Button | Action |
|---|---|
| A, B | Untouched - configure "Show Answer"/"Again" in AnkiDroid's own gamepad settings |
| X | Bring AnkiDroid to fullscreen (collapses split screen back to just Anki) |
| Y | Split screen: AnkiDroid + your currently selected AI app (default **ChatGPT**) |
| Select | Cycle which AI app Y opens (ChatGPT -> Claude -> Gemini -> loop) |
| D-pad Up | Very slow controlled smooth scroll up (1.5s per swipe) |
| D-pad Down | Very slow controlled smooth scroll down (1.5s per swipe) |
| D-pad Left | Show the on-device Granite explanation tooltip for the current card |
| L1 | Toggle the on-device Granite tooltip on/off |
| R1 | Cycle the active Granite model: Granite 4.1:3b -> Granite 4:micro -> Granite 4:1b (loop) |
| L2 | Untouched - left as AnkiDroid's own menu-navigation button |
| R2 | Go home |
| Start | Copy whole card + explanation prompt to clipboard (no app launch - paste manually, or press Y first to open the AI) |

A small status overlay (toggled the first time you press L1, R1, or Select)
shows the currently selected AI app and Granite model/on-off state in the
top-right corner.

Press **D-pad Left** while reviewing a card to show the floating explanation
tooltip - same dark card, fade-in, and three-dot loading pulse as the
original desktop LLM Hover Tooltip Anki add-on, generated live by whichever
Granite model R1 currently has selected, for the entire current card's text.


## One-time setup
1. **Build the APK** (see "Build & install" below - this one needs Android
   Studio / NDK, it won't build from a plain online CI runner as easily as
   the old pure-Kotlin version because of the native llama.cpp component).
2. Download the three GGUF files you want to `ibm/granite4.1:3b`,
   `granite4:micro-h`, `granite4:1b-h` somewhere on your phone (any app -
   browser downloads folder, a file manager, cloud storage synced locally,
   etc.) - anywhere the system file picker can see.
3. Open AnkiCopy -> tap "Choose file..." under each of the three model rows
   -> pick the matching .gguf. The app copies it into its own private
   storage on first pick, so it survives even if you later move/delete the
   original download.
4. Turn on the Accessibility Service (button in-app) and grant "Display
   over other apps" (needed for both the status overlay and the tooltip).
5. Open AnkiDroid and start reviewing. R1/L1 to pick/toggle the model,
   long-press or double-tap a word for an explanation.

## How on-device inference works
- `app/src/main/cpp/llm_bridge.cpp` is a small JNI bridge around llama.cpp
  (vendored as a git submodule at `app/src/main/cpp/llama.cpp` - run
  `git submodule update --init` before building).
- `LlamaEngine.kt` wraps one loaded GGUF + its llama_context.
- `LlmManager.kt` keeps up to three `LlamaEngine`s resident at once (one per
  Granite slot) on a single background thread, so R1 switching is instant -
  no reload - at the cost of holding all three models' RAM simultaneously.
  If that's too much for your device, `LlmManager.generateExplanation()` is
  the place to add an eager-unload-previous-slot step instead.
- Each explanation request calls `resetContext()` first, so the model's KV
  cache doesn't accumulate across many long-presses - every explanation is
  a one-shot request, not an ongoing chat.
- Only `arm64-v8a` is built by default (see `build.gradle`'s `abiFilters`);
  add other ABIs there if you need to test on an emulator or older device.

## The tooltip overlay
`TooltipOverlay.kt` + `res/layout/tooltip_overlay.xml` reproduce the
desktop add-on's card look pixel-for-pixel in color (`#1b1b1f` body,
`#232328` header, `#3a3a40` border) and motion (120ms fade+slide-in
entrance, three-dot loading pulse using the same 0%/40%/80%/100% keyframe
timing as the CSS version, not a naive linear tween). It's drawn as a real
system overlay window (`TYPE_ACCESSIBILITY_OVERLAY`) positioned near your
tap point, with edge-avoidance so it never draws off-screen.

## Why D-pad Left triggers the tooltip (not long-press/double-tap)
An earlier version of this app tried to detect long-press/double-tap on
card text by turning Android's accessibility "touch exploration" mode
(the same mechanism TalkBack uses) on only while AnkiDroid was foreground,
off otherwise. **That caused system-wide freezes requiring a hard phone
restart.** The root cause: touch exploration isn't a clean per-service
on/off switch - the OS's own docs note that toggling one service's flag
doesn't deterministically control system-wide touch-exploration state -
and the event used to detect "entered/left AnkiDroid"
(`TYPE_WINDOW_STATE_CHANGED`) fires far more often than that, on every
dialog, fragment change, and system popup. The result was touch
exploration flipping on/off rapidly and wedging the system's input
handler. That mechanism has been **removed entirely** - this build never
calls `setServiceInfo()` at runtime and never requests
`canRequestTouchExplorationMode`.

**Also worth knowing:** because AnkiCopy is a separate app from AnkiDroid,
it was never able to get true word-level text selection anyway - even the
removed gesture approach only approximated a "focus point" via whichever
accessibility node was under your finger, then sent the *whole card's*
text to the model with that node's text as a hint. D-pad Left does the
same thing (whole card text, no selection needed), just triggered by a
plain button press instead of a fragile system-wide input mode - so you
lose nothing in explanation quality, only the (already-approximate) idea
of pointing at a specific word.

## If a button isn't doing what you expect: turn on Debug Mode
Open the app, flip the **Debug mode** switch on. Now press any button that
isn't working as expected. If it's unmapped (falls to the `else` branch),
you'll get a toast showing its real Android keycode, e.g.
`Unmapped key: KEYCODE_BUTTON_C`.

This matters because **gamepads don't all report the same keycode for the
same physical button** - what your controller calls "X" might arrive as
`KEYCODE_BUTTON_X`, `KEYCODE_BUTTON_3`, or something else depending on its
HID mapping and Android's driver for it.

**To fix a mismatch:** note the keycode debug mode shows you for the
physical button you pressed, then open `AnkiAccessibilityService.kt` ->
`onKeyEvent()` and swap the relevant `KeyEvent.KEYCODE_BUTTON_*` constant
for whatever keycode debug mode reported.

## Fix for scroll not working
D-pad keycodes (`KEYCODE_DPAD_UP`/`DOWN`) are far more standardized across
controllers than face buttons, so this should be reliable. If it still
doesn't scroll, turn on Debug Mode and confirm D-pad presses aren't being
swallowed by AnkiDroid's own UI first (some apps intercept D-pad for their
own navigation).

## Split-screen limitation (unchanged)
`FLAG_ACTIVITY_LAUNCH_ADJACENT` reliably works on tablets/foldables/Samsung
multi-window; plain-phone Android doesn't guarantee automatic split-screen
entry from a background service. Some phones will open the second app
fullscreen instead, requiring one manual drag-in from Recents - this is a
device/OEM limitation, not something fixable without root.

## Build & install
Two ways to get an APK:

### Option A — GitHub Actions (no Android Studio needed)
1. Push this repo to GitHub (`git submodule update --init` first, then commit
   as usual - the `.gitmodules` file is already set up so llama.cpp comes
   along as a submodule reference, not a huge blob).
2. GitHub Actions picks up `.github/workflows/build.yml` automatically.
   It runs on every push to `main` and on pull requests, or you can trigger
   it manually: repo -> **Actions** tab -> **Build AnkiCopy APK** ->
   **Run workflow**.
3. This build has a native (NDK/CMake) component - llama.cpp compiles from
   source - so expect **~15-25 minutes** per run, noticeably slower than a
   plain-Kotlin Android build. That's normal, not a sign anything's stuck.
4. Once it finishes, open that run and scroll to **Artifacts** at the
   bottom: `AnkiCopy-debug-apk` is there. Download the zip, unzip it, and
   you have an installable `.apk` - the debug build installs directly on
   your phone (enable "install unknown apps" for whatever app/browser you
   use to open it).
5. Triggering the workflow manually (`workflow_dispatch`) also builds an
   `AnkiCopy-release-unsigned-apk` artifact - smaller/optimized, but
   Android won't install an unsigned APK as-is. Use the debug APK for
   normal sideloading; only bother signing the release one if you
   specifically want a smaller/faster build and know how to run
   `apksigner` yourself.
6. If a run fails at the "Install NDK + CMake" or "Build debug APK" step,
   the most common cause is the `ndkVersion` in `app/build.gradle` no
   longer matching what's installed - check the failing step's log for the
   exact version it complains about.

### Option B — Android Studio (local build)
1. `git clone` this project, then `git submodule update --init` to pull
   llama.cpp into `app/src/main/cpp/llama.cpp`.
2. Install the NDK (26.1.10909125, pinned in `build.gradle`) via Android
   Studio's SDK Manager if you don't have it.
3. Open the project in Android Studio, let it sync/configure CMake, then
   Build > Build APK.
4. Install the APK, then follow "One-time setup" above to point each
   Granite slot at its .gguf file.

If a build step fails on the CMake/NDK configuration specifically, check
that `ndkVersion` in `app/build.gradle` matches an NDK version you actually
have installed - mismatches here are the most common native-build failure.
