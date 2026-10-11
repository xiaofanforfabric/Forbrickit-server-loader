/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The public door into the installer's artifact pipeline.
 *
 * <p>{@link ArtifactBuilder} and {@link JdkLocator.Jvm} are package-private, and both want to stay that way: they
 * are the installer's internals, not an API, and widening them would put the whole pipeline on a surface every
 * future change has to keep compatible. This class is the one type that is deliberately {@code public}, so a
 * caller outside the package — the standalone server launcher ({@code net.forbric.kernel.standalone}) — can ask
 * for game artifacts without the installer's own visibility being part of the bargain.
 *
 * <p>It does the same six copies {@code DevPrepare} does, for the same reason: the staged tree's layout is what
 * the kernel launch expects ({@code --gameJar} one file, {@code --runtimeJar} two), and the shape of that tree is
 * the contract, not the builder's return map.
 *
 * <h2>Why a launcher wants this rather than shipping the artifacts</h2>
 *
 * <p>The merged base embeds Mojang, MinecraftForge and NeoForge code. It cannot be redistributed, so a
 * downloadable Forbric jar can never contain it — the only lawful path is to build it on the user's own machine
 * from the same public sources the installer uses. That is exactly what this does, and it is why a first launch
 * takes minutes and needs a network.
 */
public final class InstallerFacade {

	private InstallerFacade() {
	}

	/** The Minecraft version this build of Forbric targets. Kept in step with {@link Pins}. */
	public static String minecraftVersion() {
		return Pins.MINECRAFT;
	}

	/**
	 * A JVM the build tools can run under, found or downloaded.
	 *
	 * @param mcDir       the Minecraft directory to look in first
	 * @param requested   a JVM path the user named, or {@code null} to let the locator choose
	 * @param log         where progress goes; never null
	 */
	public static Jvm locateJvm(Path mcDir, Path requested, Consumer<String> log) throws IOException {
		JdkLocator.Jvm jvm = JdkLocator.locate(mcDir, requested, log);
		if (jvm.feature() < 25) {
			throw new IOException("Forbric needs JDK 25 or newer; the located JVM is " + jvm.feature()
					+ " (" + jvm.javaBin() + ")");
		}
		return new Jvm(jvm.javaBin(), jvm.feature());
	}

	/** A JVM the game can run under. Public so callers need not name the package-private record. */
	public record Jvm(Path javaBin, int feature) {
	}

	/**
	 * Builds (or reuses) the game artifacts and stages them in the tree a kernel launch reads.
	 *
	 * <p>Reuse is the normal case and costs nothing: {@link ArtifactBuilder} keeps every intermediate under
	 * {@code <mcDir>/.forbric-build/} and re-runs only the steps whose inputs moved. A caller may therefore point
	 * this at a Minecraft directory that already holds artifacts from {@code dev.py prepare}, and it will copy
	 * rather than rebuild.
	 *
	 * @param mcDir     the Minecraft directory; its {@code versions/<mc>/<mc>.jar} is downloaded if absent
	 * @param stageDir  where the staged tree is written — the layout {@code dev.py} calls {@code staged/run}
	 * @param log       where progress goes; never null
	 * @return the staged file per coordinate (without version), for a caller that wants to name them
	 */
	public static Map<String, Path> prepareGame(Path mcDir, Path stageDir, Consumer<String> log) throws IOException {
		JdkLocator.Jvm jvm = JdkLocator.locate(mcDir, null, log);
		if (jvm.feature() < 25) {
			throw new IOException("Minecraft " + Pins.MINECRAFT + " needs JDK 25 or newer to build its artifacts; "
					+ "the located JVM is " + jvm.feature() + " (" + jvm.javaBin() + ")");
		}

		// The vanilla client jar is the pipeline's first input; download it if this machine has never run
		// Minecraft 26.2, so a fresh machine needs nothing but this launcher and a network.
		Path version = mcDir.resolve("versions").resolve(Pins.MINECRAFT);
		if (!Files.isRegularFile(version.resolve(Pins.MINECRAFT + ".jar"))
				|| !Files.isRegularFile(version.resolve(Pins.MINECRAFT + ".json"))) {
			new MojangDownloader(log).downloadClient(Pins.MINECRAFT, version);
		}

		Map<String, Path> artifacts = new ArtifactBuilder(log).build(mcDir, Pins.MINECRAFT, jvm);
		Path build = mcDir.resolve(".forbric-build/out");

		// The staged layout, exactly as DevPrepare writes it. The paths are spelled out rather than derived from
		// the builder's keys because these filenames are what a launch names on its command line.
		Map<String, Path> staged = new LinkedHashMap<>();
		staged.put("merged", stage(mcDir, stageDir, artifacts.get(ArtifactBuilder.MERGED),
				"merged-base/patched-mc-merged-" + Pins.MINECRAFT + ".jar"));
		staged.put("forgeRuntime", stage(mcDir, stageDir, artifacts.get(ArtifactBuilder.FORGE_RUNTIME),
				"merged-base/forge-runtime-interop.jar"));
		staged.put("neoforgeRuntime", stage(mcDir, stageDir, artifacts.get(ArtifactBuilder.NEOFORGE_RUNTIME),
				"neoforge-runtime/neoforge-runtime.jar"));
		// Compilation and bytecode tests read the raw carrier, while launch uses the interop-patched one above.
		staged.put("forgeCarrier", stage(mcDir, stageDir, build.resolve("forge-runtime.jar"),
				"forge-runtime/forge-runtime.jar"));
		// The build pins beside the merged base say it came out of this one merge. Nothing in a launch reads them;
		// they are staged because a launch that later wants to verify which build it is running will look here,
		// and because a staged tree without them is one the tests cannot tell from an older build's.
		staged.put("mergedPins", stage(mcDir, stageDir, build.resolve("patched-mc-merged-" + Pins.MINECRAFT + ".jar.pins"),
				"merged-base/patched-mc-merged-" + Pins.MINECRAFT + ".jar.pins"));
		return staged;
	}

	/**
	 * Downloads Minecraft's own libraries, which the merge resolves external types through.
	 *
	 * <p>Exposed because a machine with no Minecraft install has none of them, and the merge does not say so: it
	 * fails with "Incompatible initializer dependency on …ChunkGenerator#featuresPerStep", which names neither
	 * libraries nor the platform. A launcher that builds the game itself has to bring these too.
	 *
	 * @return the libraries' paths, in the order the version JSON lists them
	 */
	public static java.util.List<Path> downloadMinecraftLibraries(Path mcDir, Consumer<String> log)
			throws IOException {
		// The version JSON is the manifest this reads, and on a machine that has never run Minecraft it is not
		// there yet. Fetching it here rather than trusting a caller's ordering is what makes the launcher's
		// sequence (libraries, then the build that needs them) correct on an empty directory; the downloader is
		// the same one DevPrepare uses and writes both the JSON and the client jar.
		Path version = mcDir.resolve("versions").resolve(Pins.MINECRAFT);
		if (!Files.isRegularFile(version.resolve(Pins.MINECRAFT + ".json"))) {
			new MojangDownloader(log).downloadClient(Pins.MINECRAFT, version);
		}
		java.util.List<Path> jars = new java.util.ArrayList<>(MinecraftLibraries.download(mcDir, Pins.MINECRAFT, log));
		jars.addAll(MinecraftLibraries.consolePins(mcDir, log));
		return jars;
	}

	private static Path stage(Path mcDir, Path stageDir, Path source, String relative) throws IOException {
		Path target = stageDir.resolve(relative);
		Files.createDirectories(target.getParent());
		// A .part rename keeps a killed launch from leaving a half-written jar that the next one trusts: the merge
		// itself can take minutes, and the interrupt window is not small.
		if (!Files.isRegularFile(target) || Files.mismatch(source, target) != -1) {
			Path temporary = target.resolveSibling(target.getFileName() + ".part");
			Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
			Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
		}
		return target;
	}
}
