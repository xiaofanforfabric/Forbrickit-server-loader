/*
 * Forbric Bukkit bridge — the plugin manager a plugin gets from {@code getServer().getPluginManager()}.
 */
package org.cn.xiaofan.bukkit;

import java.util.List;

/**
 * The plugin manager this bridge hands to plugins.
 *
 * <p><b>Almost all of it is Bukkit's own.</b> {@code SimplePluginManager} ships inside paper-api and is the
 * implementation the real server runs: event registration with priority ordering, {@code callEvent} dispatch,
 * the permission tables, and the recursive disable of a plugin's dependants. None of that is Forbric-specific and
 * none of it can be improved by rewriting — so it is delegated to, method for method.
 *
 * <p><b>Six methods are answered here instead, and the reason is the loader.</b> {@code SimplePluginManager}
 * tracks plugins in a private list that only its own {@code loadPlugin(File)} fills, and it is {@code final}, so
 * the list cannot be reached. This bridge loads plugins its own way — {@link PluginHandle} reads a jar, builds a
 * {@link PluginClassLoader}, and calls {@code onLoad}/{@code setEnabled} — which means the manager's list stays
 * empty while plugins are plainly running. Asking it {@code getPlugin("KitPlugin")} would answer null about a
 * plugin the server just started.
 *
 * <p>So the six that read or change that list are answered from the handles the bridge actually holds:
 * {@code getPlugin}, {@code getPlugins}, both {@code isPluginEnabled}, {@code enablePlugin}, and
 * {@code disablePlugin}. Everything else has no opinion about the list and is forwarded unchanged.
 *
 * <p><b>Why not register the plugins with it instead.</b> {@code loadPlugin(File)} goes through Bukkit's own
 * {@code PluginLoader} machinery, which would mean giving up {@link PluginClassLoader} — the loader that puts
 * {@code org.bukkit} and {@code net.minecraft} in a plugin's reach and that {@code JavaPlugin}'s own constructor
 * guard requires. Handing over loading to satisfy six getters would cost the thing that makes plugins work at
 * all.
 */
final class ForbricPluginManager implements org.bukkit.plugin.PluginManager {

    /** Bukkit's own manager, which everything not listed in this class's comment is delegated to. */
    private final org.bukkit.plugin.PluginManager delegate;

    /** The bridge's loaded plugins, in load order. */
    private final List<PluginHandle> handles;

    private final ServerImpl server;

    ForbricPluginManager(org.bukkit.plugin.PluginManager delegate, List<PluginHandle> handles,
                    ServerImpl server) {
        this.delegate = delegate;
        this.handles = handles;
        this.server = server;
    }

    /**
     * One plugin by the name it declares in {@code plugin.yml}, or null.
     *
     * <p>Case-insensitive, matching Bukkit: {@code getPlugin("kitplugin")} and {@code getPlugin("KitPlugin")}
     * are the same question, and a plugin loader that answered only one of them would break the other's callers.
     */
    @Override
    public org.bukkit.plugin.Plugin getPlugin(String name) {
        if (name == null) {
            return null;
        }
        for (PluginHandle handle : handles) {
            if (handle.description().getName().equalsIgnoreCase(name)) {
                return handle.plugin();
            }
        }
        return null;
    }

    /** Every loaded plugin, in load order. */
    @Override
    public org.bukkit.plugin.Plugin[] getPlugins() {
        org.bukkit.plugin.Plugin[] plugins = new org.bukkit.plugin.Plugin[handles.size()];
        for (int i = 0; i < handles.size(); i++) {
            plugins[i] = handles.get(i).plugin();
        }
        return plugins;
    }

    /** Whether a plugin by that name is loaded AND enabled. */
    @Override
    public boolean isPluginEnabled(String name) {
        org.bukkit.plugin.Plugin plugin = getPlugin(name);
        return plugin != null && plugin.isEnabled();
    }

    /**
     * Whether this plugin is one of ours, and enabled.
     *
     * <p>Asked of the identity set rather than of {@code plugin.isEnabled()} alone: a plugin that failed to load
     * is not ours, and answering "enabled" for one would be worse than answering false.
     */
    @Override
    public boolean isPluginEnabled(org.bukkit.plugin.Plugin plugin) {
        if (plugin == null) {
            return false;
        }
        for (PluginHandle handle : handles) {
            if (handle.plugin() == plugin) {
                return plugin.isEnabled();
            }
        }
        return false;
    }

    /**
     * Enables one of our plugins.
     *
     * <p>Runs {@code onLoad} then {@code setEnabled(true)}, the same order the bridge's own startup uses, because
     * a re-enable has to look like a first enable to the plugin. A plugin we do not hold is refused by name
     * rather than silently ignored — the caller almost certainly has the wrong object.
     */
    @Override
    public void enablePlugin(org.bukkit.plugin.Plugin plugin) {
        for (PluginHandle handle : handles) {
            if (handle.plugin() == plugin) {
                if (!plugin.isEnabled()) {
                    // Through the server, because enabling also fires the plugin's lifecycle handlers and those
                    // need the game's command dispatcher -- which the manager has no reason to hold.
                    server.enablePlugin(handle);
                }
                return;
            }
        }
        throw new IllegalArgumentException(
                        "[Forbric/Bukkit] PluginManager.enablePlugin() does not know " + plugin
                                        + "; it was not loaded by this bridge");
    }

    /**
     * Disables one of our plugins, running {@code onDisable}.
     *
     * <p>This is the method that made the manager worth building early: it is what a plugin calls on ITSELF when
     * its {@code onEnable} fails, so a null manager turned every plugin startup failure into a
     * NullPointerException that hid the original reason.
     */
    @Override
    public void disablePlugin(org.bukkit.plugin.Plugin plugin) {
        for (PluginHandle handle : handles) {
            if (handle.plugin() == plugin) {
                handle.disable();
                return;
            }
        }
        throw new IllegalArgumentException(
                        "[Forbric/Bukkit] PluginManager.disablePlugin() does not know " + plugin
                                        + "; it was not loaded by this bridge");
    }

    // ---------------------------------------------------------------------------------------------
    // Everything below is Bukkit's own behaviour, forwarded unchanged. Listed one by one rather
    // than reached through a proxy so that a method Paper adds shows up here as a compile error
    // instead of being answered with an InvocationTargetException at runtime.
    // ---------------------------------------------------------------------------------------------

    @Override
    public void registerInterface(Class<? extends org.bukkit.plugin.PluginLoader> loader)
                    throws IllegalArgumentException {
        delegate.registerInterface(loader);
    }

    @Override
    public org.bukkit.plugin.Plugin loadPlugin(java.io.File file)
                    throws org.bukkit.plugin.InvalidPluginException,
                    org.bukkit.plugin.InvalidDescriptionException,
                    org.bukkit.plugin.UnknownDependencyException {
        return delegate.loadPlugin(file);
    }

    @Override
    public org.bukkit.plugin.Plugin[] loadPlugins(java.io.File directory) {
        return delegate.loadPlugins(directory);
    }

    @Override
    public org.bukkit.plugin.Plugin[] loadPlugins(java.io.File[] files) {
        return delegate.loadPlugins(files);
    }

    @Override
    public void disablePlugins() {
        delegate.disablePlugins();
    }

    @Override
    public void clearPlugins() {
        delegate.clearPlugins();
    }

    @Override
    public void callEvent(org.bukkit.event.Event event) throws IllegalStateException {
        delegate.callEvent(event);
    }

    @Override
    public void registerEvents(org.bukkit.event.Listener listener, org.bukkit.plugin.Plugin plugin) {
        delegate.registerEvents(listener, plugin);
    }

    @Override
    public void registerEvent(Class<? extends org.bukkit.event.Event> event, org.bukkit.event.Listener listener,
                    org.bukkit.event.EventPriority priority, org.bukkit.plugin.EventExecutor executor,
                    org.bukkit.plugin.Plugin plugin) {
        delegate.registerEvent(event, listener, priority, executor, plugin);
    }

    @Override
    public void registerEvent(Class<? extends org.bukkit.event.Event> event, org.bukkit.event.Listener listener,
                    org.bukkit.event.EventPriority priority, org.bukkit.plugin.EventExecutor executor,
                    org.bukkit.plugin.Plugin plugin, boolean ignoreCancelled) {
        delegate.registerEvent(event, listener, priority, executor, plugin, ignoreCancelled);
    }

    @Override
    public org.bukkit.permissions.Permission getPermission(String name) {
        if (name == null) {
            return null;
        }
        // SimplePluginManager's Paper extension is initialized by a real Paper
        // server, not by its public constructor. Calling delegate.getPermission
        // here therefore dereferences its null paperPluginManager field. The
        // bridge already has the authoritative plugin.yml descriptions, so use
        // those instead of reaching into Paper's private initialization path.
        for (PluginHandle handle : handles) {
            for (org.bukkit.permissions.Permission permission : handle.description().getPermissions()) {
                if (name.equalsIgnoreCase(permission.getName())) {
                    return permission;
                }
            }
        }
        return null;
    }

    @Override
    public void addPermission(org.bukkit.permissions.Permission perm) {
        delegate.addPermission(perm);
    }

    @Override
    public void removePermission(org.bukkit.permissions.Permission perm) {
        delegate.removePermission(perm);
    }

    @Override
    public void removePermission(String name) {
        delegate.removePermission(name);
    }

    @Override
    public java.util.Set<org.bukkit.permissions.Permission> getDefaultPermissions(boolean op) {
        return delegate.getDefaultPermissions(op);
    }

    @Override
    public void recalculatePermissionDefaults(org.bukkit.permissions.Permission perm) {
        delegate.recalculatePermissionDefaults(perm);
    }

    @Override
    public void subscribeToPermission(String permission, org.bukkit.permissions.Permissible permissible) {
        delegate.subscribeToPermission(permission, permissible);
    }

    @Override
    public void unsubscribeFromPermission(String permission, org.bukkit.permissions.Permissible permissible) {
        delegate.unsubscribeFromPermission(permission, permissible);
    }

    @Override
    public java.util.Set<org.bukkit.permissions.Permissible> getPermissionSubscriptions(String permission) {
        return delegate.getPermissionSubscriptions(permission);
    }

    @Override
    public void subscribeToDefaultPerms(boolean op, org.bukkit.permissions.Permissible permissible) {
        delegate.subscribeToDefaultPerms(op, permissible);
    }

    @Override
    public void unsubscribeFromDefaultPerms(boolean op, org.bukkit.permissions.Permissible permissible) {
        delegate.unsubscribeFromDefaultPerms(op, permissible);
    }

    @Override
    public java.util.Set<org.bukkit.permissions.Permissible> getDefaultPermSubscriptions(boolean op) {
        return delegate.getDefaultPermSubscriptions(op);
    }

    @Override
    public java.util.Set<org.bukkit.permissions.Permission> getPermissions() {
        return delegate.getPermissions();
    }

    @Override
    public boolean useTimings() {
        return delegate.useTimings();
    }

    @Override
    public boolean isTransitiveDependency(io.papermc.paper.plugin.configuration.PluginMeta dependant,
                    io.papermc.paper.plugin.configuration.PluginMeta dependee) {
        return delegate.isTransitiveDependency(dependant, dependee);
    }

    @Override
    public void overridePermissionManager(org.bukkit.plugin.Plugin plugin,
                    io.papermc.paper.plugin.PermissionManager permissionManager) {
        delegate.overridePermissionManager(plugin, permissionManager);
    }

    @Override
    public void addPermissions(java.util.List<org.bukkit.permissions.Permission> permissions) {
        delegate.addPermissions(permissions);
    }

    @Override
    public void clearPermissions() {
        delegate.clearPermissions();
    }
}
