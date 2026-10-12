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
 * for this bridge: it makes every Bukkit listener depend on one ecosystem's patches surviving the byte merge,
 * and NeoForge's own hook is what a plugin's code then has to be shaped like. There is no relay here. The four
 * call sites below are the vanilla bodies the game itself runs, and the injected call is the whole mechanism:
 *
 * <table>
 *   <caption>Injections</caption>
 *   <tr><th>Method</th><th>Raised action</th><th>A refusal means</th></tr>
 *   <tr><td>{@code ServerPlayerGameMode.useItemOn}</td><td>{@code RIGHT_CLICK_BLOCK}</td><td>return the hook's answer ({@code InteractionResult.FAIL})</td></tr>
 *   <tr><td>{@code ServerPlayerGameMode.useItem}</td><td>{@code RIGHT_CLICK_AIR}</td><td>return the hook's answer</td></tr>
 *   <tr><td>{@code ServerPlayerGameMode.handleBlockBreakAction}</td><td>{@code LEFT_CLICK_BLOCK}</td><td>{@code return}, before any break starts</td></tr>
 *   <tr><td>{@code ServerGamePacketListenerImpl.handleAnimate}</td><td>{@code LEFT_CLICK_AIR}</td><td>{@code return} (the swing has nothing to suppress; the event is the point)</td></tr>
 * </table>
 *
 * <p><b>Why the head of the method, and not a call site.</b> Head insertion uses only the method's own arguments
 * and the class's own {@code player} field: there is no anchor to go stale when a patch (either ecosystem's, or a
 * mod's) rewrites the body, and no ordinal to renumber. What it costs is the exact position within the body,
 * which is not a contract this bridge promises — CraftBukkit raises the event at the same entry point.
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
	static final String HIT_DESC = "Lnet/minecraft/world/phys/BlockHitResult;";
	static final String POS_DESC = "Lnet/minecraft/core/BlockPos;";
	static final String DIRECTION_DESC = "Lnet/minecraft/core/Direction;";
	static final String ACTION_DESC = "Lnet/minecraft/network/protocol/game/ServerboundPlayerActionPacket$Action;";
	static final String SWING_DESC = "Lnet/minecraft/network/protocol/game/ServerboundSwingPacket;";
	static final String RESULT = "net/minecraft/world/InteractionResult";
	static final String RESULT_DESC = "L" + RESULT + ";";

	static final String USE_ITEM_ON_DESC = "(" + PLAYER_DESC + LEVEL_DESC + STACK_DESC + HAND_DESC + HIT_DESC + ")" + RESULT_DESC;
	static final String USE_ITEM_DESC = "(" + PLAYER_DESC + LEVEL_DESC + STACK_DESC + HAND_DESC + ")" + RESULT_DESC;
	static final String BREAK_DESC = "(" + POS_DESC + ACTION_DESC + DIRECTION_DESC + "II)V";
	static final String LEFT_CLICK_DESC = "(" + PLAYER_DESC + POS_DESC + DIRECTION_DESC + ")Z";
	static final String SWING_HOOK_DESC = "(" + PLAYER_DESC + ")Z";
	static final String ANIMATE_DESC = "(" + SWING_DESC + ")V";

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
				new AnchorSet.Anchor(LISTENER, AnchorSet.Severity.HEDGE,
						"LEFT_CLICK_AIR stays unraised, as it was before this injection"));
	}

	@Override
	public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean gameMode = GAME_MODE.equals(className);
		boolean listener = LISTENER.equals(className);
		if (!gameMode && !listener) return bytes;

		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);

		int changed = 0;
		// The hand and the hit result travel with the event; the player comes from the method's own argument
		// on these two, and from the class's field on the two that only see a block.
		if (gameMode) {
			changed += inject(node, "useItemOn", USE_ITEM_ON_DESC, "useItemOn", USE_ITEM_ON_DESC,
					Answer.RESULT, arguments(1, 2, 3, 4, 5), false, true);
			changed += inject(node, "useItem", USE_ITEM_DESC, "useItem", USE_ITEM_DESC,
					Answer.RESULT, arguments(1, 2, 3, 4), false, true);
			changed += inject(node, "handleBlockBreakAction", BREAK_DESC, "leftClickBlock", LEFT_CLICK_DESC,
					Answer.RETURN, withPlayer(node, 1, 3), true, true);
		}
		if (listener) {
			changed += inject(node, "handleAnimate", ANIMATE_DESC, "swing", SWING_HOOK_DESC,
					Answer.RETURN, withPlayer(node), true, false);
		}
		if (changed == 0) return bytes;

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Bukkit] %s raises Bukkit's PlayerInteractEvent from %d game method(s) of its own "
				+ "— no loader bus is involved", simpleName(node.name), changed);
		return writer.toByteArray();
	}

	/** What an injected call decides: the value the game method returns, or that it returns at all. */
	private enum Answer { RESULT, RETURN }

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
			// The frame at the target is a FULL frame whose stack is the RESULT type, not NULL. A verifier does
			// not narrow the type on the null branch — it keeps the local's declared type — so writing NULL here
			// is a VerifyError ("Type InteractionResult is not assignable to null") on the first class load.
			injected.add(new InsnNode(Opcodes.DUP));
			injected.add(new JumpInsnNode(Opcodes.IFNULL, proceed));
			injected.add(new InsnNode(Opcodes.ARETURN));
			injected.add(proceed);
			injected.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 1, new Object[] { RESULT }));
			injected.add(new InsnNode(Opcodes.POP));
		} else {
			injected.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
			injected.add(new InsnNode(Opcodes.RETURN));
			injected.add(proceed);
			injected.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 0, new Object[0]));
		}
		method.instructions.insert(injected);
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
