/*
 * Forbric Bukkit bridge — the byte form of an item, as Paper's API defines it.
 */
package org.cn.xiaofan.bukkit;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;

/**
 * Converts items to and from the bytes {@code ItemStack.serializeAsBytes} promises.
 *
 * <p><b>The format is Paper's.</b> Reading {@code ItemStack.serializeItemsAsBytes} off the API
 * shows it is not the game's own encoding: it writes a version byte, then a count, then for each
 * item a length and that item's own bytes. The per-item bytes are the game's network form of a
 * stack — a registry-indexed item id and a component patch — which is what
 * {@link ItemStack#STREAM_CODEC} speaks.
 *
 * <p><b>Why this class exists at all.</b> A plugin's usual way to store an item is to call
 * {@code serializeAsBytes()} and write the array to disk or a database, then call
 * {@code deserializeBytes} to get it back. Both of those end in the API's own class, which needs
 * a CraftBukkit delegate this bridge does not have — so the bytes have to be produced and
 * consumed here, in the same shape, or the round trip loses the item.
 *
 * <p><b>{@link RegistryFriendlyByteBuf} and why the registry access matters.</b> A stack's
 * components can name other registry entries, so the game's codec is a
 * {@code StreamCodec<RegistryFriendlyByteBuf, ItemStack>} and refuses a plain buffer. The buffer
 * is built with the server's {@link RegistryAccess}, which is the same one the game would use:
 * anything else would encode ids against a different registry and produce bytes the game reads
 * back as a different item.
 */
final class ForbricItemCodec {

    private ForbricItemCodec() {
    }

    /** Paper's format version, written as the first byte of a multi-item array. */
    private static final int FORMAT_VERSION = 1;

    /**
     * Encodes one item.
     *
     * <p>An empty item — air, or a count of zero — encodes to an empty array rather than to a
     * zero-length payload, which is the same convention {@code serializeItemsAsBytes} uses for a
     * slot it means to leave blank.
     */
    static byte[] encode(org.bukkit.inventory.ItemStack stack) {
        if (stack == null || stack.getType() == org.bukkit.Material.AIR || stack.getAmount() <= 0) {
            return new byte[0];
        }
        ItemStack game = gameOf(stack);
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            ItemStack.STREAM_CODEC.encode(buffer, game);
        } catch (RuntimeException failure) {
            // A component the codec cannot name — a mod's data on an item whose registry entry is
            // missing. Naming it beats writing a truncated array a reader would then misparse.
            throw new UnsupportedOperationException(
                            "[Forbric/Bukkit] could not encode item " + stack.getType()
                                            + " in the game's network form", failure);
        }
        return take(buffer);
    }

    /**
     * Decodes one item.
     *
     * <p>An empty array is an empty slot, which is what the encoder writes for one — the two
     * agree so a round trip of air gives air.
     */
    static org.bukkit.inventory.ItemStack decode(byte[] data) {
        if (data == null || data.length == 0) {
            return ForbricItemStack.plain(org.bukkit.Material.AIR, 0);
        }
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeBytes(data);
        ItemStack game = ItemStack.STREAM_CODEC.decode(buffer);
        return ForbricItemStack.of(game);
    }

    /**
     * Encodes a list of items in the array format.
     *
     * <p>Mirrors {@code serializeItemsAsBytes} byte for byte: the version, the count, then each
     * item as a length-prefixed block. An empty slot is a length of zero rather than a block of
     * length zero, which is the same distinction the encoder makes for a single item and is what
     * keeps the count honest.
     */
    static byte[] encodeList(java.util.List<org.bukkit.inventory.ItemStack> stacks) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(FORMAT_VERSION);
            out.writeInt(stacks.size());
            for (org.bukkit.inventory.ItemStack stack : stacks) {
                byte[] encoded = encode(stack);
                out.writeInt(encoded.length);
                out.write(encoded);
            }
        } catch (IOException failure) {
            // ByteArrayOutputStream and DataOutputStream over it cannot fail; a signature made of
            // in-memory arrays has no I/O to lose. Reaching here means the JVM is broken.
            throw new UncheckedIOException("writing an item array to memory", failure);
        }
        return bytes.toByteArray();
    }

    /** Decodes the array format. */
    static org.bukkit.inventory.ItemStack[] decodeList(byte[] data) {
        if (data == null || data.length == 0) {
            return new org.bukkit.inventory.ItemStack[0];
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            int version = in.readUnsignedByte();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException(
                                "[Forbric/Bukkit] item array format version " + version
                                                + " is not one this bridge writes (" + FORMAT_VERSION + ")");
            }
            int count = in.readInt();
            if (count < 0) {
                throw new IllegalArgumentException(
                                "[Forbric/Bukkit] item array claims a negative length: " + count);
            }
            org.bukkit.inventory.ItemStack[] stacks = new org.bukkit.inventory.ItemStack[count];
            for (int i = 0; i < count; i++) {
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    throw new IllegalArgumentException(
                                    "[Forbric/Bukkit] item " + i + " claims " + length
                                                    + " bytes but only " + in.available() + " remain");
                }
                byte[] encoded = new byte[length];
                in.readFully(encoded);
                stacks[i] = decode(encoded);
            }
            return stacks;
        } catch (IOException failure) {
            throw new UncheckedIOException("reading an item array from memory", failure);
        }
    }

    /** The game's stack behind a plugin's item, materialised if the plugin built it from a material. */
    private static ItemStack gameOf(org.bukkit.inventory.ItemStack stack) {
        if (stack instanceof ForbricItemStack) {
            return ((ForbricItemStack) stack).toGame();
        }
        // A stack of the API's own class that did not come from this bridge: it has a delegate of
        // its own or none at all, and neither is ours to read. Refusing names the problem instead
        // of encoding an item whose components silently went missing.
        throw new UnsupportedOperationException(
                        "[Forbric/Bukkit] cannot read an ItemStack this bridge did not create;"
                                        + " build items with ItemStack.of(...) or take them from an inventory");
    }

    /**
     * The game's stack for a plugin's item, for the bridge's own use by an inventory.
     *
     * <p>The same conversion {@link #encode} needs, exposed because an inventory writes items too and
     * must produce exactly the same game object the encoder would: an item stored through one path
     * and serialised through the other has to agree.
     */
    static ItemStack gameStack(org.bukkit.inventory.ItemStack stack) {
        return gameOf(stack);
    }

    /**
     * The game's stack, or the game's empty constant when the plugin's item is air or null.
     *
     * <p>For the places that write a slot: air clears it, and the game spells that as its own empty
     * stack rather than a stack of an air item.
     */
    static ItemStack gameStackOrEmpty(org.bukkit.inventory.ItemStack stack) {
        if (stack == null || stack.getType() == org.bukkit.Material.AIR || stack.getAmount() <= 0) {
            return ItemStack.EMPTY;
        }
        return gameOf(stack);
    }

    /** A buffer over the server's registry access. */
    private static RegistryFriendlyByteBuf buffer() {
        RegistryAccess registries = ForbricRegistries.access();
        if (registries == null) {
            throw new IllegalStateException(
                            "[Forbric/Bukkit] the server's registry access is not available, so an item"
                                            + " cannot be (de)serialised yet");
        }
        return new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registries);
    }

    /** The bytes written so far, leaving the buffer's own storage free to be released. */
    private static byte[] take(RegistryFriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return bytes;
    }
}
