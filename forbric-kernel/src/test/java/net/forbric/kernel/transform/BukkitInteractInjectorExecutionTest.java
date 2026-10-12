/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;

/**
 * {@link BukkitInteractInjector}'s output, run: the game's own interaction methods raise the bridge's
 * {@code PlayerInteractEvent} seam, and what the seam answers decides whether the game performs the action.
 *
 * <p>Nothing here names a Bukkit type, and that is the property under test as much as the injection is: the seam the
 * injected calls land on is a recording stand-in under {@code ForbricInteractHook}'s name, so what is proven is that
 * the edited {@code ServerPlayerGameMode} and {@code ServerGamePacketListenerImpl} load, link against the seam, reach
 * it on the path the game itself takes, and keep the game's own answer when the seam declines to give one.
 *
 * <p>The stand-ins keep the reviewed shapes: each target holds {@code player}, the two right-click paths answer with
 * an {@code InteractionResult} and the two left-click paths only act, so a refusal has something to suppress.
 */
@ExecutesInjector(BukkitInteractInjector.class)
class BukkitInteractInjectorExecutionTest {
	private static final String GAME_MODE = BukkitInteractInjector.GAME_MODE;
	private static final String LISTENER = BukkitInteractInjector.LISTENER;
	private static final String HOOK = BukkitInteractInjector.HOOK.replace('/', '.');

	private static final Map<String, String> STAND_INS = Map.ofEntries(
			Map.entry("net.minecraft.server.level.ServerPlayer", """
					package net.minecraft.server.level;

					public class ServerPlayer { }
					"""),
			Map.entry("net.minecraft.world.level.Level", """
					package net.minecraft.world.level;

					public class Level { }
					"""),
			Map.entry("net.minecraft.world.item.ItemStack", """
					package net.minecraft.world.item;

					public class ItemStack { }
					"""),
			Map.entry("net.minecraft.world.InteractionHand", """
					package net.minecraft.world;

					public enum InteractionHand { MAIN_HAND, OFF_HAND }
					"""),
			Map.entry("net.minecraft.world.InteractionResult", """
					package net.minecraft.world;

					public enum InteractionResult { SUCCESS, CONSUME, FAIL }
					"""),
			Map.entry("net.minecraft.world.phys.BlockHitResult", """
					package net.minecraft.world.phys;

					public class BlockHitResult { }
					"""),
			Map.entry("net.minecraft.core.Direction", """
					package net.minecraft.core;

					public enum Direction { NORTH }
					"""),
			Map.entry("net.minecraft.core.BlockPos", """
					package net.minecraft.core;

					public class BlockPos {
						public final int x, y, z;

						public BlockPos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
					}
					"""),
			Map.entry("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket", """
					package net.minecraft.network.protocol.game;

					public class ServerboundPlayerActionPacket {
						public enum Action { START_DESTROY_BLOCK, ABORT_DESTROY_BLOCK, STOP_DESTROY_BLOCK }
					}
					"""),
			Map.entry("net.minecraft.network.protocol.game.ServerboundSwingPacket", """
					package net.minecraft.network.protocol.game;

					public class ServerboundSwingPacket { }
					"""),
			Map.entry(HOOK, """
					package org.cn.xiaofan.bukkit;

					import net.minecraft.core.BlockPos;
					import net.minecraft.core.Direction;
					import net.minecraft.server.level.ServerPlayer;
					import net.minecraft.world.InteractionHand;
					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.ItemStack;
					import net.minecraft.world.level.Level;
					import net.minecraft.world.phys.BlockHitResult;

					/** The seam's stand-in: counts what reaches it and answers what the test asked for. */
					public final class ForbricInteractHook {
						public static int calls;
						private static boolean refuse;

						/** Named for the behaviour rather than a field, so the test flips it through the seam. */
						public static void refuse(boolean value) { refuse = value; }

						public static InteractionResult useItemOn(ServerPlayer player, Level level, ItemStack stack,
								InteractionHand hand, BlockHitResult hit) {
							calls++;
							return refuse ? InteractionResult.FAIL : null;
						}

						public static InteractionResult useItem(ServerPlayer player, Level level, ItemStack stack,
								InteractionHand hand) {
							calls++;
							return refuse ? InteractionResult.FAIL : null;
						}

						public static boolean leftClickBlock(ServerPlayer player, BlockPos pos, Direction face) {
							calls++;
							return refuse;
						}

						public static boolean swing(ServerPlayer player) {
							calls++;
							return refuse;
						}
					}
					"""),
			Map.entry(GAME_MODE, """
					package net.minecraft.server.level;

					import net.minecraft.core.BlockPos;
					import net.minecraft.core.Direction;
					import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
					import net.minecraft.world.InteractionHand;
					import net.minecraft.world.InteractionResult;
					import net.minecraft.world.item.ItemStack;
					import net.minecraft.world.level.Level;
					import net.minecraft.world.phys.BlockHitResult;

					public class ServerPlayerGameMode {
						public ServerPlayer player;
						public static int breaks;

						public ServerPlayerGameMode(ServerPlayer player) { this.player = player; }

						public InteractionResult useItemOn(ServerPlayer player, Level level, ItemStack stack,
								InteractionHand hand, BlockHitResult hit) {
							return InteractionResult.SUCCESS;
						}

						public InteractionResult useItem(ServerPlayer player, Level level, ItemStack stack,
								InteractionHand hand) {
							return InteractionResult.CONSUME;
						}

						public void handleBlockBreakAction(BlockPos pos, ServerboundPlayerActionPacket.Action action,
								Direction face, int a, int b) {
							breaks++;
						}
					}
					"""),
			Map.entry(LISTENER, """
					package net.minecraft.server.network;

					import net.minecraft.network.protocol.game.ServerboundSwingPacket;
					import net.minecraft.server.level.ServerPlayer;

					public class ServerGamePacketListenerImpl {
						public ServerPlayer player;
						public static int swings;

						public ServerGamePacketListenerImpl(ServerPlayer player) { this.player = player; }

						public void handleAnimate(ServerboundSwingPacket packet) {
							swings++;
						}
					}
					"""));

	@Test void theGamesOwnMethodsRaiseTheSeamAndWhatItAnswersDecidesTheAction(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		Map<String, byte[]> classes = new HashMap<>(original);
		for (String target : List.of(GAME_MODE, LISTENER)) {
			String internal = target.replace('.', '/');
			byte[] changed = InjectorExecution.transform(new BukkitInteractInjector(), target, original.get(internal),
					EnvType.SERVER);
			assertNotSame(original.get(internal), changed, target + " was not injected at all");
			classes.put(internal, changed);
		}

		ClassLoader loader = InjectorExecution.load(classes);
		for (String target : List.of(GAME_MODE, LISTENER)) {
			assertEquals("", InjectorExecution.verify(classes.get(target.replace('.', '/')), loader), target);
		}

		Class<?> playerType = loader.loadClass("net.minecraft.server.level.ServerPlayer");
		Class<?> levelType = loader.loadClass("net.minecraft.world.level.Level");
		Class<?> stackType = loader.loadClass("net.minecraft.world.item.ItemStack");
		Class<?> handType = loader.loadClass("net.minecraft.world.InteractionHand");
		Class<?> hitType = loader.loadClass("net.minecraft.world.phys.BlockHitResult");
		Class<?> posType = loader.loadClass("net.minecraft.core.BlockPos");
		Class<?> directionType = loader.loadClass("net.minecraft.core.Direction");
		Class<?> actionType = loader.loadClass("net.minecraft.network.protocol.game.ServerboundPlayerActionPacket$Action");
		Class<?> swingType = loader.loadClass("net.minecraft.network.protocol.game.ServerboundSwingPacket");
		Class<?> hookType = loader.loadClass(HOOK);
		Class<?> gameModeType = loader.loadClass(GAME_MODE);
		Class<?> listenerType = loader.loadClass(LISTENER);

		Object player = InjectorExecution.construct(playerType);
		Object level = InjectorExecution.construct(levelType);
		Object stack = InjectorExecution.construct(stackType);
		Object hit = InjectorExecution.construct(hitType);
		Object hand = constant(handType, "MAIN_HAND");
		Object pos = InjectorExecution.construct(posType, 1, 2, 3);
		Object north = constant(directionType, "NORTH");
		Object startDestroy = constant(actionType, "START_DESTROY_BLOCK");
		Object mode = InjectorExecution.construct(gameModeType, player);
		Object listener = InjectorExecution.construct(listenerType, player);
		Object packet = InjectorExecution.construct(swingType);

		InjectorExecution.invokeStatic(hookType, "refuse", false);
		assertEquals("SUCCESS", String.valueOf(InjectorExecution.invoke(mode, "useItemOn", player, level, stack, hand, hit)),
				"nothing refused: the game's own answer stands, so the injected guard is transparent");
		InjectorExecution.invokeStatic(hookType, "refuse", true);
		assertEquals("FAIL", String.valueOf(InjectorExecution.invoke(mode, "useItemOn", player, level, stack, hand, hit)),
				"refused RIGHT_CLICK_BLOCK: the seam's answer is what the game returns");

		InjectorExecution.invokeStatic(hookType, "refuse", false);
		assertEquals("CONSUME", String.valueOf(InjectorExecution.invoke(mode, "useItem", player, level, stack, hand)),
				"nothing refused: useItem keeps its body");
		InjectorExecution.invokeStatic(hookType, "refuse", true);
		assertEquals("FAIL", String.valueOf(InjectorExecution.invoke(mode, "useItem", player, level, stack, hand)),
				"refused RIGHT_CLICK_AIR");

		InjectorExecution.invokeStatic(hookType, "refuse", true);
		InjectorExecution.invoke(mode, "handleBlockBreakAction", pos, startDestroy, north, 0, 0);
		assertEquals(0, number(InjectorExecution.getStatic(gameModeType, "breaks")),
				"refused LEFT_CLICK_BLOCK: the break never starts");
		InjectorExecution.invokeStatic(hookType, "refuse", false);
		InjectorExecution.invoke(mode, "handleBlockBreakAction", pos, startDestroy, north, 0, 0);
		assertEquals(1, number(InjectorExecution.getStatic(gameModeType, "breaks")), "allowed: the break starts");

		InjectorExecution.invokeStatic(hookType, "refuse", true);
		InjectorExecution.invoke(listener, "handleAnimate", packet);
		assertEquals(0, number(InjectorExecution.getStatic(listenerType, "swings")),
				"refused LEFT_CLICK_AIR: the swing is not handled");
		InjectorExecution.invokeStatic(hookType, "refuse", false);
		InjectorExecution.invoke(listener, "handleAnimate", packet);
		assertEquals(1, number(InjectorExecution.getStatic(listenerType, "swings")), "allowed: the swing is handled");

		assertEquals(8, number(InjectorExecution.getStatic(hookType, "calls")),
				"all four sites reach the seam — two right-click answers and two left-click vetoes");
	}

	/** The transform runs on every load of the class, so the second pass over its own output must be a no-op. */
	@Test void aSecondPassOverTheInjectedBytesChangesNothing(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		BukkitInteractInjector injector = new BukkitInteractInjector();
		for (String target : List.of(GAME_MODE, LISTENER)) {
			byte[] once = InjectorExecution.transform(injector, target, original.get(target.replace('.', '/')),
					EnvType.SERVER);
			assertNotSame(original.get(target.replace('.', '/')), once, target);
			assertSame(once, InjectorExecution.transform(injector, target, once, EnvType.SERVER), target);
		}
	}

	/** A class this transformer does not own, and the switch being off, both leave the bytes alone. */
	@Test void theSwitchAndAClassItDoesNotOwnLeaveTheBytesAlone(@TempDir Path work) throws Exception {
		Map<String, byte[]> original = InjectorExecution.compile(work, STAND_INS);
		byte[] gameMode = original.get(GAME_MODE.replace('.', '/'));
		byte[] unrelated = original.get("net/minecraft/server/level/ServerPlayer");
		assertSame(unrelated, InjectorExecution.transform(new BukkitInteractInjector(),
				"net.minecraft.server.level.ServerPlayer", unrelated, EnvType.SERVER),
				"an unrelated class is not this transformer's to edit");

		System.setProperty(BukkitInteractInjector.PROPERTY, "off");
		try {
			assertSame(gameMode, InjectorExecution.transform(new BukkitInteractInjector(), GAME_MODE, gameMode,
					EnvType.SERVER), "the switch leaves the game's own methods as they were");
		} finally {
			System.clearProperty(BukkitInteractInjector.PROPERTY);
		}
	}

	private static Object constant(Class<?> enumType, String name) {
		return Enum.valueOf(enumType.asSubclass(Enum.class), name);
	}

	private static int number(Object value) {
		return ((Number) value).intValue();
	}
}
