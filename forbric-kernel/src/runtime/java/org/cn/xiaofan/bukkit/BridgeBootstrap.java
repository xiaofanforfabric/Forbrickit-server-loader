/*
 * Forbric Bukkit bridge — the entry point that finds and starts plugins.
 */
package org.cn.xiaofan.bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

import org.bukkit.plugin.InvalidDescriptionException;

/**
 * Finds the plugin jars in a directory, loads them, and enables them in order.
 *
 * <p>This is the bridge's whole job, and deliberately the smallest version of it that can be observed working:
 * discover, load, enable. There is no dependency sorting, no {@code PluginManager} registry, no reload. Each of
 * those is a real thing a server does, and each is added once something needs it rather than guessed at now.
 *
 * <h2>What "enabled" means here</h2>
 *
 * <p>For each jar: read its {@code plugin.yml}, define its main class in a {@link PluginClassLoader}, construct
 * it (which links it to its loader through {@code JavaPlugin}'s own guard), call {@code init(...)} to fill the
 * fields its accessors read, then {@code onLoad()} and {@code setEnabled(true)} — which is what runs
 * {@code onEnable()}.
 *
 * <p>A plugin that fails to load is reported and skipped; one bad jar must not stop the others, which is the
 * same rule every loader in this project follows.
 */
public final class BridgeBootstrap {

	private final Path pluginsFolder;
	private final Logger logger;
	private final ServerImpl server;
	private final ClassLoader hostLoader;
	private final List<PluginHandle> loaded = new ArrayList<>();

	private BridgeBootstrap(Path pluginsFolder, Logger logger, ServerImpl server, ClassLoader hostLoader) {
		this.pluginsFolder = pluginsFolder;
		this.logger = logger;
		this.server = server;
		this.hostLoader = hostLoader;
	}

	/**
	 * Loads every plugin in {@code pluginsFolder} and returns the bridge.
	 *
	 * <p>Called by {@link BukkitRuntime} once Forbric's server has a game directory. The return value keeps the
	 * handles alive, so a caller that drops it drops the plugins with it.
	 *
	 * @param loader the loader that defined the bridge — plugins take it as their parent, so a plugin sees the
	 *               same {@code org.bukkit} classes this bridge implements
	 */
	public static BridgeBootstrap start(Path pluginsFolder, Path dataFolder, ClassLoader loader) throws IOException {
		Logger logger = createLogger();
		// No PluginManager yet: paper-api's SimplePluginManager is final and its constructor needs a Server, so
		// building one here would be a cycle. ServerImpl answers getPluginManager() with null, which is honest —
		// nothing has registered a listener with it. The first plugin that needs it will be the reason to build it.
		ServerImpl server = new ServerImpl(logger, null, pluginsFolder, "26.2-forbric-bridge");
		BridgeBootstrap bridge = new BridgeBootstrap(pluginsFolder, logger, server, loader);

		logger.info("Forbric Bukkit bridge starting; plugins folder: " + pluginsFolder);
		bridge.loadAll(dataFolder);
		return bridge;
	}

	/** A logger that writes to the console at INFO, so a plugin's own logging is visible while this is built. */
	private static Logger createLogger() {
		Logger logger = Logger.getLogger("Forbric.Bukkit");
		logger.setUseParentHandlers(false);
		ConsoleHandler handler = new ConsoleHandler();
		handler.setLevel(Level.ALL);
		logger.addHandler(handler);
		logger.setLevel(Level.ALL);
		return logger;
	}

	private void loadAll(Path dataFolder) throws IOException {
		// Create it when absent, rather than warn and return. A server that says nothing and loads nothing is
		// indistinguishable from a broken one: the user drops a jar next to the server, sees no new folder, sees
		// no plugin load, and concludes the loader lied. Making the folder answers the question before it is
		// asked — and the first launch of a fresh server is exactly when it gets asked.
		if (!Files.isDirectory(pluginsFolder)) {
			Files.createDirectories(pluginsFolder);
			logger.info("created the plugins folder: " + pluginsFolder);
		}

		List<Path> jars;
		try (Stream<Path> entries = Files.list(pluginsFolder)) {
			jars = entries
					.filter(Files::isRegularFile)
					.filter(path -> path.getFileName().toString().endsWith(".jar"))
					.sorted()
					.toList();
		}

		if (jars.isEmpty()) {
			// Said plainly and with the path, because this is the message a user reads when their plugin is in
			// the wrong place — the MODS folder, a subfolder, a .zip, or a sibling directory named "plugin".
			long others;
			try (Stream<Path> entries = Files.list(pluginsFolder)) {
				others = entries.filter(path -> !path.getFileName().toString().endsWith(".jar")).count();
			}
			logger.info("no plugin jars found. Put Bukkit/Paper plugin .jar files directly in: " + pluginsFolder
					+ (others > 0 ? " (" + others + " other entr" + (others == 1 ? "y is" : "ies are")
							+ " there and were not .jar files)" : ""));
			return;
		}

		for (Path jar : jars) {
			try {
				PluginHandle handle = PluginHandle.load(jar, hostLoader, dataFolder, logger, server);
				loaded.add(handle);
				handle.enable();
				logger.info("enabled " + handle.description().getFullName() + " from " + jar.getFileName());
			} catch (IOException | InvalidDescriptionException | ReflectiveOperationException
					| RuntimeException failure) {
				// One jar's problem stays that jar's. The next one may be the one the user cares about.
				logger.log(Level.SEVERE, "could not load " + jar.getFileName() + ": " + failure, failure);
			}		}

		logger.info("bridge ready: " + loaded.size() + " plugin(s) enabled");
	}

	/** Disables every plugin, in reverse enable order. */
	public void stop() {
		for (int i = loaded.size() - 1; i >= 0; i--) {
			PluginHandle handle = loaded.get(i);
			try {
				handle.disable();
			} catch (RuntimeException failure) {
				logger.log(Level.WARNING, "could not disable " + handle.description().getName(), failure);
			}
		}
		loaded.clear();
	}

	/** The plugins this bridge enabled, in order. */
	public List<PluginHandle> loaded() {
		return List.copyOf(loaded);
	}

	/** The bridge's own logger, for a caller that wants to report through the same channel. */
	public Logger logger() {
		return logger;
	}
}
