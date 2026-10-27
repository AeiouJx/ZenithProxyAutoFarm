# ZenithProxy 自动农场

这是一个面向 ZenithProxy 1.21.4 的自动农场插件。它会扫描 bot 附近已加载的方块，可收割成熟作物、种植指定物品、耕地和使用骨粉。

[English README](README.md)

## 功能与限制

- 默认收割小麦、胡萝卜、马铃薯、甜菜根、下界疣、甜浆果丛和可可豆荚。可以用 `harvestBlock add <方块>` 添加其他方块，例如南瓜和西瓜。
- 按配置在耕地上种植指定种子/作物，在灵魂沙上种植下界疣。开启 `onlyReplant` 后，只补种本模块刚收割过的作物。
- 可选耕种附近的泥土、草方块、砂土和缠根泥土；背包需要有锄头。`moistOnly` 默认开启，会要求附近有水。
- 可选对 `bonemealBlocks` 列表中的方块使用骨粉。下界疣不能使用骨粉。
- 在 `searchRadius` 范围内搜索已加载区块。找到第一个成熟作物后，会把 `batchRange` 范围内的成熟作物加入当前队列，依次处理并补种刚收割的位置，再收集对应掉落物，最后搜索下一批。当前站位够不到的目标会单独寻路，到达后会重新检查方块状态。
- 背包空格不超过 2 格且有农场相关物品时，可前往配置的箱子存入农作物，并为每种已配置种植物品保留一组供补种。
- 箱子收纳使用 `waitForInteractTimeout` 设置打开/存储等待超时，用 `interactDelay` 设置背包操作之间的 tick 间隔。箱子位置必须可到达且可交互。

`blocksPerTick` 设置项用于保持与参考插件的参数一致。当前 ZenithProxy 交互 API 每个 bot tick 最多执行一次方块交互，因此调高此值不会提高实际交互速度；批次队列仍可减少附近作物间重复扫描和寻路。

作物需要处于正确土壤上并达到成熟阶段才会收割。普通作物需要耕地，下界疣需要灵魂沙。耕地和骨粉功能默认关闭。

## 命令

在 ZenithProxy 终端输入命令时不需要前缀；游戏内使用 `/` 或 `!`，例如 `/autofarm status`。游戏内命令受 ZenithProxy 的命令设置和权限控制。

```text
autofarm on|off
autofarm status
autofarm debug [on|off]
autofarm searchRadius <1-8>
autofarm range <1-8>（别名）
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
autofarm harvestBlock add|remove <方块>
autofarm plantItem add|remove <注册表物品>
autofarm bonemealBlock add|remove <方块>
```

`plantItem` 使用 ZenithProxy 的物品注册表参数解析，并支持注册表建议。ID 可带或不带 `minecraft:` 命名空间。设置成功显示绿色提示；无效或不支持的值显示红色提示。配置保存在 `plugins/config/autofarm.json`。

`autofarm debug on` 会在 ZenithProxy 日志中开启周期性扫描和动作调试信息；`autofarm debug off` 关闭调试输出。单独运行 `autofarm debug` 会输出 bot 坐标和朝向、主手/副手物品、脚下方块的 3×3 网格，以及地面方块的 3×3 网格。每个方块都会显示 state ID；可用时也会显示作物年龄。

使用 `autofarm storageChest <x> <y> <z>` 设置箱子方块坐标；运行 `autofarm storageChest clear` 可关闭自动存储。请勿在公开日志或问题报告中分享真实服务器坐标。`waitForInteractTimeout` 范围是 1–1200 tick（默认 20），`interactDelay` 范围是 1–100 tick（默认 1）。种下作物后，模块会进入成熟等待状态，并按 `waitForCropMaturity` 设置的间隔检查作物年龄（默认每 200 tick 检查一次），成熟后再收割。

## 部署与测试

1. 确认 ZenithProxy 使用 `java` 发布通道；插件不支持 `linux` 通道。把 `ZenithProxyAutoFarm-1.0.0.jar` 放入启动目录下的 `plugins` 文件夹，然后重启 ZenithProxy。
2. 用 `help autofarm` 确认命令已注册，再运行 `autofarm status`。模块默认关闭。
3. 连接到测试世界，在一小块农田旁站好。先把测试范围控制在 bot 周围 4 格内，并确保区块已加载。
4. 第一次只测小麦：在 bot 背包放入小麦种子，确认 `plantItems` 中有 `wheat_seeds`；让一株小麦成熟，并保留它下方的耕地。
5. 测下界疣时先执行 `autofarm debug`，确认下界疣下方的地面网格显示 `soul_sand`。然后执行 `autofarm on`。插件会先扫描并收割范围内成熟作物，再尝试种植。`onlyReplant` 关闭时，插件也可能在范围内空置耕地上种植背包里有的已配置作物。等待几秒，检查方块和 bot 背包。
6. 执行 `autofarm off` 停止。小麦正常后，再分别测试胡萝卜、马铃薯、甜菜根，以及种在灵魂沙上的下界疣。明确测试骨粉功能前，保持骨粉选项关闭。

建议先用以下设置：

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

`searchRadius` 决定 bot 周围的搜索范围；`batchRange` 决定当前批次相对第一株成熟作物的处理范围。先用较小批次确认 bot 可以到达并补种，再逐步增大。若只想补种刚刚收割的位置，执行 `autofarm onlyReplant on`。单独测试耕地时，准备一把锄头，在附近水源边找一块空的泥土/草方块，再执行 `autofarm till on`；测试后关闭。测试骨粉时，先把 `wheat` 加入 `bonemealBlocks`，给 bot 骨粉，再开启骨粉功能。不要把下界疣加入骨粉列表。

如果没有动作，依次检查：重启后插件是否加载、模块是否开启、bot 是否存活并已连接、方块是否在范围内且区块已加载、作物是否成熟，以及 bot 背包是否有对应种子或工具。也可以查看 ZenithProxy 日志中的 Baritone 或背包操作错误。

## 构建

```powershell
.\gradlew.bat build
```

JAR 输出到 `build/libs`。ZenithProxy 只在启动时加载插件，不支持热重载。
