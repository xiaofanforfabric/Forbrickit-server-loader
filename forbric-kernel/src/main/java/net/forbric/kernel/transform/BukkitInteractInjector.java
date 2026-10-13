/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Raises Bukkit's {@code PlayerInteractEvent} from the GAME's own interaction methods.
 *
 * <p>A plugin's {@code @EventHandler} only runs if something tells the bridge an interaction happened, and the
 * obvious something — subscribing to the surviving loader's event bus and relaying it — is the wrong direction
 * for this bridge: it makes every Bukkit listener depend on one ecosystem's patches surviving the byte merge.
 * There is no relay here. The call sites below are vanilla game/network methods and the injected call is the
 * whole mechanism:
 *
 * <table>
 *   <caption>Injections</caption>
 *   <tr><th>Method</th><th>Raised action</th><th>A refusal means</th></tr>
 *   <tr><td>{@code ServerPlayerGameMode.useItemOn}</td><td>{@code RIGHT_CLICK_BLOCK}</td><td>return the hook's answer ({@code InteractionResult.FAIL})</td></tr>
 *   <tr><td>{@code ServerGamePacketListenerImpl.handleUseItem}</td><td>{@code RIGHT_CLICK_AIR}</td><td>return before the game-mode call</td></tr>
 *   <tr><td>{@code ServerPlayerGameMode.handleBlockBreakAction}</td><td>{@code LEFT_CLICK_BLOCK}</td><td>{@code return}, before any break starts</td></tr>
 *   <tr><td>{@code ServerGamePacketListenerImpl.handleAnimate}</td><td>{@code LEFT_CLICK_AIR}</td><td>{@code return} (the swing has nothing to suppress; the event is the point)</td></tr>
 * </table>
 *
 * <p><b>Why the head of the method, and not a call site.</b> Head insertion uses only the method's own arguments
 * and the class's own {@code player} field where the handler does not already supply one. The air-right-click hook
 * is deliberately placed after {@code hasClientLoaded}'s false branch, so it runs on the game thread rather than
 * the Netty thread that first receives the packet.
 *
 * <p><b>Breaking a block is the one site that is not inserted at the head.</b> {@code handleBlockBreakAction}
 * serves {@code START_DESTROY_BLOCK}, {@code ABORT_DESTROY_BLOCK} and {@code STOP_DESTROY_BLOCK}. A head
 * insertion would raise {@code LEFT_CLICK_BLOCK} for releases and interruptions too, so it is gated by the
 * action at method entry.
 *
 * <p><b>What a miss costs.</b> Each site is independent and each declines on its own: a method that is not the
 * reviewed shape is left exactly as it was, with one line naming it, and the other sites still apply. The frame
 * the injected branch needs is built from the target's own descriptor, so a merged body with different arguments
 * fails the shape check rather than being written with the wrong locals.
 *
 * <p>{@code -Dforbric.bukkitInteract=off} leaves both classes alone; plugin {@code @EventHandler} listeners then
 * never hear about an interaction, which is the state this transformer exists to end.
 */
public final class BukkitInteractInjector implements ClassTransformer {

	public static final String PROPERTY = "forbric.bukkitInteract";

	/**
	 * The game-side seam the injected calls land on, as an INTERNAL name.
	 *
	 * <p>Two spellings are in play in this class and mixing them costs every injection silently: {@link
	 * ClassTransformer#transform} hands over the BINARY (dot-separated) name, so the two target constants below are
	 * dotted, while everything written into bytecode — this class, the field owners, the descriptors — is internal.
	 */
	static final String HOOK = "org/cn/xiaofan/bukkit/ForbricInteractHook";

	static final String GAME_MODE = "net.minecraft.server.level.ServerPlayerGameMode";
	static final String LISTENER = "net.minecraft.server.network.ServerGamePacketListenerImpl";

	static final String PLAYER = "net/minecraft/server/level/ServerPlayer";
	static final String PLAYER_DESC = "L" + PLAYER + ";";
	static final String LEVEL_DESC = "Lnet/minecraft/world/level/Level;";
	static final String STACK_DESC = "Lnet/minecraft/world/item/ItemStack;";
	static final String HAND_DESC = "Lnet/minecraft/world/InteractionHand;";
	static final String USE_ITEM_PACKET_DESC = "Lnet/minecraft/network/protocol/game/ServerboundUseItemPacket;";
	static final String HIT_DESC = "Lnet/minecraft/world/phys/BlockHitResult;";
	static final String POS_DESC = "Lnet/minecraft/core/BlockPos;";
	static final String DIRECTION_DESC = "Lnet/minecraft/core/Direction;";
	static final String ACTION_DESC = "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;";
	static final String SWING_DESC = "Lnet/minecraft/network/protocol/game/ServerboundSwingPacket;";

	/** The action enum as an INTERNAL name, for matching the {@code START_DESTROY_BLOCK} comparison in a body. */
	static final String ACTION_INTERNAL = "net/minecraft/network/protocol/game/ServerboundPlayerActionPacket";

	/** A name for the message a refusal prints; the internal name above is what bytecode matching needs. */
	static final String ACTION = "ServerboundPlayerActionPacket";

	/**
	 * The local slot the action argument occupies in {@code handleBlockBreakAction}.
	 *
	 * <p>2, not 1: the method is an instance method, so slot 0 is {@code this}, the {@code BlockPos} argument is
	 * slot 1, and the action is the SECOND argument at slot 2. Named rather than inlined because the guard
	 * emitted at the head compares against it, and an off-by-one there would read the position as an enum.
	 * {@code withPlayer(node, 1, 3)} loads the same method's {@code pos} and {@code face} by this numbering.
	 */
	static final int ACTION_SLOT = 2;
	static final String RESULT = "net/minecraft/world/InteractionResult";
	static final String RESULT_DESC = "L" + RESULT + ";";

	static final String USE_ITEM_ON_DESC = "(" + PLAYER_DESC + LEVEL_DESC + STACK_DESC + HAND_DESC + HIT_DESC + ")" + RESULT_DESC;
	static final String BREAK_DESC = "(" + POS_DESC + ACTION_DESC + DIRECTION_DESC + "II)V";
	static final String LEFT_CLICK_DESC = "(" + PLAYER_DESC + POS_DESC + DIRECTION_DESC + ")Z";
	static final String USE_ITEM_PACKET_HOOK_DESC = "(" + PLAYER_DESC + USE_ITEM_PACKET_DESC + ")Z";
	static final String SWING_HOOK_DESC = "(" + PLAYER_DESC + ")Z";
	static final String ANIMATE_DESC = "(" + SWING_DESC + ")V";
	static final String HANDLE_USE_ITEM_DESC = "(" + USE_ITEM_PACKET_DESC + ")V";

	/** The field both target classes hold the interacting player in, read instead of re-deriving it from arguments. */
	static final String PLAYER_FIELD = "player";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-bukkit-interact";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(
				new AnchorSet.Anchor(GAME_MODE, AnchorSet.Severity.REQUIRED,
						"a plugin's @EventHandler never hears RIGHT_CLICK_BLOCK/AIR or LEFT_CLICK_BLOCK"),
					// REQUIRED, not HEDGE. Compensating B8 is this injection's whole point: if
					// handleAnimate's shape drifts, LEFT_CLICK_AIR silently goes back to being the gap
					// the PR closed — a HEDGE miss only reaches the summary, so nobody is told.
					new AnchorSet.Anchor(LISTENER, AnchorSet.Severity.REQUIRED,
							"a plugin's @EventHandler never hears LEFT_CLICK_AIR again"));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean gameMode = GAME_MODE.equals(className);
		boolean listener = LISTENER.equals(className);
		if (!gameMode && !listener) return bytes;

		ClassNode node = new ClassNode();
		// EXPAND_FRAMES, not 0. The merged bodies carry a real StackMapTable, and ASM keeps those frames in their
		// COMPRESSED form (F_APPEND / F_SAME / F_CHOP) when read with flags 0. An F_FULL written by hand cannot
		// be added to a list that still holds compressed frames: MethodWriter rejects the mix and throws
		// IllegalStateException out of visitFrame, which is what happened the first time this ran against the
		// real class. Expanding first turns every frame into an absolute F_NEW, so the one this transformer
		// writes and the ones already there are the same kind.
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);

		int changed = 0;
		// The hand and the hit result travel with the event; the player comes from the method's own argument
		// on these two, and from the class's field on the two that only see a block.
		if (gameMode) {
			changed += inject(node, "useItemOn", USE_ITEM_ON_DESC, "useItemOn", USE_ITEM_ON_DESC,
					Answer.RESULT, arguments(1, 2, 3, 4, 5), false, true);
			// NOT the head: this body serves START / ABORT / STOP and branches on the action argument, so a
			// guard at the entry would raise LEFT_CLICK_BLOCK on a release or an interruption too. Placed on
			// the START-only edge instead, which is where CraftBukkit raises it.
			changed += injectOnStartDestroy(node, "handleBlockBreakAction", BREAK_DESC);
		}
		if (listener) {
			changed += injectUseItemPacket(node);
			changed += inject(node, "handleAnimate", ANIMATE_DESC, "swing", SWING_HOOK_DESC,
					Answer.RETURN, withPlayer(node), true, false);
		}
		if (changed == 0) return bytes;

		ClassWriter writer = new FrameComputingWriter(ClassWriter.COMPUTE_FRAMES);
		node.accept(writer);
		ForbricLog.info("[Forbric/Bukkit] %s raises Bukkit's PlayerInteractEvent from %d game method(s) of its own "
				+ "— no loader bus is involved", simpleName(node.name), changed);
		return writer.toByteArray();
	}

	/** What an injected call decides: the value the game method returns, or that it returns at all. */
	private enum Answer { RESULT, RETURN }

	/**
	 * A {@link ClassWriter} that computes every stack map frame itself and never loads a class to do it.
	 *
	 * <p>{@code COMPUTE_FRAMES} is what this transformer needs. The hand-written {@code F_FULL} approach was
	 * tried twice and failed twice against the real merged class, for two different reasons: at a mid-body
	 * offset the frame's locals are not the entry locals (the body has already stored into slots 6 and 7), and
	 * mixing a hand-written absolute frame into a table ASM read in compressed form throws out of
	 * {@code visitFrame}. Letting ASM derive all of them removes the whole class of bug — the frames stop being
	 * something this file is responsible for.
	 *
	 * <p>The override is not optional. {@code COMPUTE_FRAMES} calls {@code getCommonSuperClass} to merge two
	 * reference types on a branch, and the default implementation LOADS both classes through this transformer's
	 * own class loader — which cannot see {@code net.minecraft.*} at the point this runs, so the default would
	 * throw {@code TypeNotPresentException} on the first branch that merges two game types. Answering
	 * {@code java/lang/Object} instead is the documented way out: it is always a valid common supertype, and the
	 * cost is a wider frame than strictly needed, which the verifier accepts.
	 */
	private static final class FrameComputingWriter extends ClassWriter {

		FrameComputingWriter(int flags) {
			super(flags);
		}

		@Override
		protected String getCommonSuperClass(String type1, String type2) {
			return "java/lang/Object";
		}
	}

	/**
	 * Prepends one guard to {@code methodName}: call the seam, and if it refused, leave the game method.
	 *
	 * @param playerField whether the argument loads read {@code this.player}; declined when the field is not there
	 * @param required    whether a miss is loud (the interaction's only source) or a word (a known gap)
	 * @return 1 when the method was edited, 0 when it was left alone (and why, in one line)
	 */
	private int inject(ClassNode node, String methodName, String methodDesc, String hookName, String hookDesc,
			Answer answer, InsnList loads, boolean playerField, boolean required) {
		MethodNode method = method(node, methodName, methodDesc);
		String where = simpleName(node.name) + "." + methodName;
		if (method == null) {
			return declined(where + " is not " + methodDesc + " on this base", required);
		}
		if ((method.access & Opcodes.ACC_STATIC) != 0) {
			return declined(where + " is static, so it has no player to raise an event for", required);
		}
		if (playerField && !hasPlayerField(node)) {
			return declined(where + " has no " + PLAYER + " " + PLAYER_FIELD + " field to read the player from",
					required);
		}
		if (alreadyInjected(method, hookName)) return 0;
		if (headIsBranchTarget(method)) {
			return declined(where + " starts on a branch target, where a prepended guard cannot own the entry",
					required);
		}

		Object[] locals = frameLocals(node, method);
		LabelNode proceed = new LabelNode();
		InsnList injected = new InsnList();
		injected.add(loads);
		injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hookName, hookDesc, false));
		if (answer == Answer.RESULT) {
			// The seam answers with the result the game must return, or null for "mine stands": the null is
			// carried to the fall-through and dropped there, so the original body runs untouched.
			//
			// The frame at the target is a FULL frame whose stack is the RESULT type. This is the FALL-THROUGH
			// edge of `IFNULL`, where the operand did NOT compare null, so the value is still
			// InteractionResult — DUP left two on the stack and ARETURN consumed one. Writing NULL here is a
			// VerifyError, and the verifier says so: "Type 'InteractionResult' (current frame, stack[0]) is
			// not assignable to null (stack map, stack[0])", raised on the first class load.
			injected.add(new InsnNode(Opcodes.DUP));
			injected.add(new JumpInsnNode(Opcodes.IFNULL, proceed));
			injected.add(new InsnNode(Opcodes.ARETURN));
			injected.add(proceed);
			injected.add(new InsnNode(Opcodes.POP));
		} else {
			injected.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
			injected.add(new InsnNode(Opcodes.RETURN));
			injected.add(proceed);
		}
		method.instructions.insert(injected);
		return 1;
	}

	/**
	 * Raises {@code LEFT_CLICK_BLOCK} only for {@code START_DESTROY_BLOCK}, from the method's HEAD.
	 *
	 * <p>{@code handleBlockBreakAction} is the one site where a plain entry guard would be WRONG: the same body
	 * serves {@code START_DESTROY_BLOCK}, {@code ABORT_DESTROY_BLOCK} and {@code STOP_DESTROY_BLOCK}, so an
	 * unguarded entry call raises {@code LEFT_CLICK_BLOCK} on a release or an interruption too — a plugin counting
	 * breaks sees the count double. CraftBukkit raises it inside the START case only.
	 *
	 * <p>So the guard is still installed at the head, but it TESTS THE ACTION ITSELF first and only calls the seam
	 * on the START edge. That is deliberate, and it is the second attempt: the first put the guard inside the body,
	 * on the branch the {@code action == START_DESTROY_BLOCK} comparison selects. That works on a hand-written
	 * stand-in and fails on the real merged class, because an {@code F_FULL} frame is ABSOLUTE and the locals at a
	 * mid-body offset are not the entry locals: the merged body has already stored a {@code float} and a
	 * {@code BlockState} into slots 6 and 7 by the time the START branch is reached, so a frame written from the
	 * descriptor's six entry slots overwrites them and the verifier rejects the class with "Inconsistent stackmap
	 * frames at branch target". At the head there is nothing to overwrite — every frame this method writes is the
	 * same shape as the other three sites, which is the property that makes them safe.
	 *
	 * <p><b>What "tests the action" means.</b> The comparison is emitted here rather than reused from the body, so
	 * this transformer never has to reason about the body's polarity or its branch targets. {@code action} is
	 * argument 1 on this descriptor, so the emitted code is:
	 *
	 * <pre>
	 *   aload_1; getstatic START_DESTROY_BLOCK; if_acmpne proceed
	 *   this.player, pos, face; invokestatic leftClickBlock; ifne return
	 * proceed: &lt;the original body&gt;
	 * </pre>
	 *
	 * <p>It declines if the descriptor is not the reviewed one, so a base that widens the method is left alone and
	 * named rather than guarded against the wrong argument slot.
	 */
	private int injectOnStartDestroy(ClassNode node, String methodName, String methodDesc) {
		String where = simpleName(node.name) + "." + methodName;
		MethodNode method = method(node, methodName, methodDesc);
		if (method == null) {
			return declined(where + " is not " + methodDesc + " on this base", true);
		}
		if ((method.access & Opcodes.ACC_STATIC) != 0) {
			return declined(where + " is static, so it has no player to raise an event for", true);
		}
		if (!hasPlayerField(node)) {
			return declined(where + " has no " + PLAYER + " " + PLAYER_FIELD + " field to read the player from",
					true);
		}
		if (alreadyInjected(method, "leftClickBlock")) return 0;
		if (headIsBranchTarget(method)) {
			return declined(where + " starts on a branch target, where a prepended guard cannot own the entry",
					true);
		}

		Object[] locals = frameLocals(node, method);
		LabelNode proceed = new LabelNode();
		LabelNode callHook = new LabelNode();
		InsnList injected = new InsnList();
		// if (action == START_DESTROY_BLOCK) take the hook; otherwise fall straight through to the body, which
		// is the abort/stop path and must not raise LEFT_CLICK_BLOCK.
		injected.add(new VarInsnNode(Opcodes.ALOAD, ACTION_SLOT));
		injected.add(new FieldInsnNode(Opcodes.GETSTATIC, ACTION_INTERNAL + "$Action", "START_DESTROY_BLOCK",
				ACTION_DESC));
		injected.add(new JumpInsnNode(Opcodes.IF_ACMPEQ, callHook));
		injected.add(new JumpInsnNode(Opcodes.GOTO, proceed));
		injected.add(callHook);
		injected.add(withPlayer(node, 1, 3));
		injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "leftClickBlock", LEFT_CLICK_DESC, false));
		injected.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
		injected.add(new InsnNode(Opcodes.RETURN));
		injected.add(proceed);

		method.instructions.insert(injected);
		return 1;
	}

	/**
	 * Raises {@code RIGHT_CLICK_AIR} in the packet handler, after vanilla has moved the packet onto the server
	 * thread and established that this connection is in the play state, but before the handler calls
	 * {@code ServerPlayerGameMode.useItem}.
	 *
	 * <p>Do not inject into {@code useItem}: that game-mode method is a later implementation detail, and the
	 * observed server accepted the use-item packet at y=300 but never reached that method in the merged runtime.
	 * The packet handler is the source-of-truth boundary: every air/item right click that the server accepts
	 * enters through {@code handleUseItem(ServerboundUseItemPacket)}. Injecting immediately after
	 * {@code hasClientLoaded()}'s false-return branch also matters: the method starts on a Netty thread and its
	 * first operation hands execution to the server thread. A head hook would run Bukkit listeners on Netty.

	 * <p>The hook returns true only when Bukkit cancelled or denied the interaction; in that case return from this
	 * void packet handler before vanilla calls {@code useItem}. Otherwise fall through and let the game handle it.
	 */
	private int injectUseItemPacket(ClassNode node) {
		String methodName = "handleUseItem";
		String where = simpleName(node.name) + "." + methodName;
		MethodNode method = method(node, methodName, HANDLE_USE_ITEM_DESC);
		if (method == null) {
			return declined(where + " is not " + HANDLE_USE_ITEM_DESC + " on this base", true);
		}
		if ((method.access & Opcodes.ACC_STATIC) != 0) {
			return declined(where + " is static, so it has no player to raise an event for", true);
		}
		if (!hasPlayerField(node)) {
			return declined(where + " has no " + PLAYER + " " + PLAYER_FIELD + " field to read the player from", true);
		}
		if (alreadyInjected(method, "useItemPacket")) return 0;

		JumpInsnNode loadedBranch = null;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (!(insn instanceof MethodInsnNode call) || !call.name.equals("hasClientLoaded")
					|| !call.desc.equals("()Z")) continue;
			AbstractInsnNode next = call.getNext();
			while (next != null && next.getOpcode() < 0) next = next.getNext();
			if (next instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
				loadedBranch = jump;
				break;
			}
			return declined(where + " hasClientLoaded() but not the reviewed false-return branch", true);
		}
		if (loadedBranch == null) {
			return declined(where + " has no hasClientLoaded() false-return branch to anchor the main-thread hook", true);
		}

		LabelNode proceed = new LabelNode();
		InsnList injected = new InsnList();
		injected.add(new VarInsnNode(Opcodes.ALOAD, 0));
		injected.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, PLAYER_FIELD, PLAYER_DESC));
		injected.add(new VarInsnNode(Opcodes.ALOAD, 1));
		injected.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "useItemPacket", USE_ITEM_PACKET_HOOK_DESC, false));
		injected.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
		injected.add(new InsnNode(Opcodes.RETURN));
		injected.add(proceed);
		method.instructions.insert(loadedBranch, injected);
		return 1;
	}

	/** The reviewed method, or null: name and descriptor both, so a widened overload is not silently taken. */
	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		return null;
	}

	private static boolean hasPlayerField(ClassNode node) {
		for (FieldNode field : node.fields) {
			if (field.name.equals(PLAYER_FIELD) && field.desc.equals(PLAYER_DESC)) return true;
		}
		return false;
	}

	/** True when the seam is already called in this body, so a second pass over the bytes changes nothing. */
	private static boolean alreadyInjected(MethodNode method, String hookName) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(HOOK) && call.name.equals(hookName)) return true;
		}
		return false;
	}

	/**
	 * True when the method's body begins on a label a branch jumps to.
	 *
	 * <p>Inserting a guard before such a label would put the guard inside a loop head rather than at the entry,
	 * and the frame the guard's own branch needs would collide with the one already there. Vanilla's four methods
	 * do not start that way; a base that does is left alone instead of being rewritten blind.
	 */
	private static boolean headIsBranchTarget(MethodNode method) {
		Set<LabelNode> targets = new HashSet<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof JumpInsnNode jump) targets.add(jump.label);
			else if (insn instanceof TableSwitchInsnNode table) {
				targets.add(table.dflt);
				targets.addAll(table.labels);
			} else if (insn instanceof LookupSwitchInsnNode lookup) {
				targets.add(lookup.dflt);
				targets.addAll(lookup.labels);
			}
		}
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) return false;
			if (insn instanceof LabelNode label && targets.contains(label)) return true;
		}
		return false;
	}

	/**
	 * The locals at the method's entry, in the form a stack map frame wants: the receiver then the arguments,
	 * with every primitive narrower than an int written as {@code INTEGER}. Computed from the descriptor so a
	 * frame is never guessed.
	 *
	 * <p>The receiver is written as the class the method is declared in — the type the JVM's own resolver would
	 * find — rather than as a name and nothing else. An {@code F_FULL} frame is ABSOLUTE: the verifier compares
	 * its locals slot by slot against the frame it derived, and a receiver whose written type is not assignable
	 * to the one the resolver produced is {@code Inconsistent stackmap frames at branch target}. Both target
	 * classes are exactly the receiver type of every method injected here (neither is subclassed in a base this
	 * transformer runs on), so the declared class is the correct answer; if a base ever subclasses one, the
	 * mutation makes the frame mismatch and the load fails loudly rather than silently.
	 */
	private static Object[] frameLocals(ClassNode node, MethodNode method) {
		List<Object> locals = new ArrayList<>();
		if ((method.access & Opcodes.ACC_STATIC) == 0) locals.add(node.name);
		for (Type argument : Type.getArgumentTypes(method.desc)) locals.add(frameType(argument));
		return locals.toArray();
	}

	private static Object frameType(Type type) {
		return switch (type.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> Opcodes.INTEGER;
			case Type.FLOAT -> Opcodes.FLOAT;
			case Type.LONG -> Opcodes.LONG;
			case Type.DOUBLE -> Opcodes.DOUBLE;
			default -> type.getInternalName();
		};
	}

	/** {@code this.player} followed by the named argument slots, in that order. */
	private static InsnList withPlayer(ClassNode node, int... slots) {
		InsnList loads = new InsnList();
		loads.add(new VarInsnNode(Opcodes.ALOAD, 0));
		loads.add(new FieldInsnNode(Opcodes.GETFIELD, node.name, PLAYER_FIELD, PLAYER_DESC));
		for (int slot : slots) loads.add(new VarInsnNode(Opcodes.ALOAD, slot));
		return loads;
	}

	/** The named argument slots, in that order. */
	private static InsnList arguments(int... slots) {
		InsnList loads = new InsnList();
		for (int slot : slots) loads.add(new VarInsnNode(Opcodes.ALOAD, slot));
		return loads;
	}

	/** Left alone, and why. Rung loudly on the class this transformer cannot afford to miss, quietly elsewhere. */
	private static int declined(String reason, boolean required) {
		String cost = " — left as it is, so a plugin's @EventHandler will not hear this interaction";
		if (required) {
			ForbricLog.warn("[Forbric/Bukkit] %s%s", reason, cost);
		} else {
			ForbricLog.info("[Forbric/Bukkit] %s%s", reason, cost);
		}
		return 0;
	}

	private static String simpleName(String internal) {
		return internal.substring(internal.lastIndexOf('/') + 1);
	}
}
