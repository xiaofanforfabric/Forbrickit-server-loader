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

package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Logs every command a player types, and the two ways a command can fail before it reaches one.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A command that fails in a client's own dispatcher never becomes a packet. The server sees
 * nothing: no command, no error, no connection event. The player sees
 * {@code 试图执行该命令时出现意外错误} and the operator sees an empty log, so every attempt to find
 * out which side is wrong starts from nothing.
 *
 * <p>What this injector adds at the head of {@code handle} is the one line that makes the answer
 * unconditional: <b>the server prints every command it receives</b>. If a command the player typed
 * does not appear, the client refused it and no amount of server-side inspection will show why. If it
 * does appear, the client was fine and the failure is downstream.
 *
 * <h2>What is deliberately not logged</h2>
 *
 * <p>Completion and signed-command packets. Those are the other two paths a command can take, and
 * neither is on the route a typed command uses when the tree is intact — logging them would fill the
 * log with completion traffic for the one case this is here to separate.
 *
 * <h2>Cost and switch</h2>
 *
 * <p>One string read and one log line per command a player types. On by default, because the
 * information is the point and a player types commands rarely; {@code -Dforbric.logCommands=off}
 * turns it off.
 */
public final class ClientCommandLogInjector implements ClassTransformer {
	private static final String TARGET = "net.minecraft.network.protocol.game.ServerboundChatCommandPacket";
	private static final String HANDLE = "handle";
	private static final String HANDLE_DESC =
			"(Lnet/minecraft/network/protocol/game/ServerGamePacketListener;)V";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelClientCommandLog";
	private static final String HOOK_NAME = "note";
	private static final String HOOK_DESC = "(Ljava/lang/Object;)V";

	@Override
	public String name() {
		return "forbric-client-command-log";
	}

	@Override
	public AnchorSet anchors() {
		// HEDGE: nothing is broken without it. It is a diagnostic, and the game is exactly the game
		// without the line — which is the honest description and keeps it off the Mods screen as a
		// finding.
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.HEDGE,
				"a command a client refuses is invisible from this side: it never becomes a packet, so the "
						+ "player sees an error and the server logs nothing to compare it against"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode handle = null;
		for (MethodNode m : node.methods) {
			if (HANDLE.equals(m.name) && HANDLE_DESC.equals(m.desc)) {
				handle = m;
				break;
			}
		}
		if (handle == null) {
			ForbricLog.warn("[Forbric/Commands] no %s.%s to hook — a command a client refuses stays invisible",
					className, HANDLE);
			return classBytes;
		}

		// At the head, before the listener is even unwrapped: the point is to record that this packet
		// arrived at all, and a hook at the tail could be skipped by a throw inside the listener.
		//
		// The call adds nothing to the stack and returns void, so no jump is authored and no stack map
		// entry has to be. That is why the hook takes the packet and does its own field read, rather
		// than returning the string this method would have to push.
		InsnList prologue = new InsnList();
		prologue.add(new VarInsnNode(Opcodes.ALOAD, 0));
		prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
		handle.instructions.insert(prologue);
		handle.maxStack = Math.max(handle.maxStack, 1);

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		ForbricLog.info("[Forbric/Commands] %s.%s now logs the command text of every command packet that "
				+ "reaches this server — a command missing from the log was refused by the client before it "
				+ "became a packet", className, HANDLE);
		return writer.toByteArray();
	}
}
