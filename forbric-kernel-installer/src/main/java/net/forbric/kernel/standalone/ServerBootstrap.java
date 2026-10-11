/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.standalone;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import net.forbric.installer.kernel.InstallerFacade;

/**
 * The entry point of the standalone server jar: {@code java -jar forbric-server.jar}.
 *
 * <p>Everything a Forbric launch needs is already in this jar <em>except the game</em>. Minecraft's own code and
 * the three Forge-family artifacts that wrap it embed Mojang's and the loaders' work, so no legal distribution
 * can carry them; they have to be built on the machine that runs the server, from the same public sources the
 * installer uses. That is what makes this a launcher rather than a fat jar: its first real act is asking
 * {@link InstallerFacade} to produce the game (reusing anything already built), and its last is starting another
 * JVM with the classpath a kernel launch expects.
 *
 * <h2>Why a child process, and not a class loader</h2>
 *
 * <p>{@code java -jar} puts exactly one jar on the classpath — this one. But a kernel launch needs Minecraft's
 * 71 libraries and the merged base beside the boot jar, and {@code DelegationPolicy} requires some kernel
 * classes to come from the SYSTEM loader (its {@code ALWAYS_PARENT} list: ASM, Mixin, log4j, the vendored Fabric
 * API). Standing up a URLClassLoader would define those somewhere else, and the kernel would then hold two copies
 * of the classes it is built around — the exact failure its boot/game split exists to prevent. A child JVM gets
 * the right classpath for free and cannot be got wrong.
 *
 * <p>The cost is one more process, which is what a launcher is anyway. The child's exit code is passed through
 * unchanged, so {@code echo $?} after a server run still means what it would mean for any other server.
 */
public final class ServerBootstrap {

	/** Where the game artifacts and the API pins are cached, relative to the game directory. */
	private static final String CACHE = ".forbric-kernel";

	/** The class the child JVM is started on. Named as a string so this jar need not carry the kernel on its
	 *  compile path — it carries it as bytes instead (see {@link #nestedBootJar}). */
	private static final String LAUNCH = "net.forbric.kernel.boot.KernelServerLaunch";

	/**
	 * The kernel jars this launcher carries inside itself, unpacked before the child starts.
	 *
	 * <p>Written under {@code META-INF/forbric/} at build time by the {@code standaloneJar} task, which copies the
	 * boot jar and the two API pins in beside this class. They are bytes rather than a compile dependency because
	 * the installer module does not and should not depend on the kernel module: the kernel is the thing that gets
	 * launched, and a launcher that has to be rebuilt whenever the kernel's API changes is not a launcher.
	 */
	private static final String NESTED = "META-INF/forbric/";

	/** The API artifacts a launch needs on the classpath, at the pins {@code dev.py} uses. */
	private record ApiPin(String name, String url, String sha256) {
	}

	private static final List<ApiPin> API_PINS = List.of(
			new ApiPin("fabric-api-0.155.2+26.2.jar",
					"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/0.155.2+26.2/"
							+ "fabric-api-0.155.2+26.2.jar",
					"d6518c770024cbe8a556248f16fcdbb91c6a62f50227a6c3bae8190511e2c1b8"),
			new ApiPin("energy-5.0.0.jar", "https://maven.modmuss50.me/teamreborn/energy/5.0.0/energy-5.0.0.jar",
					"889afc438d3e4add5cfdac76517da7987a2c495e4731690a56f2c5dee775db59"));

	public static void main(String[] args) throws Exception {
		Options options = Options.parse(args);
		if (options.help) {
			System.out.println(USAGE);
			return;
		}

		// Before anything that opens a socket, and before any HttpClient is built. The installer's Http class
		// constructs its client from the JVM's default ProxySelector, which reads -Dhttp(s).proxyHost/Port — not
		// the http_proxy/https_proxy environment variables a shell exports. `dev.py` worked around this by
		// running under JDK_JAVA_OPTIONS; a user typing `java -jar forbric-server.jar` has no such help, and
		// every download then goes DIRECT and dies on maven.neoforged.net, which is unreachable without a proxy
		// on some networks (it resolves, then times out: the failure looks like "offline?" and retries).
		Proxy proxy = Proxy.fromEnvironment(options.proxy);
		proxy.applyToSystemProperties();
		if (proxy.enabled()) {
			System.out.println("[forbric] using the proxy at " + proxy.host() + ":" + proxy.port()
					+ " (from " + proxy.source() + ")");
		}

		Path gameDir = options.gameDir.toAbsolutePath().normalize();
		Path mcDir = options.mcDir.toAbsolutePath().normalize();
		Path cache = gameDir.resolve(CACHE);
		Files.createDirectories(gameDir);
		Files.createDirectories(cache);

		System.out.println("[forbric] game directory:  " + gameDir);
		System.out.println("[forbric] Minecraft directory: " + mcDir);
		System.out.println();

		// ---- the JVM the game will run under ----
		// Located BEFORE the artifacts, because building them needs one too, and the user should learn about a
		// missing JDK in the first second rather than after a merge.
		InstallerFacade.Jvm jvm = InstallerFacade.locateJvm(mcDir, options.jdk, line -> System.out.println("[jdk] " + line));

		// ---- the game itself ----
		Path stage = cache.resolve("staged/run");
		boolean staged = isStaged(stage);
		if (!staged) {
			System.out.println();
			System.out.println("[forbric] no staged game artifacts under " + stage);
			System.out.println("[forbric] building them now. This happens once: the merge takes a few minutes and "
					+ "downloads Minecraft, MinecraftForge and NeoForge from their public sources.");
			System.out.println();
			Map<String, Path> artifacts = InstallerFacade.prepareGame(mcDir, stage, System.out::println);
			System.out.println();
			System.out.println("[forbric] game artifacts ready:");
			artifacts.forEach((name, path) -> System.out.println("[forbric]   " + name + " -> " + path));
		} else {
			System.out.println("[forbric] reusing the game artifacts already staged under " + stage);
			System.out.println("[forbric] (delete that directory to force a rebuild)");
		}
		System.out.println();

		// ---- the API pins, and everything else the child classpath is built from ----
		Path libs = cache.resolve("libs");
		List<Path> apiJars = new ArrayList<>();
		for (ApiPin pin : API_PINS) apiJars.add(fetch(pin, libs));

		Path bootJar = unpackBootJar(cache.resolve("kernel"));
		List<Path> minecraftLibraries = minecraftLibraries(mcDir, options.mcDirExplicit)
				? mcLibraries(mcDir)
				: List.of();

		// A metadata-only jar beside the merged base: system-classloader consumers (log4j config, the Fabric
		// loader's version probe) read version.json, but the game's own bytecode must never be on the parent
		// classpath. dev.py writes exactly this jar for exactly this reason.
		Path metadata = writeGameMetadata(stage);

		// The kernel's own boot dependencies (ASM, Mixin, sat4j, NightConfig, the mapping and tweaker libraries),
		// unpacked from this jar beside the kernel. They are nested rather than resolved, for the same reason the
		// kernel is: a launcher that has to find them at run time is one that can fail to.
		List<Path> kernelDependencies = unpackNested(cache.resolve("kernel/dependencies"));

		// ---- the child ----
		List<String> classpath = new ArrayList<>();
		classpath.add(bootJar.toString());
		for (Path jar : kernelDependencies) classpath.add(jar.toString());
		for (Path jar : apiJars) classpath.add(jar.toString());
		for (Path jar : minecraftLibraries) classpath.add(jar.toString());
		classpath.add(metadata.toString());

		Path stageMerged = stage.resolve("merged-base/patched-mc-merged-26.2.jar");
		String owned = String.join(java.io.File.pathSeparator,
				stageMerged.toString(),
				stage.resolve("merged-base/forge-runtime-interop.jar").toString(),
				stage.resolve("neoforge-runtime/neoforge-runtime.jar").toString());

		List<String> command = new ArrayList<>();
		command.add(jvm.javaBin().toString());
		// Headless and the natives path are what dev.py passes for a server; without the first, any plugin that
		// touches AWT drags in a display, and without the second the game's native libraries cannot load.
		command.add("-Djava.awt.headless=true");
		command.add("-Djava.library.path=" + nativeDirectory(cache));
		command.add("-Dforbric.compatibilityPolicy=" + System.getProperty("forbric.compatibilityPolicy", "strict"));
		command.add("-Dforbric.dependencyDialog=off");
		// The kernel needs a large heap for the merge's own bookkeeping; the merge runs as a child of THIS process
		// during prepare, so what matters here is only what the game needs. Passed through so a user can raise it.
		String heap = System.getProperty("forbric.heap");
		if (heap != null && !heap.isBlank()) command.add("-Xmx" + heap);
		command.add("-cp");
		command.add(String.join(java.io.File.pathSeparator, classpath));
		command.add(LAUNCH);
		command.add("--gameJar");
		command.add(stageMerged.toString());
		command.add("--runtimeJar");
		command.add(stage.resolve("merged-base/forge-runtime-interop.jar") + java.io.File.pathSeparator
				+ stage.resolve("neoforge-runtime/neoforge-runtime.jar"));
		command.add("--libraryPath");
		command.add(owned);
		command.add("--");
		command.add("--gameDir");
		command.add(gameDir.toString());
		command.addAll(options.gameArguments);
		// --nogui unless the user asked for the console: the standalone jar is started from a terminal, and
		// Minecraft's own GUI console would need a display that a headless host does not have.
		if (options.gameArguments.stream().noneMatch(a -> a.equals("--nogui") || a.equals("gui"))) {
			command.add("--nogui");
		}

		System.out.println("[forbric] starting the server" + (options.gameArguments.isEmpty() ? "" : " ("
				+ String.join(" ", options.gameArguments) + ")"));
		System.out.println();
		Process child = new ProcessBuilder(command).inheritIO().start();
		int code = child.waitFor();
		if (code != 0) System.exit(code);
	}

	/** The natives directory: a sibling of the caches, filled by {@code dev.py prepare} when one ran. */
	private static Path nativeDirectory(Path cache) {
		// The launcher never extracts natives itself — Minecraft's own libraries unpack their .so/.dll at load
		// on modern versions, and dev.py's flattening exists for the bundled-launcher path. An empty directory is
		// therefore normal rather than an error; the property has to be set, not to point at populated natives.
		Path natives = cache.resolve("natives");
		try {
			Files.createDirectories(natives);
		} catch (IOException ignored) {
			// A read-only game directory still starts; only the property's value is lost.
		}
		return natives;
	}

	/** Every entry under {@code META-INF/forbric/kernel-deps/}, written to {@code dir} once. */
	private static List<Path> unpackNested(Path dir) throws IOException {
		Files.createDirectories(dir);
		List<Path> jars = new ArrayList<>();
		try (JarFile self = new JarFile(selfLocation().toFile())) {
			for (java.util.Enumeration<java.util.jar.JarEntry> entries = self.entries(); entries.hasMoreElements(); ) {
				java.util.jar.JarEntry entry = entries.nextElement();
				String name = entry.getName();
				if (!name.startsWith(NESTED + "kernel-deps/") || !name.endsWith(".jar")) continue;
				Path target = dir.resolve(name.substring((NESTED + "kernel-deps/").length()));
				if (!Files.isRegularFile(target) || Files.size(target) != entry.getSize()) {
					try (InputStream in = self.getInputStream(entry)) {
						Path temporary = target.resolveSibling(target.getFileName() + ".part");
						Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
						Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
					}
				}
				jars.add(target);
			}
		}
		if (jars.isEmpty()) throw new IOException("this launcher carries no kernel dependencies under " + NESTED
				+ "kernel-deps/ — it was built without the standaloneJar task");
		return jars;
	}

	private static Path selfLocation() {
		try {
			return Path.of(ServerBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		} catch (java.net.URISyntaxException impossible) {
			throw new IllegalStateException("this jar's own location is a URI", impossible);
		}
	}

	private static final String USAGE = """
			Forbric standalone server

			usage: java -jar forbric-server.jar [options] [-- <game arguments>]

			  --game-dir <dir>   where the world, mods, plugins and caches live (default: .)
			  --mc-dir <dir>     the Minecraft directory holding libraries/ and versions/ (default: <game-dir>)
			  --jdk <path>       a Java 25+ launcher or JDK home to build and run with (default: auto-detected)
			  --help             this text

			Everything after -- is passed to the dedicated server unchanged (e.g. -- --port 25566 --nogui).
			""";

	/** The parsed command line. A record so the launch path cannot read a field {@link #parse} did not set. */
	private record Options(Path gameDir, Path mcDir, boolean mcDirExplicit, Path jdk, boolean help,
			List<String> gameArguments) {

		static Options parse(String[] args) {
			Path gameDir = Path.of(System.getProperty("user.dir", "."));
			Path mcDir = null;
			Path jdk = null;
			boolean help = false;
			List<String> game = new ArrayList<>();
			boolean afterSeparator = false;
			for (int i = 0; i < args.length; i++) {
				if (afterSeparator) {
					game.add(args[i]);
					continue;
				}
				switch (args[i]) {
					// `--` hands the rest to the game. Minecraft's own flags are the ones after it, so they are
					// also accepted bare: a user typing `--port 25566` without the separator means the game.
					case "--" -> afterSeparator = true;
					case "--help", "-h" -> help = true;
					case "--game-dir" -> gameDir = Path.of(requireValue(args, ++i, "--game-dir"));
					case "--mc-dir" -> mcDir = Path.of(requireValue(args, ++i, "--mc-dir"));
					case "--jdk" -> jdk = Path.of(requireValue(args, ++i, "--jdk"));
					default -> {
						if (args[i].startsWith("--") && looksLikeServerFlag(args[i])) {
							game.add(args[i]);
						} else if (args[i].startsWith("--")) {
							throw new IllegalArgumentException("unknown option: " + args[i] + "\n\n" + USAGE);
						} else {
							game.add(args[i]);
						}
					}
				}
			}
			return new Options(gameDir, mcDir != null ? mcDir : gameDir, mcDir != null, jdk, help, game);
		}

		/** Flags the dedicated server understands, which a launcher must forward rather than reject. */
		private static boolean looksLikeServerFlag(String argument) {
			return switch (argument) {
				case "--nogui", "--port", "--serverIp", "--bonusChest", "--demo", "--forceUpgrade", "--eraseCache",
						"--safeMode", "--singleplayer", "--universe", "--world", "--width", "--height" -> true;
				default -> false;
			};
		}

		private static String requireValue(String[] args, int index, String flag) {
			if (index >= args.length) throw new IllegalArgumentException(flag + " needs a value");
			return args[index];
		}
	}

	/** True when every file a launch names is already staged and non-trivial. */
	private static boolean isStaged(Path stage) {
		for (String relative : new String[] {
				"merged-base/patched-mc-merged-26.2.jar",
				"merged-base/forge-runtime-interop.jar",
				"neoforge-runtime/neoforge-runtime.jar"}) {
			Path path = stage.resolve(relative);
			if (!Files.isRegularFile(path)) return false;
		}
		return true;
	}

	private static boolean minecraftLibraries(Path mcDir, boolean explicit) {
		return Files.isDirectory(mcDir.resolve("libraries"));
	}

	/** Minecraft's own libraries, in the order its version JSON lists them. */
	private static List<Path> mcLibraries(Path mcDir) throws IOException {
		// Reuse the version JSON the game itself ships: its `libraries` list is the authoritative classpath, and
		// re-deriving it from Maven coordinates would drift from what the launcher would have used.
		Path json = mcDir.resolve("versions/26.2/26.2.json");
		if (!Files.isRegularFile(json)) throw new IOException("no " + json + " — run the Minecraft launcher for 26.2 once, "
				+ "or pass --mc-dir pointing at a directory that has it");
		List<Path> jars = new ArrayList<>();
		// A JSON reader would be a dependency; this launcher carries none. The paths are `downloads.artifact.path`
		// strings, and every one of them ends in `.jar`. A regex over the text is enough and cannot silently
		// produce a wrong path the way a hand-rolled parser could.
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("\"path\"\\s*:\\s*\"([^\"]+\\.jar)\"").matcher(Files.readString(json));
		while (matcher.find()) {
			Path jar = mcDir.resolve("libraries").resolve(matcher.group(1));
			if (Files.isRegularFile(jar)) jars.add(jar);
		}
		// The dedicated console uses jline, which 26.2's own metadata omits.
		Path jline = mcDir.resolve("libraries/org/jline");
		if (Files.isDirectory(jline)) {
			try (var walk = Files.walk(jline)) {
				walk.filter(p -> p.getFileName().toString().matches("jline-.*-3\\.25\\.1\\.jar"))
						.forEach(jars::add);
			}
		}
		if (jars.isEmpty()) throw new IOException("no Minecraft libraries found under " + mcDir
				+ "/libraries — run the Minecraft launcher for 26.2 once");
		return jars;
	}

	/**
	 * Writes {@code version.json} alone into a tiny jar beside the merged base.
	 *
	 * <p>System-classloader consumers need the game's version metadata, but the game itself must not be on the
	 * parent classpath — a {@code net.minecraft} class defined by the system loader would be a second copy of the
	 * one {@code ForbricClassLoader} is about to define. One entry in one jar answers both.
	 */
	private static Path writeGameMetadata(Path stage) throws IOException {
		Path merged = stage.resolve("merged-base/patched-mc-merged-26.2.jar");
		Path metadata = stage.resolve("merged-base/forbric-game-metadata.jar");
		byte[] version;
		try (JarFile jar = new JarFile(merged.toFile())) {
			ZipEntry entry = jar.getEntry("version.json");
			if (entry == null) throw new IOException(merged + " has no version.json");
			try (InputStream in = jar.getInputStream(entry)) {
				version = in.readAllBytes();
			}
		}
		try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(metadata))) {
			out.putNextEntry(new ZipEntry("version.json"));
			out.write(version);
			out.closeEntry();
		}
		return metadata;
	}

	/** The boot jar, unpacked from this jar's own resources on first use. */
	private static Path unpackBootJar(Path dir) throws IOException {
		Files.createDirectories(dir);
		Path target = dir.resolve("forbric-kernel.jar");
		if (Files.isRegularFile(target)) return target;
		try (InputStream in = ServerBootstrap.class.getResourceAsStream("/" + NESTED + "forbric-kernel.jar")) {
			if (in == null) throw new IOException("this launcher carries no kernel at " + NESTED
					+ "forbric-kernel.jar — it was built without the standaloneJar task");
			Path temporary = target.resolveSibling(target.getFileName() + ".part");
			try (OutputStream out = Files.newOutputStream(temporary)) {
				in.transferTo(out);
			}
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	/** Downloads one pinned artifact if absent, and refuses to use it if the digest is not the pinned one. */
	private static Path fetch(ApiPin pin, Path dir) throws IOException {
		Files.createDirectories(dir);
		Path target = dir.resolve(pin.name());
		if (!Files.isRegularFile(target) || !pin.sha256().equalsIgnoreCase(digest(target))) {
			System.out.println("[forbric] downloading " + pin.name());
			Path temporary = target.resolveSibling(target.getFileName() + ".part");
			try (InputStream in = java.net.URI.create(pin.url()).toURL().openStream()) {
				Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
			}
			if (!pin.sha256().equalsIgnoreCase(digest(temporary))) {
				Files.deleteIfExists(temporary);
				throw new IOException(pin.name() + " did not match its pinned SHA-256; refusing to use it");
			}
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}

	private static String digest(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			byte[] buffer = new byte[1 << 16];
			for (int read; (read = in.read(buffer)) > 0; ) sha.update(buffer, 0, read);
			return HexFormat.of().formatHex(sha.digest());
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("every JVM has SHA-256", impossible);
		}
	}
}
