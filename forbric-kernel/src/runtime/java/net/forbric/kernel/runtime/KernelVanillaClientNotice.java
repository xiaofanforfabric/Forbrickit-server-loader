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

package net.forbric.kernel.runtime;

import java.util.UUID;

import net.forbric.kernel.boot.KernelVanillaClientBridge;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Tells a player whose client is not NeoForge that this server runs the loader and that the mods
 * must be installed on their side.
 *
 * <p>{@link KernelVanillaClientBridge} admits such a client to the configuration phase and records it,
 * because the configuration phase itself has no text channel — the refusal it replaces is the only
 * thing NeoForge ever says, and it says it as a disconnect. This class is the other half: once the
 * player is actually in the world, the sentence is worth reading, so it is delivered as a chat
 * message the client is guaranteed to render.
 *
 * <p><b>Why this warning exists at all.</b> Skipping the negotiation makes the server send its
 * registry, which is what a vanilla client needs to finish loading; it does not make that registry
 * resolvable. A client without the mods will disconnect the moment it meets a block, an item or an
 * entity it has no entry for, and the crash it gets names a raw id. The sentence in the chat is the
 * difference between that and "install the mods this server runs".
 */
public final class KernelVanillaClientNotice {
	private KernelVanillaClientNotice() {
	}

	/**
	 * Warns {@code player} if their client connected without a NeoForge negotiation.
	 *
	 * <p>Called from the {@code PlayerLoggedInEvent} forward, so it runs after the player is fully in
	 * the world and a chat message will reach them. Recorded peers are forgotten once warned: the
	 * event fires again on respawn and on a dimension change in some paths, and the same sentence
	 * twice reads as a bug.
	 */
	public static void warnIfVanilla(Object player) {
		if (!(player instanceof ServerPlayer serverPlayer)) return;
		UUID id = serverPlayer.getUUID();
		if (!KernelVanillaClientBridge.isVanillaPeer(id)) return;
		KernelVanillaClientBridge.forget(id);
		serverPlayer.sendSystemMessage(Component.empty()
				.append(Component.literal("[Forbric] ").withStyle(ChatFormatting.GOLD))
				.append(Component.literal(KernelVanillaClientBridge.warningText())
						.withStyle(ChatFormatting.RED)));
	}
}
