/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Downloads Minecraft's own libraries, in the order its version JSON lists them.
 *
 * <p>The launcher does this on first play, and {@code dev.py} does it in {@code stage_minecraft}. The standalone
 * launcher had neither: it fetched {@code 26.2.jar}, left {@code libraries/} empty, and then handed that empty
 * directory to the merge — which resolves external types through it. The merge then failed on a class it could
 * read perfectly well, with a message about an initializer dependency that names nothing about the missing
 * libraries. A machine with no Minecraft install could not build a server at all.
 *
 * <h2>Only what the game needs to run a server</h2>
 *
 * <p>The version JSON's {@code libraries} list is the authoritative classpath and is followed as-is, including
 * its rules: 77 of 26.2's 131 entries carry {@code rules}, and taking them all would fetch the Windows and macOS
 * natives onto a Linux host. {@code natives} and {@code classifiers} are absent from 26.2's JSON entirely, so
 * there is nothing to unpack — the loaders' own libraries carry their natives inside the jar on this version.
 *
 * <p>Paths come from {@code downloads.artifact.path}, which is where the launcher would put the jar and what the
 * version JSON's own consumers look up; deriving them from the Maven coordinate would be a second opinion that
 * can disagree.
 */
final class MinecraftLibraries {

	/** How every entry names the file it wants, and the only field this needs. */
	private record Entry(String path, String url, String sha1) {
	}

	private MinecraftLibraries() {
	}

	/**
	 * Ensures every library the version JSON lists is present under {@code mcDir/libraries}.
	 *
	 * @param mcDir the Minecraft directory; its {@code versions/<mc>/<mc>.json} is the manifest
	 * @return the libraries' paths, in manifest order
	 */
	static List<Path> download(Path mcDir, String mcVersion, Consumer<String> log) throws IOException {
		Path json = mcDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".json");
		if (!Files.isRegularFile(json)) {
			throw new IOException("no " + json + " — the base download should have written it");
		}
		List<Entry> entries = parse(json, log);
		if (entries.isEmpty()) {
			throw new IOException(json + " lists no libraries for this platform; refusing to hand the merge an "
					+ "empty classpath, which is how it fails on a class it can otherwise read");
		}

		Http http = new Http(log);
		List<Path> jars = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			Path target = mcDir.resolve("libraries").resolve(entry.path());
			// ensure() is the same call dev.py's fetch() makes: a cached file that hashes right is left alone,
			// a missing or wrong one is streamed to a .part and moved into place only after it verifies.
			http.ensure(entry.url(), target);
			jars.add(target);
		}
		log.accept("[minecraft] " + jars.size() + " librar" + (jars.size() == 1 ? "y" : "ies") + " ready");
		return jars;
	}

	/**
	 * The entries that apply to this platform, from the version JSON.
	 *
	 * <p>Read with the installer's own {@link Json}, not a regex: the rules list is nested three deep and a
	 * pattern over the text cannot tell an artifact this platform needs from one it must not fetch.
	 */
	@SuppressWarnings("unchecked")
	private static List<Entry> parse(Path json, Consumer<String> log) throws IOException {
		Map<String, Object> root = (Map<String, Object>) Json.parse(Files.readString(json));
		Object libraries = root.get("libraries");
		if (!(libraries instanceof List<?> list)) return List.of();

		String os = systemName();
		String arch = architecture();
		List<Entry> entries = new ArrayList<>();
		for (Object element : list) {
			Map<String, Object> library = (Map<String, Object>) element;
			if (!applies((List<Map<String, Object>>) library.get("rules"), os, arch)) continue;
			Map<String, Object> downloads = (Map<String, Object>) library.get("downloads");
			if (downloads == null) continue;
			Map<String, Object> artifact = (Map<String, Object>) downloads.get("artifact");
			if (artifact == null) continue;
			entries.add(new Entry((String) artifact.get("path"), (String) artifact.get("url"),
					(String) artifact.get("sha1")));
		}
		return entries;
	}

	/**
	 * Mojang's rule semantics, as {@code dev.py}'s {@code applies()} implements them: a non-empty list starts
	 * DENIED, then the last rule that matches decides.
	 *
	 * <p>Getting this backwards fetches the wrong platform's natives rather than failing loudly, so it is worth
	 * stating: the list is a set of exceptions to "denied", not to "allowed".
	 */
	private static boolean applies(List<Map<String, Object>> rules, String os, String arch) {
		if (rules == null || rules.isEmpty()) return true;
		boolean allowed = false;
		for (Map<String, Object> rule : rules) {
			Map<String, Object> match = (Map<String, Object>) rule.get("os");
			if (match != null) {
				Object name = match.get("name");
				if (name != null && !name.equals(os)) continue;
				Object wantArch = match.get("arch");
				if (wantArch != null && !Pattern.matches((String) wantArch, arch)) continue;
				Object wantVersion = match.get("version");
				if (wantVersion != null && !Pattern.compile((String) wantVersion)
						.matcher(System.getProperty("os.version", "")).find()) continue;
			}
			// A rule gated on a launcher feature (demo mode, quick play, custom resolution) is not one a dedicated
			// server has, so it never matches — the same call dev.py makes.
			Map<String, Object> features = (Map<String, Object>) rule.get("features");
			if (features != null && features.values().stream()
					.anyMatch(value -> value instanceof Boolean enabled && enabled)) continue;
			allowed = "allow".equals(rule.get("action"));
		}
		return allowed;
	}

	private static String systemName() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) return "windows";
		if (os.contains("mac")) return "osx";
		return "linux";
	}

	/** Mojang's arch token, which its rules' patterns are written against. */
	private static String architecture() {
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return switch (arch) {
			case "amd64", "x86_64" -> "x86_64";
			case "aarch64", "arm64" -> "arm64";
			case "x86", "i386", "i686" -> "x86";
			default -> arch;
		};
	}

	/** The jline jars a dedicated console needs, which 26.2's own metadata omits. Mirrors dev.py's CONSOLE_PINS. */
	static List<Path> consolePins(Path mcDir, Consumer<String> log) throws IOException {
		Map<String, String> pins = new LinkedHashMap<>();
		pins.put("jline-reader", "26333a275de502adf1dd9e6ea50aa0b4021412c71490df9ed5e88a648886ee89");
		pins.put("jline-terminal", "c0f5d70901255da66a94e59778b265d19f9308342578e34c88fc92d1b0c65fef");
		pins.put("jline-terminal-jna", "58ca9d719c373206af15775ee3cd5f268136ea0d0c4e009c3e96a6d4612d5c66");

		Http http = new Http(log);
		List<Path> jars = new ArrayList<>();
		for (Map.Entry<String, String> pin : pins.entrySet()) {
			String relative = "org/jline/" + pin.getKey() + "/3.25.1/" + pin.getKey() + "-3.25.1.jar";
			Path target = mcDir.resolve("libraries").resolve(relative);
			http.ensure("https://repo.maven.apache.org/maven2/" + relative, target);
			// SHA-256, matching the digest dev.py records: these three are pinned by hand, not by Mojang's
			// manifest, so they carry the algorithm its existing pins already use.
			if (!pin.getValue().equalsIgnoreCase(Util.sha256(target))) {
				throw new IOException(target + " does not match the pinned SHA-256 for " + pin.getKey()
						+ "; refusing to run a server console on unverified bytes");
			}
			jars.add(target);
		}
		return jars;
	}
}
