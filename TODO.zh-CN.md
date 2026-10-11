# Forbrickit — Bukkit 桥接层 TODO

[English](TODO.md) | 简体中文

第四国（在 Fabric + Forge + NeoForge 统一服务端上跑 Bukkit/Paper 插件）的进度与待办。
代码在 `forbric-kernel`，具体在 `src/runtime/java/org/cn/xiaofan/bukkit/`。

桥接层**答不出来的方法一律抛出具名的 `UnsupportedOperationException`**，绝不返回一个"看着像对的"错值。
所以下面说的"没适配"，指的是"抛出一个能自己报出名字的异常"——是一个看得见、查得出的缺口，而不是静默坏掉。

---

## 1. 现在到哪一步了

### 已经端到端跑通（在真实服务端上验证过）

| 功能 | 状态 |
| --- | --- |
| 插件加载（`plugin.yml`、`JavaPlugin`、`onEnable`/`onDisable`） | ✅ 能启用、能干净地停用 |
| 插件管理器、命令表、`/kit` 这类命令 | ✅ 能注册、能执行、受权限控制 |
| `plugin.yml` **和** Paper Brigadier 两套命令树 | ✅ 都支持，含别名与补全 |
| 权限判定（`hasPermission`、`isOp`、attachment） | ✅ 含玩家入列前的 `default: op` |
| 聊天 / 动作栏（`sendMessage(String)`、`sendActionBar(String)`） | ✅ 纯文本（样式丢失，见 §3 B1） |
| 玩家身份、世界、坐标、在线状态 | ✅ |
| 玩家背包读/写、护甲、副手、迭代器 | ✅ 实时视图，写入即生效 |
| 物品创建（`ItemStack.of`、`new ItemStack(...)`） | ✅ 走 `ItemType` 注册表 |
| 物品序列化（`serializeAsBytes`、`serializeItemsAsBytes`、`deserialize...`） | ✅ Paper 的字节格式 |
| `ItemStack.empty()` / `createEmptyStack()` | ✅ |
| 调度器（`BukkitRunnable`、`runTask`、延迟、重复） | ✅ |
| 事件注册 + 派发（`registerEvents`、`callEvent`） | ✅ 含优先级排序 |
| `Bukkit.setServer`、`ServerBuildInfo`、`RegistryAccess` 等 ServiceLoader 服务 | ✅ |

### 核心数字

生成类（由 `tools/gen_stub.py` 生成）：

| 绑定的接口 | 已实现 | 总数 | 未实现 |
| --- | --- | --- | --- |
| `ForbricPlayer`（`org.bukkit.entity.Player`） | 24 | 632 | 608 |
| `ForbricWorld`（`org.bukkit.World`） | 17 | 211 | 194 |
| `ServerImpl`（`org.bukkit.Server`） | 14 | 202 | 188 |
| `ForbricEntity`（`org.bukkit.entity.Entity`） | 11 | 159 | 148 |
| `ForbricBlock`（`org.bukkit.block.Block`） | 12 | 73 | 61 |
| **小计** | **78** | **1277** | **1199** |

手写的桥接类（文件里的拒绝点数量）：

| 类 | 未实现 |
| --- | --- |
| `ForbricArgumentTypes`（Paper 命令参数类型） | 33 |
| `ForbricApiBridge`（Paper `InternalAPIBridge`） | 20 |
| `ForbricItemStack`（组件层） | 19 |
| `ForbricItemType`（Paper `ItemType`） | 15 |
| `ForbricPlayerInventory`（仅容器层这一部分） | 5 |
| `ForbricRegistryAccess`（非物品注册表） | 3 |
| **小计** | **95** |

**全桥接层约 1294 处拒绝点（≈ 绑定面的 95%）。**

> **关于这两张表。** 它们量的是不同的东西，**不能相加**。
> 上面那张数的是*所绑定 Bukkit 接口的方法数*（`Player`/`World`/… 强制桥接层声明的每一个抽象方法）。
> 下面那张数的是*实现 Paper 自身服务接口的文件里的拒绝点*，其中一些是辅助重载，不一定是接口方法。
> 另外 `ForbricBlock` / `ForbricEntity` / `ForbricPlayer` / `ForbricWorld` / `ServerImpl`
> 在生成的方法体里也有手写的拒绝，它们算在第一张表的列里，不在第二张里。

**这个百分比单看是会误导的，不能读成"只完成了 5%。"** 这些接口本身极其庞大（光 `Player` 一个就 632 个方法），
而剩下的绝大多数插件根本碰不到。真正要看的是**缺口的形状** —— §2 按"插件有多可能用到"做了分级。
只想要命令、权限、聊天和物品的插件**今天就能跑**；想要自定义 GUI 容器或物品名称的还不行。

---

## 2. 还缺什么，按优先级

### P0 —— 直接卡住一整类插件

这些不是少一个调用，而是让插件根本没法工作。

| # | 缺口 | 位置 | 为什么重要 |
| --- | --- | --- | --- |
| 1 | **Adventure `Component` 桥** | `ForbricPlayer.sendMessage(Component)`、标题、书 | 所有给聊天上色、用悬停/点击的插件，现在发出去的都是无色纯文本（只有 `String` 重载能用）。两套对象图表达同一份 JSON，但不经序列化+解析无法互转。 |
| 2 | **`ItemMeta`** | `ForbricItemStack.getItemMeta` / `setItemMeta` | 自定义名、lore、附魔、耐久、物品 PDC。真实插件里占比很大（商店、带名字的 kit、自定义合成）。需要 Bukkit ↔ 游戏组件的映射。 |
| 3 | **容器层** | *不存在* —— 需要新建 `ForbricInventory` / `InventoryView` | 箱子、漏斗、自定义 GUI：`openInventory`、`InventoryHolder`、光标上的物品、查看者。 |
| 4 | **`Block` 的变更面** | `ForbricBlock`（61 未实现） | `setType` 能用；`getDrops`、`breakNaturally`、`getState`、方块数据、`getRelative` 都不能。世界编辑 / 建筑类插件需要这些。 |
| 5 | **`Entity` 超出句柄的部分** | `ForbricEntity`（148 未实现） | 生成、血量、伤害、AI、传送、装备 —— 需要在游戏侧做实体生命周期的工作。 |

### P1 —— 常用，一个功能一项

| # | 缺口 | 位置 |
| --- | --- | --- |
| 6 | `World` 生成 / 区块访问（`getChunkAt`、`getHighestBlockYAt`、生物群系、时间、天气、出生点） | `ForbricWorld` |
| 7 | `Player` 玩法：游戏模式、饱食度、血量、经验、传送、重生、睡觉、`give`、`dropItem` | `ForbricPlayer` |
| 8 | `Player` 冷却、肩上的实体、激流、鱼钩 | `ForbricPlayer` |
| 9 | `Server` 配方（`getRecipesFor`、`craftItemResult`）、`createExplorerMap` | `ServerImpl` |
| 10 | `Player` 射线检测 / 目标选取（`getTargetBlock`、`rayTraceEntities`） | `ForbricPlayer` |
| 11 | `ItemType` 的方块 / 堆肥 / 稀有度 / 属性查询 | `ForbricItemType` |
| 12 | 非物品注册表（`Registry.BLOCK`、`BIOME`、`ENCHANTMENT` …） | `ForbricRegistryAccess` |
| 13 | Paper 参数类型（34 个里的 33 个：`itemStack`、`blockState`、`world`、`entityType` …） | `ForbricArgumentTypes` |

### P2 —— 冷门或价值低

- `ForbricPlayer` 零碎项：统计、成就（`discoverRecipes`）、`sendEquipmentChange`、单人资源包、自定义聊天补全。
- `ForbricEntity` 的显示 / 粒子 / 乘客、记分板标签。
- `ServerImpl` 插件相关调用（`getPluginCommand`、`unload`、`reload`）、各种 `broadcast`。
- Paper `InternalAPIBridge` 杂项：伤害来源、战斗记录、POI 类型、皮肤部件、旧版游戏规则桥接、人偶档案。

---

## 3. 已知问题 / 粗糙处

| # | 问题 | 位置 | 说明 |
| --- | --- | --- | --- |
| B1 | `sendMessage(Component)` 丢样式 | `ForbricPlayer` | 已知限制，不是崩溃。修它就是 P0-1。 |
| B2 | `ForbricCommandSender.hasPermission` 是"存在性检查" | `ForbricCommandSender` | 控制台路径无害（`isOp()` 恒真），但语义不严谨；玩家发送者走的是正确路径。 |
| B3 | 插件桥关闭时不干净 | `BridgeBootstrap` | 退出时报 `java.lang.reflect.InvocationTargetException`。属于表面问题 —— 服务端仍能正常停止。未深入调查。 |
| B4 | 带计时的 `addAttachment(..., ticks)` 被拒绝 | `ForbricPlayer` | 需要调度器；不带计时的重载能用。 |
| B5 | `addEnchantment` / `getEnchantments` 被拒绝 | `ForbricItemStack` | 属于 P0-2（`ItemMeta`）的下游。 |

---

## 4. 本桥接层遵循的设计规则

在"顺手把方法实现了"之前，先看这几条：

1. **具名拒绝，绝不返回看着像对的错值。** 返回 `null` 或 `0` 的桩会把"这里还没实现"变成离案发现场很远的游戏性 bug。
2. **生成文件是整文件覆盖的。** `ForbricPlayer.java`、`ForbricWorld.java`、`ForbricEntity.java`、
   `ForbricBlock.java`、`ServerImpl.java` 由 `tools/gen_stub.py` 生成。只改 `.java` 的实现
   **下一次重新生成就没了** —— 必须写进 `tools/stubs/<Class>.java.in`，**并且**在 `.table` 里加条目。
   这一点已经害过一次：权限方法被打回 `notYet`，**玩家直接进不了服**（命令的权限判定是在建命令树时执行的，
   早于 `players.add`）。
3. **重新生成之后，把"拒绝方法集合"和 `HEAD` 对拍** —— 静默回退否则根本看不出来。见 §5。
4. **`notYet()` 是返回异常的方法，必须 `throw`。**
5. **`ForbricLog` 用 `String.format`（`%s`），不是 `{}`。**
6. **是实时视图，不是拷贝。** 句柄每次调用都读回游戏，其它东西改了插件也能立刻看到。
7. **不要留下从 API 类继承来、却没覆写的方法。** Paper 的 `ItemStack` 把每个方法都转发给私有的
   `craftDelegate`，而这里它是 null，所以一个没覆写的方法就是一个 `NullPointerException`，
   报的还是插件闻所未闻的字段名。要么覆写，要么具名拒绝 —— 漏掉 `isEmpty()` 那次直接崩了
   `/kit create`。自检命令：
   `javap -p -c org/bukkit/inventory/ItemStack.class | grep -B12 'getfield      #48'`
   列出的就是读它的全部方法。

---

## 5. 如何重新生成与验证

```sh
export JAVA_HOME=~/.local/share/hmcl/java/linux-x86_64/mojang-java-runtime-epsilon
export PATH=$JAVA_HOME/bin:$PATH
export MC_DIR="$HOME/桌面/.minecraft"          # 必须！否则会冒出约 100 个假的"程序包不存在"

cd forbric-kernel
./gradlew -q printBukkitApiClasspath > /tmp/cp.txt
python3 tools/gen_stub.py --table tools/stubs/ForbricPlayer.table --classpath "$(cat /tmp/cp.txt)"
```

然后确认没丢东西 —— 静默回退正是这一步要防的失败模式：

```bash
git show HEAD:forbric-kernel/src/runtime/java/org/cn/xiaofan/bukkit/ForbricPlayer.java > /tmp/old.java
# 拒绝集合 = 每处 `throw notYet(` 往上找最近的一行 `public ...(`
# 期望：(新 - 旧) 为空，除了本次新实现的方法
```

### 构建并部署

```sh
cd forbric-kernel && ./gradlew clean jar runtimeJar
cd ../forbric-kernel-installer && ./gradlew standaloneJar
cp build/libs/forbric-server.jar /mnt/A/forbirckit/forbric-server.jar
rm -rf /mnt/A/forbirckit/.forbric-kernel/kernel
rm -f  /mnt/A/forbirckit/.forbric-kernel/lib/*/forbric-kernel*.jar
```

服务端读的是 `.forbric-kernel/kernel/` 下的**缓存副本**，所以漏掉那两行 `rm` 就会跑旧 jar，白白浪费一轮测试。

---

## 6. 建议顺序

1. **P0-1 Adventure `Component` 桥** —— 用最少的新机制解锁最多插件（一对序列化/解析，聊天、动作栏、标题、书全都能复用）。
2. **P0-2 `ItemMeta`** —— 价值最高的对象；它需要的组件映射和 P0-1 是同一套，两个一起做能省一半工。
3. **P0-3 容器层** —— `InventoryView` + `openInventory` 让箱子类插件成为可能。
4. **P1-6/7 `World` + `Player` 玩法** —— 面广但浅，每个方法都是一次小映射。
5. **P2** 按真实插件的反馈来做，别凭空猜。

---

## 7. 诊断开关

| 属性 | 作用 |
| --- | --- |
| `-Dforbric.logCommands=off` | 停止记录每一个客户端命令包 |
| `-Dforbric.vanillaClient=off` | 停止在配置阶段接收非 NeoForge 客户端 |
| `-Dforbric.playPayloadFallThrough=off` | 游戏阶段的 payload 只交给 NeoForge 的派发器 |
| `-Dforbric.debug` | 打开 `ForbricLog.debug` 的详细跟踪 |
