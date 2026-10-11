/*
 * Forbric Bukkit bridge — reading a plugin.yml and bringing one plugin up.
 */
package org.cn.xiaofan.bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Everything needed to bring one plugin up: read its {@code plugin.yml}, define it in its own loader, construct
 * it, link it to the server, and run {@code onLoad} then {@code onEnable}.
 *
 * <p>The order is not arbitrary. {@code JavaPlugin}'s constructor refuses to finish unless its class loader is a
 * {@link PluginClassLoader} — so the loader must exist, and be the one defining the class, before the plugin is
 * constructed. And {@code init(...)} must run before {@code onLoad}/{@code onEnable}, because every accessor a
 * plugin calls ({@code getDataFolder}, {@code getLogger}, {@code getName}) reads a field it fills.
 *
 * <p>Mirrors the sequence Paper's own provider runs, without the parts this bridge does not have (a provider
 * registry, a module layer, a plugin meta file format of its own).
 */
final class PluginHandle {

	/** The entry name Bukkit reads a plugin's description from. */
	private static final String DESCRIPTOR = "plugin.yml";

	private final Path jarPath;
	private final PluginDescriptionFile description;
	private final PluginClassLoader classLoader;
	private final JavaPlugin plugin;
	private final String source;

	private PluginHandle(Path jarPath, PluginDescriptionFile description, PluginClassLoader classLoader,
			JavaPlugin plugin, String source) {
		this.jarPath = jarPath;
		this.description = description;
		this.classLoader = classLoader;
		this.plugin = plugin;
		this.source = source;
	}

	/**
	 * Loads one plugin jar, up to but not including {@code onEnable}. The caller decides when to enable, because
	 * the order across plugins is the caller's to choose.
	 *
	 * @param jarPath   the plugin jar
	 * @param parent    the loader carrying the Bukkit API and the game (shared by every plugin)
	 * @param dataRoot  where per-plugin data folders live; one directory per plugin is created beneath it
	 * @param logger    the logger the plugin receives from {@code getLogger()}
	 * @param server    the {@code Server} the plugin receives from {@code getServer()}
	 */
	static PluginHandle load(Path jarPath, ClassLoader parent, Path dataRoot, Logger logger, Object server)
			throws IOException, InvalidDescriptionException, ReflectiveOperationException {
		PluginDescriptionFile description = readDescription(jarPath);

		PluginClassLoader loader = new PluginClassLoader(jarPath, parent, description);
		Class<?> mainClass = Class.forName(description.getMainClass(), true, loader);
		if (!JavaPlugin.class.isAssignableFrom(mainClass)) {
			throw new IllegalStateException(description.getMainClass() + " does not extend JavaPlugin");
		}

		// Constructing is where JavaPlugin's own guard runs: its constructor calls back into `loader.init(this)`,
		// so the instance and the loader are linked before a single plugin field is assigned.
		JavaPlugin plugin = (JavaPlugin) mainClass.getDeclaredConstructor().newInstance();

		Path dataFolder = dataRoot.resolve(description.getName());
		// The seven-argument init is the one that fills every accessor a plugin has; the older six-argument one
		// derives the same fields but takes a PluginLoader this bridge has no equivalent of.
		plugin.init((org.bukkit.Server) server, description, dataFolder.toFile(), jarPath.toFile(), loader,
				description, logger);

		return new PluginHandle(jarPath, description, loader, plugin, jarPath.getFileName().toString());
	}

	/** Runs {@code onLoad}, then enables the plugin (which runs {@code onEnable}). */
	void enable() {
		plugin.onLoad();
		plugin.setEnabled(true);
	}

	/** Disables the plugin, running {@code onDisable} when it was enabled. */
	void disable() {
		if (plugin.isEnabled()) plugin.setEnabled(false);
	}

	/** Reads {@code plugin.yml} through Bukkit's own parser, so the accepted syntax is Bukkit's, not ours. */
	private static PluginDescriptionFile readDescription(Path jarPath) throws IOException, InvalidDescriptionException {
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			JarEntry entry = jar.getJarEntry(DESCRIPTOR);
			if (entry == null) {
				throw new InvalidDescriptionException(
						"[Forbric/Bukkit] " + jarPath.getFileName() + " has no " + DESCRIPTOR);
			}
			try (InputStream in = jar.getInputStream(entry)) {
				return new PluginDescriptionFile(in);
			}
		}
	}

	PluginDescriptionFile description() {
		return description;
	}

	JavaPlugin plugin() {
		return plugin;
	}

	PluginClassLoader classLoader() {
		return classLoader;
	}

	Path jarPath() {
		return jarPath;
	}

	String source() {
		return source;
	}
}
