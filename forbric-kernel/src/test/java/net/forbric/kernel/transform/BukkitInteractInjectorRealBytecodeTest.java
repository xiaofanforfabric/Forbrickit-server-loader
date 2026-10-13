package net.forbric.kernel.transform;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the Bukkit interaction injector over the REAL merged-base bytecode and loads the result.
 *
 * <p>Why this exists beside {@code BukkitInteractInjectorExecutionTest}: that one drives hand-written stand-ins,
 * and a stand-in is written by the same person who wrote the injector, so it tends to have the shape the injector
 * expects. The merged base is the shape that actually ships — it carries a real {@code StackMapTable}, its
 * {@code handleBlockBreakAction} has had NeoForge hooks written into it, and its branch polarity is whatever the
 * compiler and the byte-merge left behind. Every frame bug this injector has had showed up only against that
 * shape, never against a stand-in.
 *
 * <p>The test is skipped, not failed, when the staged jar is absent: this source set is built on machines that
 * have never run the merge, and a missing fixture is not a broken injector. The skip is loud in the log so a run
 * that silently stopped covering the real shape is visible.
 */
class BukkitInteractInjectorRealBytecodeTest {

	private static final String JAR = ".dev/staged/run/merged-base/patched-mc-merged-26.2.jar";
	private static final String GAME_MODE = "net/minecraft/server/level/ServerPlayerGameMode.class";
	private static final String LISTENER = "net/minecraft/server/network/ServerGamePacketListenerImpl.class";

	@Test void theRealMergedBodiesSurviveInjectionAndLoad(@TempDir Path work) throws Exception {
		Path jar = Path.of(JAR);
		if (!Files.isRegularFile(jar)) {
			System.out.println("[forbric] skipping the real-bytecode check: " + JAR + " is not on this machine");
			return;
		}

		for (String entry : new String[] { GAME_MODE, LISTENER }) {
			byte[] original = read(jar, entry);
			String className = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');

			byte[] changed = InjectorExecution.transform(new BukkitInteractInjector(), className, original,
					EnvType.SERVER);
			if (changed == original) {
				// A declined site is legitimate — a base whose shape is not the reviewed one is left alone on
				// purpose, with a log line. What must not happen is a silent bad edit.
				System.out.println("[forbric] " + className + " was left alone (the injector declined it)");
				continue;
			}

			// Loading is the assertion. A wrong absolute frame, a bad offset or a stack mismatch is a
			// VerifyError raised HERE, which is the whole point: neither javac nor the ASM round-trip sees it.
			//
			// The parent is this test's own loader, which serves the transform and the hook; the target's own
			// references (ServerPlayer, Level, …) resolve from the merged jar, registered by name below so the
			// verifier can follow the field and method types the injected code names.
			InjectorExecution.load(Map.of(className.replace('.', '/'), changed), parentOf(jar));
			System.out.println("[forbric] " + className + " injected and loaded cleanly");
		}
	}

	/** A loader that serves the merged jar by name, so the verifier can resolve what the injected code names. */
	private static ClassLoader parentOf(Path jar) throws Exception {
		return new java.net.URLClassLoader(new java.net.URL[] { jar.toUri().toURL() },
				BukkitInteractInjectorRealBytecodeTest.class.getClassLoader());
	}

	private static byte[] read(Path jar, String entry) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) throw new IllegalStateException("no " + entry + " in " + jar);
			return zip.getInputStream(found).readAllBytes();
		}
	}
}
