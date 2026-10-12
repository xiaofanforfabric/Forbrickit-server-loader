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
	 * @param server        the running {@code net.minecraft.server.MinecraftServer}, the thing every {@code World}
	 *                      and {@code Player} the plugins see is ultimately reached through. Typed as
	 *                      {@code Object} on purpose: the caller is the boot-side {@code BukkitBridgeLauncher},
	 *                      which links no game type, and a parameter typed {@code MinecraftServer} would drag the
	 *                      whole game onto its signature.
	 * @return the running bridge, whose {@link #stop()} the kernel calls on shutdown
	 */
	public static BukkitRuntime start(Path pluginsFolder, Path dataFolder, ClassLoader loader, Object server)
			throws Exception {
		BridgeBootstrap bridge = BridgeBootstrap.start(pluginsFolder, dataFolder, loader, server);
		return new BukkitRuntime(bridge);
	}

	/** Disables every plugin, in reverse enable order. */
	public void stop() {
		bridge.stop();
	}

	/**
	 * Subscribes the game-to-Bukkit event forwards, so a plugin's {@code @EventHandler} is actually
	 * invoked.
	 *
	 * <p>Separate from {@link #start} because it is a caller-side concern: this class's own entry
	 * point is reached through the boot side, which names no game type and therefore holds no
	 * {@code IEventBus} to hand over. The kernel's game-side lifecycle has one at server start and
	 * calls this immediately afterwards. A bridge whose forwards never install still LOADS plugins
	 * and still runs their commands — it simply never tells them about an interaction, which is the
	 * silent half-failure this method exists to end.
	 *
	 * @param neoBus NeoForge's {@code IEventBus}, typed {@code Object} because the caller is
	 *               game-side code that must not force this seam's signature to name a NeoForge type
	 */
	public void installGameEvents(Object neoBus) {
		org.bukkit.plugin.PluginManager manager = bridge.server().getPluginManager();
		if (!(manager instanceof ForbricPluginManager plugins)) {
			return;
		}
		BukkitInteractBridge.install(neoBus, bridge.server(), plugins);
	}
}
