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

package net.forbric.kernel.boot;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a client that is <em>not</em> NeoForge finish the configuration phase instead of being
 * disconnected before the registry is ever synchronized.
 *
 * <h2>The gate this replaces</h2>
 *
 * <p>The merged base's {@code ServerConfigurationPacketListenerImpl} is NeoForge's patched copy, so
 * its {@code handlePong} is NeoForge's handshake: it calls
 * {@code NetworkRegistry.initializeOtherConnection(listener)}, which negotiates payload sets against
 * the client and, on failure, <b>disconnects with
 * {@code "You are trying to connect to a server that is running NeoForge, but you are not"}</b> and
 * returns {@code false}. {@code handlePong} then returns without calling {@code runConfiguration()},
 * so {@code SynchronizeRegistriesTask} is never queued and the client's
 * {@code ClientboundFinishConfigurationPacket} handler dies in {@code Registry Loading} with
 * "Missing tag: ..." for every tag the registry referenced.
 *
 * <p>That refusal is correct for a real NeoForge server: its registry contents differ from vanilla,
 * so a vanilla client cannot resolve them. It is exactly wrong for this one, whose whole purpose is
 * that a vanilla client connects without installing anything. So when the peer is not NeoForge, the
 * negotiation is skipped and {@code runConfiguration()} runs as vanilla would have run it.
 *
 * <h2>Why skipping the negotiation is safe here</h2>
 *
 * <p>{@code initializeOtherConnection} does four things and only the first needs the negotiation:
 * it cleans inbound filters, records an <em>empty</em> payload set (nothing was negotiated), records
 * the connection type, and then — the part that fails — negotiates the server's payloads against an
 * empty client list. Everything {@code runConfiguration()} later needs is either produced by itself
 * (the brand, server links, enabled features, known packs, {@code SynchronizeRegistriesTask}) or is
 * optional mod traffic the vanilla client discards as an unknown custom payload. The connection-type
 * and empty-payload bookkeeping is redone by {@link #noteVanillaPeer} so mods that later ask which
 * side they are talking to still get an answer.
 *
 * <h2>The warning</h2>
 *
 * <p>The configuration phase has no text channel to the client, so the warning cannot be shown while
 * the client is still loading. It is recorded here and delivered as a chat message once the player is
 * actually in the world (see {@code KernelVanillaClientNotice}), because "your client does not have
 * the mods this server was built with" is only actionable once the player can read it.
 */
public final class KernelVanillaClientBridge {
	/** Connection UUIDs whose configuration phase was admitted without a NeoForge negotiation. */
	private static final Set<UUID> VANILLA_PEERS = ConcurrentHashMap.newKeySet();

	/** Whether the bridge is installed. Off means the NeoForge refusal stands, as upstream. */
	private static boolean enabled;

	private KernelVanillaClientBridge() {
	}

	/** Reads the switch once, at boot, so the hot path is a field read. */
	public static void configure() {
		enabled = !"off".equalsIgnoreCase(System.getProperty("forbric.vanillaClient", "on"));
		if (!enabled) {
			ForbricLog.warn("[Forbric/VanillaClient] -Dforbric.vanillaClient=off — a client that is not "
					+ "NeoForge will be disconnected during configuration with "
					+ "\"You are trying to connect to a server that is running NeoForge, but you are not\"");
		}
	}

	/**
	 * Whether the transport hook should admit this listener. Called from the injected
	 * {@code handlePong}, in place of {@code NetworkRegistry.initializeOtherConnection}.
	 *
	 * <p>Returns whether the caller must still run NeoForge's negotiation. When the bridge is off it
	 * answers {@code true} unconditionally, which is upstream behaviour routed back through the
	 * original call.
	 */
	public static boolean shouldNegotiate(Object listener) {
		if (!enabled) return true;
		try {
			if (isNeoForgePeer(listener)) return true;
			noteVanillaPeer(listener);
			return false;
		} catch (ReflectiveOperationException | RuntimeException unknown) {
			// Never make connectivity depend on this probe. If the shape is not what we expect the
			// safe answer is "let NeoForge decide", which is the behaviour without the bridge.
			ForbricLog.warn("[Forbric/VanillaClient] peer probe failed (%s) — leaving the NeoForge "
					+ "negotiation in charge of this connection", unknown.getClass().getSimpleName());
			return true;
		}
	}

	/** {@code true} when the peer announced itself as NeoForge and can resolve this server's registry. */
	private static boolean isNeoForgePeer(Object listener) throws ReflectiveOperationException {
		Object type = readConnectionType(listener);
		if (type == null) return false;
		// ConnectionType.isNeoForge() is the exact predicate handlePong itself branches on.
		Object answer = type.getClass().getMethod("isNeoForge").invoke(type);
		return Boolean.TRUE.equals(answer);
	}

	private static Object readConnectionType(Object listener) throws ReflectiveOperationException {
		for (Class<?> owner = listener.getClass(); owner != null; owner = owner.getSuperclass()) {
			try {
				var field = owner.getDeclaredField("connectionType");
				field.setAccessible(true);
				return field.get(listener);
			} catch (NoSuchFieldException absent) {
				// Walk up: the field is declared on the listener itself on the merged base, but the
				// superclass is searched so a future move does not silently disable the bridge.
			}
		}
		return null;
	}

	/**
	 * Records the peer as vanilla and publishes the answers {@code initializeOtherConnection} would
	 * have published had the negotiation succeeded, so mods that ask "am I talking to NeoForge?" get
	 * {@code OTHER} rather than an unset attribute.
	 */
	private static void noteVanillaPeer(Object listener) {
		Object connection = null;
		try {
			connection = listener.getClass().getMethod("getConnection").invoke(listener);
		} catch (ReflectiveOperationException | RuntimeException unknown) {
			ForbricLog.warn("[Forbric/VanillaClient] could not read the connection from %s (%s: %s)",
					listener.getClass().getName(), unknown.getClass().getName(), unknown.getMessage());
		}

		// Each step is isolated on purpose. The connection attributes below are what the server reads
		// when it switches to PLAY (NetworkFilters.injectIfNecessary), so a failure to remember the
		// player's name must not be able to take them down with it.
		rememberPlayer(listener);

		if (connection == null) {
			ForbricLog.warn("[Forbric/VanillaClient] admitted a non-NeoForge client but has no connection "
					+ "to stamp: NetworkFilters will find no connection type when this client finishes "
					+ "configuration, and the packet that ends the configuration phase will fail");
			return;
		}
		forbricAttributes(connection);
		ForbricLog.info("[Forbric/VanillaClient] a client that is not NeoForge was admitted to the "
				+ "configuration phase — the NeoForge payload negotiation was skipped so "
				+ "SynchronizeRegistriesTask runs and the client receives this server's registry and "
				+ "tags. It will be warned in chat, and it must have installed the mods this server "
				+ "runs or it will crash on a block, item or entity it does not know");
	}

	/** Records the player behind this connection, so the chat warning can find them later. */
	private static void rememberPlayer(Object listener) {
		try {
			for (Class<?> owner = listener.getClass(); owner != null; owner = owner.getSuperclass()) {
				java.lang.reflect.Field profileField;
				try {
					profileField = owner.getDeclaredField("gameProfile");
				} catch (NoSuchFieldException absent) {
					continue;
				}
				profileField.setAccessible(true);
				Object profile = profileField.get(listener);
				if (profile == null) return;
				VANILLA_PEERS.add(playerId(profile));
				return;
			}
		} catch (ReflectiveOperationException | RuntimeException unknown) {
			ForbricLog.warn("[Forbric/VanillaClient] could not record which player this admitted client "
					+ "is, so the \"install the mods\" warning will not be shown in their chat (%s: %s)",
					unknown.getClass().getName(), unknown.getMessage());
		}
	}

	/**
	 * The id out of whatever profile object this game carries.
	 *
	 * <p>Both spellings are tried because the one that exists depends on the authlib on the classpath,
	 * and the failure mode of guessing wrong is silent — the warning simply never arrives. authlib 7
	 * made {@code GameProfile} a record (26.2 ships it), so its accessor is {@code id()}; the older
	 * accessor is {@code getId()}. The no-argument, {@code UUID}-returning method is what is wanted in
	 * both cases.
	 */
	private static UUID playerId(Object profile) throws ReflectiveOperationException {
		for (String name : new String[] {"id", "getId", "getUUID"}) {
			try {
				Object value = profile.getClass().getMethod(name).invoke(profile);
				if (value instanceof UUID uuid) return uuid;
			} catch (NoSuchMethodException absent) {
				// try the next spelling
			}
		}
		throw new NoSuchMethodException(profile.getClass().getName() + " has no UUID accessor");
	}

	/**
	 * Reproduces the two attributes {@code initializeOtherConnection} sets, through NeoForge's own
	 * setters so the class that reads them and the class that writes them cannot drift apart.
	 *
	 * <h2>Every type is taken from a live instance</h2>
	 *
	 * <p>{@code Class.forName} here would resolve against the loader that loaded <em>this</em> class —
	 * the boot loader — while {@code connection} is a game object and NeoForge's classes sit on the
	 * game loader. The two {@code Connection} types are then different classes, {@code getMethod}
	 * finds no such method, and the whole call fails with {@code NoSuchMethodException} while the
	 * connection it was supposed to describe continues. That is exactly what happened: the setting
	 * never ran, and the failure surfaced later and elsewhere, as
	 * {@code NullPointerException: no connection type on connection!} inside
	 * {@code NetworkFilters.injectIfNecessary} when the client sent
	 * {@code ServerboundFinishConfigurationPacket}.
	 *
	 * <p>So nothing here is named by a {@code String} that could be resolved by the wrong loader:
	 * {@code connection.getClass()} and {@code setup.getClass()} give the exact types the setters
	 * declare, and the two classes that are named are loaded through the game loader that already
	 * owns {@code connection}.
	 */
	private static void forbricAttributes(Object connection) {
		try {
			ClassLoader game = connection.getClass().getClassLoader();
			Class<?> attributes = Class.forName(
					"net.neoforged.neoforge.network.registration.ChannelAttributes", false, game);
			Object setup = Class.forName("net.neoforged.neoforge.network.registration.NetworkPayloadSetup",
					false, game).getMethod("empty").invoke(null);
			Object other = Class.forName("net.neoforged.neoforge.network.connection.ConnectionType",
					false, game).getField("OTHER").get(null);

			// The setter NAMES are known; their parameter types are not assumed. connection is a live
			// object whose runtime class may be a subclass (or a lambda's carrier), and
			// Class.getMethod demands an exact parameter-type match, so passing
			// connection.getClass() finds a setter declared as setConnectionType(Connection, ...) only
			// when the runtime class IS Connection. It is not: this threw NoSuchMethodException and
			// the attribute was never written. So each setter is selected by name and arity, and its
			// declared parameter types are used for the call.
			applySetter(attributes, "setPayloadSetup", connection, setup);
			applySetter(attributes, "setConnectionType", connection, other);
		} catch (ReflectiveOperationException | RuntimeException unknown) {
			// Not fatal to the connection, but it is fatal to the packet that ends configuration:
			// NetworkFilters.injectIfNecessary reads this attribute when the protocol switches to
			// PLAY and throws NullPointerException when it is absent. Logged at ERROR for that reason.
			ForbricLog.warn("[Forbric/VanillaClient] could not publish NeoForge's connection attributes "
					+ "for an admitted client (%s: %s) — the packet that ends the configuration phase "
					+ "will fail with \"no connection type on connection!\"",
					unknown.getClass().getName(), unknown.getMessage());
		}
	}

	/**
	 * Calls the two-argument static setter named {@code name} with {@code (connection, value)}.
	 *
	 * <p>Selected by name and arity rather than by parameter types, because the second parameter's
	 * type is a class this side cannot spell and the first is declared on a supertype of the object
	 * passed. Both argument types are checked assignable before the call, so a method of the right
	 * shape but the wrong types is skipped rather than invoked.
	 */
	private static void applySetter(Class<?> owner, String name, Object connection, Object value)
			throws ReflectiveOperationException {
		for (var method : owner.getMethods()) {
			if (!method.getName().equals(name) || method.getParameterCount() != 2) continue;
			Class<?>[] parameters = method.getParameterTypes();
			if (!parameters[0].isInstance(connection) || !parameters[1].isInstance(value)) continue;
			method.invoke(null, connection, value);
			return;
		}
		throw new NoSuchMethodException(owner.getName() + "." + name
				+ "(Connection, " + value.getClass().getSimpleName() + ")");
	}

	/** Whether the given player connected with a client that is not NeoForge. */
	public static boolean isVanillaPeer(UUID player) {
		return player != null && VANILLA_PEERS.contains(player);
	}

	/** Called once the warning has been delivered, so it is not repeated on every respawn or join. */
	public static void forget(UUID player) {
		if (player != null) VANILLA_PEERS.remove(player);
	}

	/**
	 * The warning a vanilla peer is shown once it is in the world, as plain text. This class runs on
	 * the boot side and cannot name a Minecraft type, so the caller in the runtime layer wraps this in
	 * a chat component.
	 */
	public static String warningText() {
		return "This server runs the NeoForge loader. Install every mod this server runs, or you will "
				+ "crash on a block, item or entity your client does not know.";
	}

	static void resetForTests() {
		VANILLA_PEERS.clear();
		enabled = false;
	}
}
