/*
 * Forbric Bukkit bridge — the entry point the kernel calls into.
 *
 * Compiled into the kernel's game-side jar (forbric-kernel-runtime.jar), which ForbricClassLoader defines from.
 * Paper's API is the half that is NOT shipped: PaperApiFetcher downloads it into the game directory and
 * BukkitBridgeLauncher adds it to the same loader.
 */
package org.cn.xiaofan.bukkit;

import java.nio.file.Path;

/**
 * The bridge as the kernel sees it: one class, one static method to start, one instance method to stop.
 *
 * <p>The kernel reaches this by string and reflection ({@code BukkitBridgeLauncher}), because the kernel's boot
 * half names no game or Bukkit type. That is the same seam the kernel uses for its own game side.
 *
 * <p>The signatures here are a contract: {@code start(Path, Path, ClassLoader)} and {@code stop()}. Changing them
 * means changing {@code BukkitBridgeLauncher} in the same breath.
 */
public final class BukkitRuntime {

	private final BridgeBootstrap bridge;

	private BukkitRuntime(BridgeBootstrap bridge) {
		this.bridge = bridge;
	}

	/**
	 * Loads and enables every plugin in {@code pluginsFolder}.
	 *
	 * @param pluginsFolder where the plugin jars are
	 * @param dataFolder    the directory per-plugin data folders are created under
	 * @param loader        the loader that defined this class; passed through so plugin class loaders have the
	 *                      game's classes as their parent rather than whatever defined the bridge
	 * @return the running bridge, whose {@link #stop()} the kernel calls on shutdown
	 */
	public static BukkitRuntime start(Path pluginsFolder, Path dataFolder, ClassLoader loader) throws Exception {
		BridgeBootstrap bridge = BridgeBootstrap.start(pluginsFolder, dataFolder, loader);
		return new BukkitRuntime(bridge);
	}

	/** Disables every plugin, in reverse enable order. */
	public void stop() {
		bridge.stop();
	}
}
