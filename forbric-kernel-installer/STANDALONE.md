# Standalone server (`java -jar forbric-server.jar`)

One file, one command, a server that runs. The first launch builds the game itself.

```bash
java -jar forbric-server.jar
```

That is all. The world, `mods/` and `plugins/` live in the current directory, exactly like a vanilla server.

---

## What it does

```
java -jar forbric-server.jar
  │
  ├─ find a JDK 25+ (this JVM first, then the launcher's runtime, then the system's)
  │
  ├─ check <game-dir>/.forbric-kernel/staged/run/
  │    ├─ present → use it (this is the "reuse" path)
  │    └─ absent  → build it:
  │           download Minecraft 26.2 / MinecraftForge / NeoForge (public sources)
  │           → patch both sides
  │           → byte-merge into the merged base
  │           → link-check gate
  │
  ├─ download the API pins (fabric-api, team reborn energy), verify SHA-256
  │
  └─ start a child JVM on the server
```

The first run takes a few minutes (several hundred MB of downloads plus the merge). **Every run after that is
seconds.**

---

## Why it has to build the game

The merged base embeds Mojang's, MinecraftForge's and NeoForge's code. There is **no lawful way to
redistribute it.**

So a downloadable Forbric jar can only **build it on your own machine, from the same public sources**. That is
not a shortcut in the design; it is what the licences allow.

For the same reason this jar contains **none** of:

- `net.minecraft.*`
- `net.minecraftforge.*`
- `net/neoforged.*`
- the Paper API

The `standaloneJar` task asserts each of those, and fails the build if one appears.

---

## Options

```
--game-dir <dir>   where the world, mods, plugins and caches live (default: .)
--mc-dir <dir>     the Minecraft directory holding libraries/ and versions/ (default: <game-dir>)
--jdk <path>       a Java 25+ launcher or JDK home to build and run with (default: auto-detected)
--help             this text
```

Everything after `--` is passed to the server unchanged.

```bash
# world elsewhere, Minecraft in its default place
java -jar forbric-server.jar --game-dir ~/my-server --mc-dir ~/.minecraft

# server options (port)
java -jar forbric-server.jar -- --port 25566 --nogui
```

`--nogui` is added automatically unless you write it yourself.

---

## Directory layout

```
<game-dir>/
├── mods/                          ← Fabric / Forge / NeoForge mods
├── plugins/                       ← Bukkit / Paper plugins
├── world/                         ← the world
├── server.properties
├── eula.txt                       ← write eula=true once
└── .forbric-kernel/               ← Forbric's working directory
    ├── staged/run/                ← the game (built once, reused)
    │   ├── merged-base/patched-mc-merged-26.2.jar
    │   ├── merged-base/forge-runtime-interop.jar
    │   └── neoforge-runtime/neoforge-runtime.jar
    ├── libs/                      ← the API pins (fabric-api, energy)
    ├── kernel/                    ← the kernel, unpacked from this jar
    └── natives/

<mc-dir>/.forbric-build/            ← build intermediates (the Forge/NeoForge patches and the merge)
```

**Deleting `<game-dir>/.forbric-kernel/` forces a rebuild of the game.**
Deleting `<mc-dir>/.forbric-build/` throws away the intermediates too, and they are downloaded again.

---

## Reusing what you already have

`dev.py prepare` produces exactly the same layout. So:

```bash
# a machine that has already run dev.py prepare can point straight at it
java -jar forbric-server.jar --game-dir forbric-kernel/.dev/server --mc-dir ~/.minecraft
```

It finds `staged/run/` already complete, skips the build, and starts. The reverse holds too: what
`standaloneJar` produces, `dev.py` accepts.

---

## Server options

```
--nogui            no graphical console (added by default)
--port <n>         port (default 25565)
--serverIp <ip>    bind address
--world <dir>      world directory name
--bonusChest       a bonus chest on a new world
--forceUpgrade     force the world format upgrade
```

Run with `--help`, or just start it once and read the output.

---

## Exit codes

The child's exit code is passed through unchanged, so:

```bash
java -jar forbric-server.jar && echo "clean exit" || echo "failed ($?)"
```

It behaves like any other server.

---

## Common problems

**The first run fails at the merge with `Incompatible initializer dependency on ...ChunkGenerator#featuresPerStep`**
→ This build does not yet download Minecraft's libraries before merging, and the merge needs them to resolve
external types. Point `--mc-dir` at a directory with **Minecraft 26.2 installed** (it must have `libraries/`):

```bash
java -jar forbric-server.jar --mc-dir ~/.minecraft
```

`--game-dir` still holds your world and mods; the two may differ. A directory that has already run
`dev.py prepare` also qualifies. **This is a known defect and the next release fixes it** (by having
`ServerBootstrap` download the libraries itself).

**`no Java 25 or newer was found`**
→ Install a JDK 25, or pass `--jdk /path/to/jdk25`. Starting Minecraft 26.2 once also works: the launcher
then downloads its own runtime.

**The first run sits at "merging"**
→ Normal. Merging 88 MB of bytecode takes a minute or two; the progress lines carry a `[merge]` prefix.

**Downloads hang, or `maven.neoforged.net` is unreachable**
→ You need a proxy. Use `JAVA_TOOL_OPTIONS`: **every** JVM reads it, including the tool processes the build
forks:

```bash
export JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890 \
                          -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890"
java -jar forbric-server.jar
```

**`-D` after `-jar` does nothing** — `-jar` is a terminator, and everything after it is a program argument:

```bash
java -jar -Dhttps.proxyHost=... forbric-server.jar   # ❌ no proxy
java -Dhttps.proxyHost=... -jar forbric-server.jar   # ✅
```

And the parent's `-D` is **not** inherited by the child processes the build forks, which is why
`JAVA_TOOL_OPTIONS` is more reliable than `-D`.

**The build fails with "merge-tools.jar not found"**
→ The jar is missing from the launcher, which is a build problem. Re-run `./gradlew standaloneJar`.

**Turn off the Bukkit plugin bridge**
→ `-Dforbric.bukkitBridge=off`.

---

## Building it from source

```bash
cd forbric-kernel-installer
M=$(pwd)/..

./gradlew standaloneJar \
  -Pforbric.stagedRoot=$M/forbric-kernel/.dev/staged/run \
  -Pforbric.mcLibraries=$HOME/.minecraft/libraries \
  -Pforbric.fabricApi=$M/forbric-kernel/.dev/api/fabric-api-0.155.2+26.2.jar \
  -Pforbric.rebornEnergy=$M/forbric-kernel/.dev/api/energy-5.0.0.jar
```

Output: `build/libs/forbric-server.jar` (about 8.5 MB)

Those four `-P` are what the kernel's game side needs to compile (`forbric.stagedRoot` and friends);
`buildForbricJars` passes them through to the kernel build.

---

## Why a child process

`java -jar` puts exactly **one** jar on the classpath. But a kernel launch needs:

- the merged base (the game)
- Minecraft's 71 library jars
- the kernel and its boot dependencies

and `DelegationPolicy.ALWAYS_PARENT` requires some kernel classes to come from the **system** loader (ASM,
Mixin, log4j, the vendored Fabric API). Standing up a `URLClassLoader` would define those somewhere else, and
the kernel would then hold two copies of the classes it is built around — exactly the failure its boot/game
split exists to prevent.

A child JVM gets the right classpath for free and cannot be got wrong. The cost is one more process, and a
launcher is a process anyway.
