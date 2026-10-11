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
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Lets a client that is not NeoForge finish the configuration phase, instead of being disconnected
 * before the registry is ever synchronized.
 *
 * <h2>The measured failure</h2>
 *
 * <p>The merged base's {@code ServerConfigurationPacketListenerImpl} is NeoForge's patched copy.
 * Its {@code handlePong} is NeoForge's handshake, and its three shapes are:
 *
 * <pre>
 *   super.handlePong(packet);
 *   if (packet.getId() != 0) return;
 *   if (connectionType.isNeoForge()) { runConfiguration(); return; }
 *   if (NetworkRegistry.initializeOtherConnection(this)) { runConfiguration(); return; }
 *   // falls off the end: nothing is sent
 * </pre>
 *
 * <p>{@code initializeOtherConnection} negotiates this server's payloads against the client's and,
 * on failure, <b>disconnects with "You are trying to connect to a server that is running NeoForge,
 * but you are not"</b> and returns {@code false}. A vanilla client never registered a payload, so the
 * negotiation always fails; {@code runConfiguration()} never runs; {@code SynchronizeRegistriesTask}
 * is never queued; and the vanilla client's {@code ClientboundFinishConfigurationPacket} handler dies
 * in {@code Registry Loading} with "Missing tag: ..." for every tag the registry referenced. The
 * client reports 网络协议错误.
 *
 * <h2>What this changes</h2>
 *
 * <p>One call is inserted ahead of the NeoForge chain:
 *
 * <pre>
 *   if (packet.getId() != 0) return;
 *   if (!KernelVanillaClientBridge.shouldNegotiate(this)) { runConfiguration(); return; }
 *   if (!initializeOtherConnection(this)) return;    // the original call, kept
 *   runConfiguration();
 * </pre>
 *
 * <p>{@code shouldNegotiate} answers {@code true} for a peer that announced itself as NeoForge, so a
 * NeoForge client takes the original path with its negotiation, its version check and its refusal
 * intact. It answers {@code false} for any other peer, and that peer runs {@code runConfiguration()}
 * — the vanilla sequence, which sends the brand, the server links, the enabled features, the known
 * packs and then {@code SynchronizeRegistriesTask} (the registry <em>and</em> its tags).
 *
 * <p>The original {@code connectionType.isNeoForge()} shortcut is gone, so a NeoForge client now
 * reaches {@code initializeOtherConnection} where it used to skip it. That is deliberate: the call
 * is idempotent for a peer that negotiated, and folding the two tests into one predicate is what
 * makes the branch authorable without a second stack-map edge. A NeoForge client's outcome is
 * unchanged — it is admitted exactly when its negotiation succeeds, which is when it was admitted
 * before.
 *
 * <h2>Why not make initializeOtherConnection "succeed"</h2>
 *
 * <p>That was the first design and it is wrong: the negotiation failing is also how a NeoForge
 * client with a mismatched mod set is refused, and forcing success would send a NeoForge client into
 * a registry it cannot resolve. Deciding on the peer type and then choosing a sequence keeps the two
 * audiences on their own paths.
 *
 * <h2>Frames and the rewrite</h2>
 *
 * <p>The branch is authored here, so the method needs a stack map and the injected code has to be
 * re-framed. {@code COMPUTE_FRAMES} does that, with {@code getCommonSuperClass} pinned to
 * {@code java/lang/Object}: the frames this computes only ever merge {@code this}, a
 * {@code boolean} and the result of {@code runConfiguration()}, and asking a real loader for the
 * common supertype of two game classes is how a transformer breaks inside the class loader it is
 * already running in.
 *
 * <p>Only {@code handlePong} is touched. The rest of the class — including
 * {@code runConfiguration()}, which this calls — is the base's own.
 */
public final class VanillaClientConfigurationInjector implements ClassTransformer {
	private static final String TARGET = "net.minecraft.server.network.ServerConfigurationPacketListenerImpl";
	private static final String TARGET_INTERNAL = "net/minecraft/server/network/ServerConfigurationPacketListenerImpl";
	private static final String HOOK_OWNER = "net/forbric/kernel/boot/KernelVanillaClientBridge";
	private static final String HOOK_NAME = "shouldNegotiate";
	private static final String HOOK_DESC = "(Ljava/lang/Object;)Z";
	private static final String HANDLE_PONG = "handlePong";
	private static final String HANDLE_PONG_DESC =
			"(Lnet/minecraft/network/protocol/common/ServerboundPongPacket;)V";
	/** The base's own {@code runConfiguration()}, which the vanilla branch calls. */
	private static final String RUN_CONFIGURATION = "runConfiguration";
	private static final String RUN_CONFIGURATION_DESC = "()V";
	/** NeoForge's admission call, kept on the NeoForge path so its refusal still decides that peer. */
	private static final String NEO_REGISTRY =
			"net/neoforged/neoforge/network/registration/NetworkRegistry";
	private static final String INITIALIZE_OTHER = "initializeOtherConnection";
	private static final String INITIALIZE_OTHER_DESC =
			"(Lnet/minecraft/network/protocol/configuration/ServerConfigurationPacketListener;)Z";

	private boolean announced;

	@Override
	public String name() {
		return "forbric-vanilla-client-configuration";
	}

	@Override
	public AnchorSet anchors() {
		// REQUIRED: this one is not hardening. Without it every client that is not NeoForge is
		// disconnected during configuration, which is the entire population this project exists for.
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"a client that is not NeoForge is disconnected during configuration with "
						+ "\"You are trying to connect to a server that is running NeoForge, but you are not\" "
						+ "before the registry is synchronized, so it can never join this server"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		if (!TARGET.equals(className)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);

		MethodNode pong = null;
		for (MethodNode m : node.methods) {
			if (HANDLE_PONG.equals(m.name) && HANDLE_PONG_DESC.equals(m.desc)) {
				pong = m;
				break;
			}
		}
		if (pong == null) {
			ForbricLog.warn("[Forbric/VanillaClient] no %s.%s to hook — a client that is not NeoForge "
					+ "will be disconnected during configuration and cannot join", className, HANDLE_PONG);
			return classBytes;
		}

		// Where the packet id is not 0: not our handshake, so fall through to nothing.
		LabelNode afterIdCheck = new LabelNode();
		// Where the peer announced itself as NeoForge: the original chain runs.
		LabelNode neoForgePath = new LabelNode();
		// Where the peer is anything else: runConfiguration(), then return.
		LabelNode vanillaPath = new LabelNode();
		LabelNode end = new LabelNode();

		InsnList replacement = new InsnList();
		// super.handlePong(packet);
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
		replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
				"net/minecraft/server/network/ServerCommonPacketListenerImpl",
				HANDLE_PONG, HANDLE_PONG_DESC, false));
		// if (packet.getId() == 0) goto afterIdCheck; else return;
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 1));
		replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
				"net/minecraft/network/protocol/common/ServerboundPongPacket", "getId", "()I", false));
		replacement.add(new JumpInsnNode(Opcodes.IFEQ, afterIdCheck));
		replacement.add(new InsnNode(Opcodes.RETURN));
		// afterIdCheck: if (KernelVanillaClientBridge.shouldNegotiate(this)) goto neoForgePath;
		replacement.add(afterIdCheck);
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
		replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK_OWNER, HOOK_NAME, HOOK_DESC, false));
		replacement.add(new JumpInsnNode(Opcodes.IFNE, neoForgePath));
		// vanillaPath: runConfiguration(); return;
		replacement.add(vanillaPath);
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
		replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET_INTERNAL,
				RUN_CONFIGURATION, RUN_CONFIGURATION_DESC, false));
		replacement.add(new JumpInsnNode(Opcodes.GOTO, end));
		// neoForgePath: the original chain — negotiate, and refuse a client this server cannot serve.
		//
		// Reached only for a peer that announced itself as NeoForge, so this is the call the base made
		// on the same path: initializeOtherConnection() still decides admission, still disconnects a
		// mismatched client with the same message, and still gates runConfiguration() on its result.
		replacement.add(neoForgePath);
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
		replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, NEO_REGISTRY,
				INITIALIZE_OTHER, INITIALIZE_OTHER_DESC, false));
		replacement.add(new JumpInsnNode(Opcodes.IFEQ, end));
		replacement.add(new VarInsnNode(Opcodes.ALOAD, 0));
		replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, TARGET_INTERNAL,
				RUN_CONFIGURATION, RUN_CONFIGURATION_DESC, false));
		replacement.add(end);
		replacement.add(new InsnNode(Opcodes.RETURN));

		pong.instructions = replacement;
		pong.tryCatchBlocks = null;
		pong.localVariables = null;
		pong.maxStack = 3;
		pong.maxLocals = 2;

		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/VanillaClient] %s.%s now branches on the peer type: a client that is "
					+ "not NeoForge runs the base's own runConfiguration() and receives this server's "
					+ "registry and tags, instead of being disconnected before it is synchronized",
					className, HANDLE_PONG);
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
			@Override
			protected String getCommonSuperClass(String left, String right) {
				return left.equals(right) ? left : "java/lang/Object";
			}
		};
		node.accept(writer);
		return writer.toByteArray();
	}
}
