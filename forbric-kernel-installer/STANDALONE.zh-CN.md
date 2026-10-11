# 独立服务端（`java -jar forbric-server.jar`）

一个文件，一条命令，服务端起得来。首次启动会自己把游戏本体造出来。

```bash
java -jar forbric-server.jar
```

就这样。世界、`mods/`、`plugins/` 都在当前目录，和原版服务端一样。

---

## 它会做什么

```
java -jar forbric-server.jar
  │
  ├─ 找 JDK 25+（先用当前 JVM，再找启动器的 runtime，最后找系统里的）
  │
  ├─ 检查 <game-dir>/.forbric-kernel/staged/run/
  │    ├─ 有 → 直接用（这就是"复用"）
  │    └─ 没有 → 造：
  │           下载 Minecraft 26.2 / MinecraftForge / NeoForge（公开源）
  │           → 给两边打 patch
  │           → 字节合并成 merged base
  │           → link-check 质量门
  │
  ├─ 下载 API pin（fabric-api、team reborn energy），校验 SHA-256
  │
  └─ 起子 JVM 跑服务端
```

第一次要几分钟（下载几百 MB + 合并），**之后每次都是秒起**。

---

## 为什么它要自己造游戏

merged base（合并基底）里嵌着 Mojang、MinecraftForge、NeoForge 的代码。**没有合法途径随包分发**。

所以一个能下载的 Forbric jar 只能**在你自己的机器上，从同样的公开源把它造出来**。这不是设计偷懒，是许可决定的。

同理，这个 jar 里**不含**：

- `net.minecraft.*`
- `net.minecraftforge.*`
- `net/neoforged.*`
- Paper API

`standaloneJar` 任务里有断言，一旦打进去就直接构建失败。

---

## 选项

```
--game-dir <dir>   世界 / mods / plugins / 缓存放哪（默认：当前目录）
--mc-dir <dir>     Minecraft 目录（含 libraries/ 和 versions/）（默认：= --game-dir）
--jdk <path>       用哪个 JDK 25+ 来构建和运行（默认：自动找）
--proxy <url>      下载用的 http(s) 代理（默认：读环境变量）
--help             帮助
```

`--` 之后的所有参数原样传给服务端。

例子：

```bash
# 世界放别处，Minecraft 装在默认位置
java -jar forbric-server.jar --game-dir ~/my-server --mc-dir ~/.minecraft

# 传服务端参数（端口）
java -jar forbric-server.jar -- --port 25566 --nogui
```

`--nogui` 会自动加上（除非你已经写了）。

---

## 目录布局

```
<game-dir>/
├── mods/                          ← Fabric / Forge / NeoForge 模组
├── plugins/                       ← Bukkit / Paper 插件
├── world/                         ← 世界
├── server.properties
├── eula.txt                       ← 首次要写 eula=true
└── .forbric-kernel/               ← Forbric 的工作目录
    ├── staged/run/                ← 游戏本体（造一次，复用）
    │   ├── merged-base/patched-mc-merged-26.2.jar
    │   ├── merged-base/forge-runtime-interop.jar
    │   └── neoforge-runtime/neoforge-runtime.jar
    ├── libs/                      ← API pin（fabric-api、energy）
    ├── kernel/                     ← 从 jar 里解出来的内核
    └── natives/

<mc-dir>/.forbric-build/            ← 构建中间产物（Forge/NeoForge 的 patch 与合并）
```

**删掉 `<game-dir>/.forbric-kernel/` = 强制重建游戏本体。**
删 `<mc-dir>/.forbric-build/` 则会连中间产物一起丢掉，重新下载。

---

## 复用已有产物

`dev.py prepare` 造出来的东西就是同一套布局。所以：

```bash
# 已经跑过 dev.py prepare 的机器，可以直接指过去
java -jar forbric-server.jar --game-dir forbric-kernel/.dev/server --mc-dir ~/桌面/.minecraft
```

它会发现 `staged/run/` 已经齐了，跳过构建，直接启动。

反过来也成立：`standaloneJar` 造出来的产物，`dev.py` 也认。

---

## 服务端参数

```
--nogui            不开图形控制台（已默认加）
--port <n>         端口（默认 25565）
--serverIp <ip>    绑定地址
--world <dir>      世界目录名
--bonusChest       新世界送箱子
--forceUpgrade     强制升级世界格式
```

不确定的用 `--help`，或者直接跑一次看输出。

---

## 退出码

子进程的退出码**原样透传**。所以：

```bash
java -jar forbric-server.jar && echo "正常退出" || echo "异常退出 ($?)"
```

行为和其他服务端一致。

---

## 常见问题

**首次运行合并失败，报 `Incompatible initializer dependency on ...ChunkGenerator#featuresPerStep`**
→ 现在不应该再出现了：启动器会先下 Minecraft 的 library 再合并，而合并要用它们解析外部类型（空的 `libraries/` 就是当时的原因，而报错信息两者都没提）。如果还是看到它，说明下载那一步没跑完——`[minecraft] 68 libraries ready` 这行是它跑完了的证据，且它必须出现在 `== merging ==` **之前**。代理只起了一半作用是最常见的原因。

**`no Java 25 or newer was found`**
→ 装个 JDK 25，或者 `--jdk /path/to/jdk25`。也可以先启动一次 Minecraft 26.2，让启动器把 runtime 下下来。

**首次启动卡在 "merging"**
→ 正常。合并 88 MB 的字节码要一两分钟，输出里有 `[merge]` 前缀的进度。

**下载卡住 / `maven.neoforged.net` 连不上**
→ 需要代理，启动器会自己读：

```bash
export https_proxy=http://127.0.0.1:7890
java -jar forbric-server.jar
```

`https_proxy` / `http_proxy` / `all_proxy`（及其大写形式）都会读，也可以用 `--proxy <url>` 显式指定。启动器会把它变成自己的系统属性、变成每个子进程的环境变量，并打印出来：

```
[forbric] using the proxy at 127.0.0.1:7890 (from https_proxy)
```

**这一点比看起来重要。** 构建过程会 fork 出好几个工具进程；它们不继承父进程的属性，所以只用 `-D` 传的代理只能到父进程，到不了它们。而且 `-D` 写在 `-jar` 后面完全无效，因为 `-jar` 是终结符：

```bash
java -jar -Dhttps.proxyHost=... forbric-server.jar   # ❌ 被当成程序参数
java -Dhttps.proxyHost=... -jar forbric-server.jar   # ✅ 有效，但到不了子进程
```

**构建失败，报 `merge-tools.jar` 找不到**
→ 这个 jar 打漏了，是构建问题。重新 `./gradlew standaloneJar`。

**想关掉 Bukkit 插件桥**
→ `-Dforbric.bukkitBridge=off`。

---

## 从源码构建

```bash
cd forbric-kernel-installer
M=$(pwd)/..

./gradlew standaloneJar \
  -Pforbric.stagedRoot=$M/forbric-kernel/.dev/staged/run \
  -Pforbric.mcLibraries=$HOME/.minecraft/libraries \
  -Pforbric.fabricApi=$M/forbric-kernel/.dev/api/fabric-api-0.155.2+26.2.jar \
  -Pforbric.rebornEnergy=$M/forbric-kernel/.dev/api/energy-5.0.0.jar
```

产物：`build/libs/forbric-server.jar`（约 8.5 MB）

那四个 `-P` 是 kernel 的游戏侧编译需要的（`forbric.stagedRoot` 等）；`buildForbricJars` 会把它们透传给 kernel 的构建。

---

## 架构说明（为什么是子进程）

`java -jar` 只把**一个** jar 放上 classpath。但一次内核启动需要：

- merged base（游戏本体）
- MC 的 71 个 library jar
- 内核与它的 boot 依赖

而 `DelegationPolicy.ALWAYS_PARENT` 要求某些内核类**必须**从系统加载器来（ASM、Mixin、log4j、vendored Fabric API）。自建 `URLClassLoader` 会把这些定义到别处，内核就同时持有两份自己赖以运转的类——正是它 boot/game 分离要防的事。

子 JVM 天然拿到正确的 classpath，给不错。代价是多一个进程，而启动器本来就是个进程。
