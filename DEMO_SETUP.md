# Shadowplay demo setup

Checklist for running the live demo. Do sections 1–4 once per machine/world, section 5 before every run.

## 1. Launch

```bash
cd ~/Desktop/dev/shadowplay
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew runClient
```

The game folder is `run/` in the project: worlds in `run/saves/`, config in `run/config/shadowplay.json`.

## 2. Config (`run/config/shadowplay.json`)

- [ ] `"useGoldenPlans": true` (the three demo prompts run their pinned plans, no Gemini involved)
- [ ] `"humanize": { "enabled": true, ... }` (thinking pause, look-before-walking, look-around in the cave)
- [ ] Optional, only for off-script prompts: `"geminiApiKey"` (or the `GEMINI_API_KEY` env var) and `"geminiModel": "gemini-3.8-flash"`

The file is gitignored. Never commit it.

## 3. Game options (Options menu)

- [ ] Controls: Auto-Jump **OFF**, Sprint **Hold**, Sneak **Hold**
- [ ] Mouse: sensitivity 100%, Raw Input **ON**
- [ ] Video: FOV **70**, FOV Effects **0%**, View Bobbing **OFF**, GUI Scale **2**
- [ ] Chat: visible; Chat Width at max, Chat Height large enough for ~8 lines
- [ ] Press **F3 + P** once so the game does **not** pause when the window loses focus (otherwise clicking away from the game interrupts the agent)

## 4. World (once, in the demo world)

Create the world with Game Mode **Survival**, Difficulty **Peaceful**, **Allow Commands ON**.

TODO: seed and curated areas (oak trees on flat ground, shallow lake with a sand bed, cave entrance visible from a standing spot, ≥ 6 exposed coal ores just inside).

Then run these once (press **T**, paste each line):

```
/gamerule doDaylightCycle false
/time set day
/gamerule doWeatherCycle false
/weather clear
/gamerule announceAdvancements false
/advancement grant @p only minecraft:story/root
/advancement grant @p only minecraft:story/mine_stone
/advancement grant @p only minecraft:story/upgrade_tools
/recipe give @p *
/xp set @p 0 levels
/xp set @p 0 points
```

The advancement and recipe lines unlock, ahead of time, everything the demos would otherwise unlock, so no "Advancement Made!" / "New Recipes Unlocked!" pop-ups or advancement chat lines appear mid-demo. (Tested: zero pop-ups during demo 1.)

Save a backup copy of the world folder (`run/saves/<world>`) once it's ready.

## 5. Before each run

- [ ] Inventory: empty hotbar (`/clear @p` resets everything)
- [ ] Stand at the demo 1 start position, facing the trees. TODO: coordinates
- [ ] Before demo 3: give a stack of torches (`/give @p torch 64`); the stone pickaxe comes from demo 1
- [ ] TODO: replace the manual steps above with `/function shadowplay:reset` once the reset datapack exists

## 6. The three demo prompts

Run them in order, in one session (demo 3 uses demo 1's stone pickaxe). Case and punctuation don't matter; the keywords do.

| Demo | Type | Must contain | Notes |
|---|---|---|---|
| 1 | `/agent get me a stone pickaxe` | "stone" and "pickaxe" | ~50s |
| 2 | `/agent bring me a block of sand from underwater from the lake to your right` | "sand", plus "lake" or "underwater" | Face so the lake is on your **right** when you press Enter. ~18s |
| 3 | `/agent go into that cave, place torches so you can see, and mine 5 coal` | "cave" and "coal" | Always mines 5. Torches must be in the inventory. ~43s |

What the audience sees: a "Thinking…" line, a one-line plan, one "▶ n/N" line per step (about a second apart), and a green "✔ Done" line with the time.

## 7. During the demo

- **Any real input stops the agent** (movement keys, jump, sneak, sprint, clicks, mouse movement, Esc). It says so in chat and waits.
- `/agent resume` continues from where it stopped (finished steps are skipped).
- `/agent stop` cancels the task. `/agent status` shows what it's doing. `/agent help` lists commands.
- Typing in chat does not interrupt it.

## 8. If something goes wrong

- A step fails: the agent retries up to twice (yellow "⚠" line), then stops with a red "✖ Couldn't finish: …" line. Fix the scene (or teleport back), then run the prompt again.
- Gemini down/rate limited (off-script prompts only): chat says so ("Gemini is rate limited right now…"); demo prompts are unaffected.
- Logs for debugging: `run/logs/latest.log` (lines are prefixed `[Shadowplay]`).
