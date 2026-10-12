/*
 * Forbric Bukkit bridge — the one place a game interaction becomes a Bukkit event.
 *
 * Compiled into the kernel's game-side jar, so it may name game and NeoForge types. The boot side
 * reaches {@link #install(Object, Object, Object)} by reflection (see {@code GameEventMultiplexer}),
 * because that half links no game type at all.
 */
package org.cn.xiaofan.bukkit;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Turns the game's own right-click interactions into Bukkit's {@code PlayerInteractEvent}.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>{@link ForbricPluginManager#callEvent} was implemented and never called: nothing in the bridge
 * listened to the game. A plugin that registers {@code @EventHandler} therefore had its listener
 * recorded and never invoked — the plugin loaded, its command ran, and the right-click path did
 * nothing, silently. This is the missing direction: game to Bukkit.
 *
 * <h2>Which game events</h2>
 *
 * <table>
 *   <caption>Action mapping</caption>
 *   <tr><th>Bukkit {@code Action}</th><th>NeoForge event</th></tr>
 *   <tr><td>{@code RIGHT_CLICK_AIR}</td><td>{@code RightClickItem}</td></tr>
 *   <tr><td>{@code RIGHT_CLICK_BLOCK}</td><td>{@code RightClickBlock}</td></tr>
 *   <tr><td>{@code LEFT_CLICK_BLOCK}</td><td>{@code LeftClickBlock}</td></tr>
 * </table>
 *
 * <p>{@code LEFT_CLICK_AIR} is deliberately absent. Bukkit has the action, but the game has no
 * server-side event for swinging at nothing: the animation is client-side and
 * {@code ServerGamePacketListenerImpl} drops the swing packet. Subscribing something that never
 * fires would be worse than not subscribing it, so the omission is stated here instead.
 *
 * <h2>Cancelling</h2>
 *
 * <p>Cancelling the Bukkit event cancels the NeoForge event, which is the flag the merged base
 * branches on. {@code RightClickItem} carries the flag too, so the three are handled alike even
 * though the air form has no server-side block interaction to suppress.
 *
 * <p>The two {@code getUseItemInHand}/{@code getUseInteractedBlock} decisions are <b>not</b> carried
 * back. NeoForge's interaction events expose cancellation and an {@code InteractionResult}, while
 * Bukkit's expose a tri-state {@code Result} — the two are not the same shape, and writing one into
 * the other would need a field the game event does not have. A plugin that reads them still gets the
 * event's own defaults; it simply cannot force {@code ALLOW} or {@code DENY} through this path. That
 * is a real limitation, named rather than hidden.
 */
public final class BukkitInteractBridge {

	private BukkitInteractBridge() {
	}

	/**
	 * Subscribes the Bukkit-facing forwards to the game's interaction events.
	 *
	 * <p>Reached from the boot side through the runtime class table, typed as {@code Object}
	 * throughout: that half cannot name {@code IEventBus}, {@link ServerImpl} or
	 * {@link ForbricPluginManager}, and a parameter typed as one would drag this class's whole
	 * dependency closure onto its signature.
	 *
	 * @param neoBus  NeoForge's {@code IEventBus}, handed over untyped by the boot side
	 * @param server  the running bridge server, the thing a {@code Player} is wrapped against
	 * @param manager the plugin manager that dispatches to plugin listeners
	 */
	public static void install(Object neoBus, Object server, Object manager) {
		if (!(neoBus instanceof IEventBus bus) || !(server instanceof ServerImpl impl)
				|| !(manager instanceof ForbricPluginManager plugins)) {
			return;
		}

		// LOWEST, like every other forward in this package: a plugin observing the event must
		// read the state the game has settled on, not a state a higher-priority hook is still
		// rewriting.
		bus.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.RightClickItem.class,
				forward(impl, plugins, Action.RIGHT_CLICK_AIR));
		bus.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.RightClickBlock.class,
				forward(impl, plugins, Action.RIGHT_CLICK_BLOCK));
		bus.addListener(EventPriority.LOWEST, false, PlayerInteractEvent.LeftClickBlock.class,
				forward(impl, plugins, Action.LEFT_CLICK_BLOCK));
	}

	/**
	 * One forward, parameterised by the Bukkit action it reports.
	 *
	 * <p>A listener per event class rather than one listener with a branch: the three arrive on
	 * three different types, so a single listener would take the common supertype and then cast
	 * back down, which is a cast the bus has already proven for each registration.
	 */
	private static <E extends PlayerInteractEvent> Consumer<E> forward(
			ServerImpl server, ForbricPluginManager manager, Action action) {
		AtomicBoolean warned = new AtomicBoolean();

		return gameEvent -> {
			try {
				fire(server, manager, action, gameEvent);
			} catch (Throwable t) {
				// Swallowed with ONE warning, like the kernel's own forwards. A plugin throwing
				// inside callEvent must not take the server tick down with it, and repeating the
				// message on every right-click would bury the log.
				if (warned.compareAndSet(false, true)) {
					server.getLogger().warning(
							"[Forbric/Bukkit] PlayerInteractEvent forward failed; plugins will "
									+ "not observe interactions - " + t);
				}
			}
		};
	}

	private static void fire(ServerImpl server, ForbricPluginManager manager, Action action,
			PlayerInteractEvent gameEvent) {
		// RightClickBlock and LeftClickBlock address a block; RightClickItem does not. Tested by
		// the event's own kind rather than by comparing getPos() against a sentinel, because
		// BlockPos.ZERO is a real coordinate a player can click.
		boolean onBlock = gameEvent instanceof PlayerInteractEvent.RightClickBlock
				|| gameEvent instanceof PlayerInteractEvent.LeftClickBlock;

		// A client-side post or a fake player has no ServerPlayer to wrap, and inventing a
		// wrapper whose every call fails is worse than reporting nothing.
		if (!(gameEvent.getEntity() instanceof ServerPlayer serverPlayer)) {
			return;
		}

		org.bukkit.entity.Player player = ForbricPlayer.of(serverPlayer, server);

		// The live hand stack, not the event's copy, so a plugin that mutates what it is handed
		// mutates what the player is holding.
		ItemStack held = serverPlayer.getItemInHand(gameEvent.getHand());
		org.bukkit.inventory.ItemStack item = held.isEmpty() ? null : ForbricItemStack.of(held);

		Block block = null;
		BlockFace face = null;
		if (onBlock) {
			block = blockAt(server, gameEvent.getLevel(), gameEvent.getPos());
			face = ForbricWorld.blockFace(gameEvent.getFace());
		}

		EquipmentSlot hand = gameEvent.getHand() == InteractionHand.MAIN_HAND
				? EquipmentSlot.HAND
				: EquipmentSlot.OFF_HAND;

		org.bukkit.event.player.PlayerInteractEvent bukkitEvent =
				new org.bukkit.event.player.PlayerInteractEvent(
						player, action, item, block, face, hand);

		manager.callEvent(bukkitEvent);

		if (bukkitEvent.isCancelled()) {
			// setCanceled lives on NeoForge's ICancellableEvent, not on the event class itself, so
			// the interface is what the call goes through.
			((net.neoforged.bus.api.ICancellableEvent) gameEvent).setCanceled(true);
		}
	}

	/** A block at a game position, wrapped so it can be written back to the same world. */
	private static Block blockAt(ServerImpl server, Object level, BlockPos pos) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return null;
		}
		return ForbricBlock.at(ForbricWorld.of(serverLevel, server),
				pos.getX(), pos.getY(), pos.getZ());
	}
}
