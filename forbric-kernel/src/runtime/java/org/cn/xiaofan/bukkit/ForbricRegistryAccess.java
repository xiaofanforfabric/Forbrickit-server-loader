/*
 * Forbric Bukkit bridge — the {@code RegistryAccess} Paper's API insists on finding.
 */
package org.cn.xiaofan.bukkit;

import java.util.Collection;
import java.util.stream.Stream;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.tag.Tag;
import io.papermc.paper.registry.tag.TagKey;

/**
 * The game's registries, in the shape {@code org.bukkit.Registry} asks for them.
 *
 * <p><b>Why this class has to exist.</b> {@code Registry.ITEM} and its siblings are not plain
 * fields: their static initialiser calls {@code RegistryAccess.getRegistry(key)}, and
 * {@code RegistryAccess.registryAccess()} is a {@code ServiceLoader} lookup, exactly like
 * {@code InternalAPIBridge}. On Paper the provider lives in the server jar; a bridge that ships only
 * the API has none, so the lookup comes back empty and the API throws — the first time any plugin
 * touches a registry.
 *
 * <p><b>Why it matters beyond one registry.</b> {@code Material.asItemType()} resolves its
 * {@code ItemType} through {@code Registry.ITEM}, and {@code ItemStack.of(material, amount)} goes
 * through {@code asItemType().createItemStack()}. So this provider is on the path of every
 * {@code new ItemStack(...)} a plugin writes — without it that constructor throws, which reads as a
 * broken API rather than a missing service.
 *
 * <p><b>Item types are the ones answered here.</b> {@code RegistryKey.ITEM} is what the item path
 * needs; the other registry keys a plugin may ask for are refused by name, because this bridge has
 * no {@code ItemType} table to give for them and an empty registry would make every lookup return
 * null — a plugin iterating blocks would find none, and conclude the game has no blocks.
 */
public final class ForbricRegistryAccess implements RegistryAccess {

    /** Required by {@link java.util.ServiceLoader}. */
    public ForbricRegistryAccess() {
    }

    /**
     * The {@code ItemType} registry, when the caller asks for an {@code ItemType} registry.
     *
     * <p>{@code RegistryKey} is not a real type token — it is a named handle, and Paper ships a
     * different one per registry. The check therefore has to be by the class, and
     * {@code RegistryKey.ITEM} is the only one this bridge can fill.
     */
    @Override
    public <T extends org.bukkit.Keyed> org.bukkit.Registry<T> getRegistry(RegistryKey<T> key) {
        if (key == RegistryKey.ITEM) {
            @SuppressWarnings("unchecked")
            org.bukkit.Registry<T> items = (org.bukkit.Registry<T>) ForbricItemRegistry.INSTANCE;
            return items;
        }
        throw new UnsupportedOperationException(
                        "[Forbric/Bukkit] the registry " + key + " is not implemented by this bridge yet");
    }

    /**
     * The same, by class, which is how {@code Registry.MATERIAL} and the older registry constants
     * reach it.
     *
     * <p>Only {@code ItemType} is answered. Paper reaches two shapes of the same registry — by key
     * and by class — and a bridge that answered one and not the other would work for a plugin that
     * used {@code Registry.ITEM} and fail for one that used {@code Material.asItemType()} on a
     * different code path.
     */
    @Override
    public <T extends org.bukkit.Keyed> org.bukkit.Registry<T> getRegistry(Class<T> type) {
        if (type == org.bukkit.inventory.ItemType.class) {
            @SuppressWarnings("unchecked")
            org.bukkit.Registry<T> items = (org.bukkit.Registry<T>) ForbricItemRegistry.INSTANCE;
            return items;
        }
        throw new UnsupportedOperationException(
                        "[Forbric/Bukkit] the registry for " + type.getName()
                                        + " is not implemented by this bridge yet");
    }

    /**
     * The {@code ItemType} registry itself.
     *
     * <p>Read from the game's item registry on every call rather than snapshotted: the world loads
     * after the service is first asked for a registry, and a snapshot taken before that would be
     * missing every item the game adds during its own bootstrap.
     *
     * <p>An empty registry before the server has installed the game's registries, and this is a
     * deliberate choice over throwing: Paper's contract is that a registry exists for the life of
     * the server, and a plugin that reads it very early — during its own bootstrap — is asking a
     * question whose answer is genuinely "nothing yet". {@link #size()} is honest about that, and
     * every lookup returns null, which is the same null a plugin gets for a name that is not in the
     * registry. Throwing here would instead break a plugin's class initialiser.
     */
    static final class ForbricItemRegistry implements org.bukkit.Registry<org.bukkit.inventory.ItemType> {

        static final ForbricItemRegistry INSTANCE = new ForbricItemRegistry();

        private ForbricItemRegistry() {
        }

        /** The game's item ids, as this bridge sees them, in the order the game keeps them. */
        private java.util.List<org.bukkit.NamespacedKey> keys() {
            net.minecraft.core.RegistryAccess registries = ForbricRegistries.access();
            if (registries == null) {
                return java.util.List.of();
            }
            return registries.lookupOrThrow(net.minecraft.core.registries.Registries.ITEM)
                            .keySet().stream()
                            .map(id -> new org.bukkit.NamespacedKey(id.getNamespace(), id.getPath()))
                            .toList();
        }

        @Override
        public org.bukkit.inventory.ItemType get(org.bukkit.NamespacedKey key) {
            if (key == null) {
                return null;
            }
            // Whether the item exists is a question for the game's registry, not for a table here:
            // a mod-added item is as real as a vanilla one, and only the game knows it.
            net.minecraft.core.RegistryAccess registries = ForbricRegistries.access();
            if (registries == null) {
                return null;
            }
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.fromNamespaceAndPath(
                            key.getNamespace(), key.getKey());
            if (!registries.lookupOrThrow(net.minecraft.core.registries.Registries.ITEM).containsKey(id)) {
                return null;
            }
            return ForbricItemType.of(id);
        }

        @Override
        public org.bukkit.NamespacedKey getKey(org.bukkit.inventory.ItemType type) {
            if (type == null) {
                return null;
            }
            return type.getKey();
        }

        @Override
        public boolean hasTag(TagKey<org.bukkit.inventory.ItemType> key) {
            return false;
        }

        @Override
        public Tag<org.bukkit.inventory.ItemType> getTag(TagKey<org.bukkit.inventory.ItemType> key) {
            throw new UnsupportedOperationException(
                            "[Forbric/Bukkit] item tags are not implemented by this bridge yet");
        }

        @Override
        public Collection<Tag<org.bukkit.inventory.ItemType>> getTags() {
            return java.util.List.of();
        }

        @Override
        public Stream<org.bukkit.inventory.ItemType> stream() {
            return keys().stream().map(key -> ForbricItemType.of(
                            net.minecraft.resources.Identifier.fromNamespaceAndPath(key.getNamespace(), key.getKey())));
        }

        @Override
        public Stream<org.bukkit.NamespacedKey> keyStream() {
            return keys().stream();
        }

        @Override
        public int size() {
            return keys().size();
        }

        @Override
        public java.util.Iterator<org.bukkit.inventory.ItemType> iterator() {
            return stream().iterator();
        }
    }
}
