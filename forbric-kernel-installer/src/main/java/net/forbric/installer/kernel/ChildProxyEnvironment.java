/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.installer.kernel;

import java.util.List;
import java.util.Map;

/**
 * Carries the shell's proxy variables into a forked child's environment.
 *
 * <h2>Why this exists as its own class</h2>
 *
 * <p>{@code ServerBootstrap} has the same logic on its own {@code Proxy} record, and that record is
 * package-private in {@code net.forbric.kernel.standalone} — a different package from this one, so
 * every tool here would need either a visibility change or a copy. A copy is what happened: two of the
 * forks below ran with no proxy at all while the parent downloaded through one happily.
 *
 * <h2>What was actually broken</h2>
 *
 * <p>System properties ({@code -Dhttps.proxyHost=…}) cover the JVM that has them and reach a child
 * not at all: a child is handed its own command line and inherits only the environment. So a user who
 * passes {@code -Dhttps.proxyHost} to the installer gets every download in the PARENT through the
 * proxy, and every tool the build forks — NFRT, the Forge tool — goes direct. On a network where
 * {@code maven.neoforged.net} resolves but only answers through a proxy, that is exactly the shape of
 * the failure: the parent's requests succeed and the child's sit until they time out, which reads as
 * "offline?" in a log that just proved the network is fine.
 *
 * <p>The {@code http_proxy}/{@code https_proxy} variables are the other half. Java children inherit
 * the environment, so a user who exported those is already covered — {@link #apply} uses
 * {@code putIfAbsent} so a value the user exported still wins over the one derived from system
 * properties.
 */
final class ChildProxyEnvironment {

	/** Both spellings, because tools differ on which they read. */
	private static final List<String> NAMES = List.of("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY");

	private ChildProxyEnvironment() {
	}

	/**
	 * Puts the proxy into {@code environment} when one is configured and the child does not already
	 * carry its own.
	 *
	 * <p>Read in this order, and it matters: an explicit {@code -Dhttps.proxyHost} the user passed on
	 * the command line is the most specific thing anyone said, so it is consulted first; the JVM can
	 * also have been started from a shell that exported the variables, in which case {@code putIfAbsent}
	 * leaves the child's inherited value alone.
	 *
	 * @param environment the child's environment map, i.e. {@code ProcessBuilder.environment()}
	 */
	static void apply(Map<String, String> environment) {
		String host = firstNonBlank(System.getProperty("https.proxyHost"), System.getProperty("http.proxyHost"));
		String port = firstNonBlank(System.getProperty("https.proxyPort"),
				firstNonBlank(System.getProperty("http.proxyPort"), "7890"));
		if (host == null) return;
		String url = "http://" + host + ":" + port;
		for (String name : NAMES) environment.putIfAbsent(name, url);
	}

	/**
	 * The JVM arguments that carry the same proxy to a child that reads properties rather than the
	 * environment.
	 *
	 * <p>Empty when no proxy is configured, so a caller can splat it into a command line without
	 * testing first and without adding two dead {@code -D} flags.
	 */
	static List<String> jvmArguments() {
		String host = firstNonBlank(System.getProperty("https.proxyHost"), System.getProperty("http.proxyHost"));
		if (host == null) return List.of();
		String port = firstNonBlank(System.getProperty("https.proxyPort"),
				firstNonBlank(System.getProperty("http.proxyPort"), "7890"));
		return List.of("-Dhttp.proxyHost=" + host, "-Dhttp.proxyPort=" + port,
				"-Dhttps.proxyHost=" + host, "-Dhttps.proxyPort=" + port);
	}

	private static String firstNonBlank(String first, String second) {
		if (first != null && !first.isBlank()) return first;
		return second != null && !second.isBlank() ? second : null;
	}
}
