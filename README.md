# ZenithProxy AutoFarm

An AutoFarm plugin for ZenithProxy 1.21.4. It scans nearby loaded blocks and can harvest mature crops, plant configured items, till dirt, and apply bone meal.

[中文说明 / Chinese documentation](README_zh.md)

## Features and limits

- Harvests wheat, carrots, potatoes, beetroot, Nether wart, sweet berry bushes, and cocoa by default. Add other supported block IDs with `harvestBlock add <block>`; for example, pumpkins and melons can be harvested when mature.
- Plants configured seeds/items on farmland, or Nether wart on soul sand. With `onlyReplant` enabled, it only replants crops this module harvested.
- Optionally tills nearby dirt, grass blocks, coarse dirt, and rooted dirt. A hoe must be in the inventory. `moistOnly` requires water nearby and defaults to on.
- Optionally uses bone meal on configured blocks. Nether wart cannot be fertilized with bone meal.
- Searches loaded blocks within `searchRadius`. Mature crops around the first target are queued within `batchRange`; the bot processes that local batch in order, replants harvested positions, collects matching drops, and then searches for the next batch. It paths to targets it cannot reach from its current position and rechecks block state before acting.
- When the inventory has two or fewer empty slots and contains configured farm items, it can path to a configured storage chest, deposit farm outputs, and keep one stack of each configured planting item for replanting.
- Storage uses `waitForInteractTimeout` as the timeout for opening/depositing and `interactDelay` as the tick delay between inventory actions. Chest storage requires the configured position to be reachable and the chest to be usable.

`blocksPerTick` is exposed to match the reference module's settings. The current ZenithProxy interaction API executes at most one block interaction per bot tick, so changing this setting does not increase the actual interaction rate. The batch queues still reduce repeated searching and pathing between nearby crops.

The crop must have the correct soil and be mature before harvesting. Ordinary crops need farmland; Nether wart needs soul sand. Tilling and bone meal are disabled by default.

## Commands

Enter commands in the ZenithProxy terminal without a prefix, or in game with `/` or `!` (for example, `/autofarm status`). In-game command behavior depends on ZenithProxy command settings and permissions.

```text
autofarm on|off
autofarm status
autofarm debug [on|off]
autofarm searchRadius <1-8>
autofarm range <1-8>  (alias)
autofarm blocksPerTick <1-16>
autofarm storageChest <x y z>|clear
autofarm waitForInteractTimeout <ticks>
autofarm interactDelay <ticks>
autofarm waitForCropMaturity <ticks>
autofarm harvest on|off
autofarm plant on|off
autofarm onlyReplant on|off
autofarm till on|off
autofarm moistOnly on|off
autofarm bonemeal on|off
autofarm harvestBlock add|remove <block>
autofarm plantItem add|remove <registered item>
autofarm bonemealBlock add|remove <block>
```

`plantItem` uses ZenithProxy's item registry parser and suggestions. IDs may include or omit the `minecraft:` namespace. Successful setting changes are shown in green; invalid or unsupported values are shown in red. The persistent configuration is stored in `plugins/config/autofarm.json`.

`autofarm debug on` enables periodic scan and action diagnostics in the ZenithProxy log; `autofarm debug off` disables them. Bare `autofarm debug` prints the bot's position and rotation, main/off-hand items, a 3×3 block grid at the bot's feet, and a 3×3 grid of the ground blocks below. Each block includes its state ID; crop age is shown when available.

Set the storage location with `autofarm storageChest <x> <y> <z>` using the chest block's coordinates; use `autofarm storageChest clear` to disable automatic storage. Avoid sharing real server coordinates in public logs or issue reports. `waitForInteractTimeout` accepts 1–1200 ticks (20 by default), and `interactDelay` accepts 1–100 ticks (1 by default). After planting, the module waits for the crop to mature and checks its age every `waitForCropMaturity` ticks (200 by default) before harvesting it.

## Deploy and test

1. Use the ZenithProxy `java` release channel; plugins are not supported on the `linux` channel. Put `ZenithProxyAutoFarm-1.0.0.jar` in the launch directory's `plugins` folder and restart ZenithProxy.
2. Confirm the plugin command is registered with `help autofarm`, then run `autofarm status`. The module starts disabled.
3. Connect to a test world and stand next to a small farm. Keep the test area within four blocks of the bot and in loaded chunks.
4. For the first test, use only a wheat plot. Put wheat seeds in the bot inventory and confirm the `plantItems` setting includes `wheat_seeds`. Make one wheat crop mature and leave the farmland directly below it in place.
5. Run `autofarm debug` to verify the bot position and held items. When testing Nether wart, confirm the ground grid reports `soul_sand` below it. Then run `autofarm on`. The module prioritizes mature crop harvesting across the scan before trying planting. When `onlyReplant` is off, it may plant an available configured item on empty farmland in range. Check the bot inventory and crop block after a few seconds.
6. Stop the module with `autofarm off`. After wheat works, repeat with carrots, potatoes, beetroot, and Nether wart on soul sand. Keep bone meal disabled until you deliberately test it.

Useful test settings:

```text
autofarm range 3
autofarm batchRange 3
autofarm harvest on
autofarm plant on
autofarm onlyReplant off
autofarm till off
autofarm bonemeal off
autofarm status
```

`searchRadius` controls discovery around the bot; `batchRange` controls how far from the first mature crop the current harvest/replant batch extends. Start with a small batch and increase it after confirming the bot can reach and replant the area. If you want planting to occur only in the spaces it just harvested, turn on `onlyReplant`. To test tilling separately, provide a hoe and an empty dirt/grass block near water, then run `autofarm till on`; turn it back off afterward. To test bone meal, add a supported block such as `wheat` to the `bonemealBlocks` list, provide bone meal, and enable the setting. Do not add Nether wart.

If nothing happens, check that the plugin loaded after restart, the module is enabled, the bot is alive and connected, the block is within range, the chunk is loaded, the crop is mature, and the required seed/tool is in the bot inventory. Check ZenithProxy logs for Baritone or inventory errors.

## Build

```powershell
.\gradlew.bat build
```

The JAR is written to `build/libs`. ZenithProxy loads plugins at startup; hot reload is not supported.
