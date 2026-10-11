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

import net.forbric.kernel.util.ForbricLog;

/**
 * Records the text of every command packet that reaches this server.
 *
 * <p>The other half of {@code ClientCommandLogInjector}, which calls this at the head of
 * {@code ServerboundChatCommandPacket.handle}.
 *
 * <h2>The question this answers</h2>
 *
 * <p>A client that cannot make sense of the command tree refuses the command locally. Nothing is sent,
 * so nothing arrives, and the server's log is empty — no command, no error, no disconnect. From the
 * server's side that is indistinguishable from a player who never typed anything, which is why a
 * report like "试图执行该命令时出现意外错误" used to take a dozen rounds of guessing to place.
 *
 * <p>With this line in the log the question has an answer: <b>a command that appears here reached the
 * server, and one that does not was refused before it became a packet</b>. The two need opposite
 * fixes, and nothing else on this side tells them apart.
 *
 * <h2>The text is read reflectively</h2>
 *
 * <p>This class is loaded by the boot loader and the packet by the game loader, so the record's
 * accessor cannot be named at compile time. {@code command()} is a record component (26.2 ships the
 * packet as a record); the older spelling is {@code getCommand()}, and both are tried so a recompile
 * of the game against an older shape does not silently turn this into a no-op.
 */
public final class KernelClientCommandLog {
	private static volatile boolean enabled = true;

	private KernelClientCommandLog() {
	}

	/** Reads the switch once, at boot. */
	public static void configure() {
		enabled = !"off".equalsIgnoreCase(System.getProperty("forbric.logCommands", "on"));
		if (!enabled) {
			ForbricLog.warn("[Forbric/Commands] -Dforbric.logCommands=off — commands a client refuses cannot "
					+ "be told apart from commands a player never typed");
		}
	}

	/**
	 * Prints the command text, or says the text could not be read.
	 *
	 * <p>Never throws. This runs inside a packet handler on a Netty thread: an exception here would
	 * fail the packet and disconnect the player, which is a far worse outcome than a missing log line.
	 */
	public static void note(Object packet) {
		if (!enabled || packet == null) return;
		try {
			String command = text(packet);
			if (command == null) {
				ForbricLog.info("[Forbric/Commands] a command packet arrived and its text could not be read "
						+ "(%s) — the packet did arrive, so this is a diagnostic gap and not a client refusal",
						packet.getClass().getName());
				return;
			}
			ForbricLog.info("[Forbric/Commands] client command from a packet: /%s", command);
		} catch (RuntimeException | LinkageError unexpected) {
			ForbricLog.warn("[Forbric/Commands] command log hook failed (%s) — the packet is unaffected",
					unexpected.getClass().getName());
		}
	}

	/** The command text off the record, trying the accessor names the packet may have. */
	private static String text(Object packet) {
		for (String name : new String[] {"command", "getCommand"}) {
			try {
				Object value = packet.getClass().getMethod(name).invoke(packet);
				if (value instanceof String command) return command;
			} catch (ReflectiveOperationException | RuntimeException absent) {
				// try the next spelling
			}
		}
		return null;
	}
}
