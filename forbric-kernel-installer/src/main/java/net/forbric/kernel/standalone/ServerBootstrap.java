/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.standalone;

import java.io.IOException;
import java.io.InputStream;
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

		// ---- Minecraft's own libraries ----
		// BEFORE the artifacts, and this ordering is load-bearing: the merge resolves external types through
		// them. Handed an empty libraries/ it fails on ChunkGenerator#featuresPerStep with "Incompatible
		// initializer dependency" — a class it can read perfectly well — which names neither libraries nor the
		// platform. A machine with no Minecraft install used to be unable to build a server at all.
		List<Path> minecraftLibraries = mcLibraries(mcDir, proxy);

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
		ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
		// The child downloads the API pins' siblings and whatever a mod fetches at runtime; it inherits neither
		// this JVM's properties nor, on some networks, a working route without them.
		proxy.applyTo(builder);
		Process child = builder.start();
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
			  --proxy <url>      an http(s) proxy for the downloads, e.g. http://127.0.0.1:7890
			                     (default: http_proxy / https_proxy from the environment)
			  --help             this text

			Everything after -- is passed to the dedicated server unchanged (e.g. -- --port 25566 --nogui).
			""";

	/** The parsed command line. A record so the launch path cannot read a field {@link #parse} did not set. */
	private record Options(Path gameDir, Path mcDir, Path jdk, String proxy, boolean help,
			List<String> gameArguments) {

		static Options parse(String[] args) {
			Path gameDir = Path.of(System.getProperty("user.dir", "."));
			Path mcDir = null;
			Path jdk = null;
			String proxy = null;
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
					case "--proxy" -> proxy = requireValue(args, ++i, "--proxy");
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
			return new Options(gameDir, mcDir != null ? mcDir : gameDir, jdk, proxy, help, game);
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

	/**
	 * A proxy for every download this launcher makes, and for the ones its children make.
	 *
	 * <p>{@link net.forbric.installer.kernel.Http} builds its client from the JVM's default {@code ProxySelector},
	 * which reads the {@code http(s).proxyHost/Port} properties — not the {@code http_proxy}/{@code https_proxy}
	 * variables a shell exports. So a launcher started as {@code java -jar} has no proxy unless the user knew to
	 * pass {@code -D} (and put it before {@code -jar}, which is a terminator). Reading the variables here is what
	 * makes "one file, one command" true on a network that needs one.
	 *
	 * <p>{@link #applyToEnvironment} is not redundant with {@link #applyToSystemProperties}: a build tool the
	 * installer forks reads neither, but several of them read the environment, and Java children inherit both.
	 */
	record Proxy(String host, int port, String source) {

		private static final String[] HTTPS_VARIABLES = {"https_proxy", "HTTPS_PROXY"};
		private static final String[] HTTP_VARIABLES = {"http_proxy", "HTTP_PROXY", "all_proxy", "ALL_PROXY"};

		/** The explicit option first, then the environment. Never a proxy at all when neither names one. */
		static Proxy fromEnvironment(String explicit) {
			String value = explicit;
			String source = "--proxy";
			if (value == null) {
				// https first: it is the one the failing downloads need, and a machine may set only that.
				for (String name : HTTPS_VARIABLES) {
					if (System.getenv(name) != null && !System.getenv(name).isBlank()) {
						value = System.getenv(name);
						source = name;
						break;
					}
				}
			}
			if (value == null) {
				for (String name : HTTP_VARIABLES) {
					if (System.getenv(name) != null && !System.getenv(name).isBlank()) {
						value = System.getenv(name);
						source = name;
						break;
					}
				}
			}
			if (value == null) return new Proxy(null, 0, "none");
			return parse(value, source);
		}

		/**
		 * Accepts the shapes a shell actually holds: {@code http://host:port}, {@code host:port},
		 * {@code //host:port} (what a browser's copy-paste produces), and the proxy variables no scheme.
		 */
		private static Proxy parse(String raw, String source) {
			String value = raw.trim();
			int scheme = value.indexOf("://");
			if (scheme >= 0) value = value.substring(scheme + 3);
			if (value.startsWith("//")) value = value.substring(2);
			// Credentials are not supported and, more importantly, must not be silently dropped: a proxy that
			// needs them would fail at every request with a confusing 407 rather than at startup.
			if (value.contains("@")) value = value.substring(value.lastIndexOf('@') + 1);
			int colon = value.lastIndexOf(':');
			if (colon < 0) throw new IllegalArgumentException(source + " names no port: " + raw
					+ " (expected e.g. http://127.0.0.1:7890)");
			String host = value.substring(0, colon);
			String port = value.substring(colon + 1);
			int slash = port.indexOf('/');
			if (slash >= 0) port = port.substring(0, slash);
			try {
				return new Proxy(host, Integer.parseInt(port), source);
			} catch (NumberFormatException notAPort) {
				throw new IllegalArgumentException(source + " has a non-numeric port: " + raw, notAPort);
			}
		}

		boolean enabled() {
			return host != null;
		}

		/** Both schemes: HttpClient does not carry an http proxy over to https requests. */
		void applyToSystemProperties() {
			if (!enabled()) return;
			System.setProperty("http.proxyHost", host);
			System.setProperty("http.proxyPort", String.valueOf(port));
			System.setProperty("https.proxyHost", host);
			System.setProperty("https.proxyPort", String.valueOf(port));
		}

		/**
		 * The proxy variables, into the child processes' environment.
		 *
		 * <p>System properties cover THIS process. They reach a child not at all: the tools the build forks are
		 * handed their own command line and inherit only the environment, which is why neoform's runtime kept
		 * going direct to maven.neoforged.net while the parent used the proxy happily. Setting the variables here
		 * is what carries them across.
		 *
		 * <p>Set only where absent, so a value the user exported still wins.
		 */
		void applyTo(ProcessBuilder builder) {
			if (!enabled()) return;
			builder.environment().putIfAbsent("http_proxy", "http://" + host + ":" + port);
			builder.environment().putIfAbsent("https_proxy", "http://" + host + ":" + port);
			builder.environment().putIfAbsent("HTTP_PROXY", "http://" + host + ":" + port);
			builder.environment().putIfAbsent("HTTPS_PROXY", "http://" + host + ":" + port);
		}

		/** The same pair as {@code -D}, for a child that reads properties but not the environment. */
		List<String> jvmArguments() {
			if (!enabled()) return List.of();
			return List.of("-Dhttp.proxyHost=" + host, "-Dhttp.proxyPort=" + port,
					"-Dhttps.proxyHost=" + host, "-Dhttps.proxyPort=" + port);
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

	/**
	 * Minecraft's own libraries, downloaded if this machine has never had them.
	 *
	 * <p>This is what a fresh machine was missing. The launcher used to hand the merge a Minecraft directory with
	 * an empty {@code libraries/}, and the merge resolves external types through it — so it failed on a class it
	 * could read perfectly well, with a message about an initializer dependency that names nothing about the real
	 * cause. {@code dev.py} has always done this in {@code stage_minecraft}; the standalone launcher now does too.
	 */
	private static List<Path> mcLibraries(Path mcDir, Proxy proxy) throws Exception {
		try {
			return InstallerFacade.downloadMinecraftLibraries(mcDir, System.out::println);
		} catch (Exception failure) {
			throw new IOException("could not prepare Minecraft's libraries under " + mcDir + "/libraries — the "
					+ "merge needs them to resolve external types and will otherwise fail on an unrelated class. "
					+ (proxy.enabled()
							? "The proxy " + proxy.host() + ":" + proxy.port() + " was used."
							: "No proxy was configured; pass --proxy or export https_proxy if this network needs one."),
					failure);
		}
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
	/**
	 * The boot jar, unpacked from this jar's own resources.
	 *
	 * <p>Unpacked to a name derived from its CONTENT, not to a fixed {@code forbric-kernel.jar}. A fixed name is
	 * a cache that can never be invalidated: the file exists, so it is used, whatever the launcher now carries.
	 * Rebuilding the launcher with a new kernel and running it in an existing game directory then executes the
	 * OLD kernel, silently — which is exactly how a fix for "plugins/ is never created" shipped and appeared not
	 * to work. The content name makes replacement automatic and keeps the old copy harmlessly beside it.
	 */
	private static Path unpackBootJar(Path dir) throws IOException {
		Files.createDirectories(dir);
		byte[] bytes;
		try (InputStream in = ServerBootstrap.class.getResourceAsStream("/" + NESTED + "forbric-kernel.jar")) {
			if (in == null) throw new IOException("this launcher carries no kernel at " + NESTED
					+ "forbric-kernel.jar — it was built without the standaloneJar task");
			bytes = in.readAllBytes();
		}
		Path target = dir.resolve("forbric-kernel-" + digestOf(bytes).substring(0, 16) + ".jar");
		if (Files.isRegularFile(target) && Files.size(target) == bytes.length) return target;
		Path temporary = target.resolveSibling(target.getFileName() + ".part");
		Files.write(temporary, bytes);
		Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		return target;
	}

	/** SHA-256 of bytes, for naming an unpacked artifact after what it is. */
	private static String digestOf(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (java.security.NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("every JVM has SHA-256", impossible);
		}
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
