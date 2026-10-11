/*
 * Forbric Bukkit bridge — the {@code Server} the plugins see.  *** GENERATED FILE — DO NOT EDIT ***
 *
 * Produced by tools/gen_server_stub.py against paper-api-26.2.build.133-stable.jar. Re-run that script after bumping the api; editing
 * this file by hand means the next run silently discards the edit.
 */
package org.cn.xiaofan.bukkit;



/**
 * The {@code Server} a plugin receives from {@code getServer()}.
 *
 * <p>{@code org.bukkit.Server} has ~200 abstract methods. This bridge implements the few a plugin needs to
 * start — its identity, its logger, and the plugin manager — and refuses the rest with a named
 * {@link UnsupportedOperationException}. Refusing loudly is deliberate: a stubbed {@code getWorld()}
 * returning {@code null} would turn "this bridge has no worlds yet" into a {@code NullPointerException}
 * inside the plugin, several frames away from the cause.
 *
 * <p>Every method below carries the interface's own signature, so a plugin links against this class exactly
 * as it would against Paper's.
 */
final class ServerImpl implements org.bukkit.Server {

	static final String BRAND = "Forbric";
	static final String BUKKIT_VERSION = "26.2-R0.1-SNAPSHOT";
	static final String MINECRAFT_VERSION = "26.2";

	private final java.util.logging.Logger logger;
	private final org.bukkit.plugin.PluginManager pluginManager;
	private final java.nio.file.Path pluginsFolder;
	private final String version;

	ServerImpl(java.util.logging.Logger logger, org.bukkit.plugin.PluginManager pluginManager,
			java.nio.file.Path pluginsFolder, String version) {
		this.logger = logger;
		this.pluginManager = pluginManager;
		this.pluginsFolder = pluginsFolder;
		this.version = version;
	}

	private static UnsupportedOperationException notYet(String what) {
		return new UnsupportedOperationException(
				"[Forbric/Bukkit] Server." + what + "() is not implemented by this bridge yet");
	}

	@Override
	public java.io.File getPluginsFolder() {
		return pluginsFolder.toFile();
	}

	@Override
	public String getName() {
		return BRAND;
	}

	@Override
	public String getVersion() {
		return version;
	}

	@Override
	public String getBukkitVersion() {
		return BUKKIT_VERSION;
	}

	@Override
	public String getMinecraftVersion() {
		return MINECRAFT_VERSION;
	}

	@Override
	public java.util.Collection<? extends org.bukkit.entity.Player> getOnlinePlayers() {
		throw notYet("getOnlinePlayers");
	}

	@Override
	public int getMaxPlayers() {
		throw notYet("getMaxPlayers");
	}

	@Override
	public void setMaxPlayers(int arg0) {
		throw notYet("setMaxPlayers");
	}

	@Override
	public int getPort() {
		throw notYet("getPort");
	}

	@Override
	public int getViewDistance() {
		throw notYet("getViewDistance");
	}

	@Override
	public int getSimulationDistance() {
		throw notYet("getSimulationDistance");
	}

	@Override
	public String getIp() {
		throw notYet("getIp");
	}

	@Override
	public String getWorldType() {
		throw notYet("getWorldType");
	}

	@Override
	public boolean getGenerateStructures() {
		throw notYet("getGenerateStructures");
	}

	@Override
	public int getMaxWorldSize() {
		throw notYet("getMaxWorldSize");
	}

	@Override
	public boolean getAllowEnd() {
		throw notYet("getAllowEnd");
	}

	@Override
	public boolean getAllowNether() {
		throw notYet("getAllowNether");
	}

	@Override
	public boolean isLoggingIPs() {
		throw notYet("isLoggingIPs");
	}

	@Override
	public java.util.List<String> getInitialEnabledPacks() {
		throw notYet("getInitialEnabledPacks");
	}

	@Override
	public java.util.List<String> getInitialDisabledPacks() {
		throw notYet("getInitialDisabledPacks");
	}

	@Override
	public org.bukkit.ServerTickManager getServerTickManager() {
		throw notYet("getServerTickManager");
	}

	@Override
	public org.bukkit.packs.ResourcePack getServerResourcePack() {
		throw notYet("getServerResourcePack");
	}

	@Override
	public String getResourcePack() {
		throw notYet("getResourcePack");
	}

	@Override
	public String getResourcePackHash() {
		throw notYet("getResourcePackHash");
	}

	@Override
	public String getResourcePackPrompt() {
		throw notYet("getResourcePackPrompt");
	}

	@Override
	public boolean isResourcePackRequired() {
		throw notYet("isResourcePackRequired");
	}

	@Override
	public boolean hasWhitelist() {
		throw notYet("hasWhitelist");
	}

	@Override
	public void setWhitelist(boolean arg0) {
		throw notYet("setWhitelist");
	}

	@Override
	public boolean isWhitelistEnforced() {
		throw notYet("isWhitelistEnforced");
	}

	@Override
	public void setWhitelistEnforced(boolean arg0) {
		throw notYet("setWhitelistEnforced");
	}

	@Override
	public java.util.Set<org.bukkit.OfflinePlayer> getWhitelistedPlayers() {
		throw notYet("getWhitelistedPlayers");
	}

	@Override
	public void reloadWhitelist() {
		throw notYet("reloadWhitelist");
	}

	@Override
	public String getUpdateFolder() {
		return "update";
	}

	@Override
	public java.io.File getUpdateFolderFile() {
		throw notYet("getUpdateFolderFile");
	}

	@Override
	public long getConnectionThrottle() {
		throw notYet("getConnectionThrottle");
	}

	@Override
	public int getTicksPerSpawns(org.bukkit.entity.SpawnCategory arg0) {
		throw notYet("getTicksPerSpawns");
	}

	@Override
	public org.bukkit.entity.Player getPlayer(String arg0) {
		throw notYet("getPlayer");
	}

	@Override
	public org.bukkit.entity.Player getPlayerExact(String arg0) {
		throw notYet("getPlayerExact");
	}

	@Override
	public java.util.List<org.bukkit.entity.Player> matchPlayer(String arg0) {
		throw notYet("matchPlayer");
	}

	@Override
	public org.bukkit.entity.Player getPlayer(java.util.UUID arg0) {
		throw notYet("getPlayer");
	}

	@Override
	public java.util.UUID getPlayerUniqueId(String arg0) {
		throw notYet("getPlayerUniqueId");
	}

	@Override
	public org.bukkit.plugin.PluginManager getPluginManager() {
		return pluginManager;
	}

	@Override
	public org.bukkit.scheduler.BukkitScheduler getScheduler() {
		throw notYet("getScheduler");
	}

	@Override
	public org.bukkit.plugin.ServicesManager getServicesManager() {
		throw notYet("getServicesManager");
	}

	@Override
	public java.util.List<org.bukkit.World> getWorlds() {
		throw notYet("getWorlds");
	}

	@Override
	public boolean isTickingWorlds() {
		throw notYet("isTickingWorlds");
	}

	@Override
	public org.bukkit.World createWorld(org.bukkit.WorldCreator arg0) {
		throw notYet("createWorld");
	}

	@Override
	public boolean unloadWorld(String arg0, boolean arg1) {
		throw notYet("unloadWorld");
	}

	@Override
	public boolean unloadWorld(org.bukkit.World arg0, boolean arg1) {
		throw notYet("unloadWorld");
	}

	@Override
	public org.bukkit.World getRespawnWorld() {
		throw notYet("getRespawnWorld");
	}

	@Override
	public void setRespawnWorld(org.bukkit.World arg0) {
		throw notYet("setRespawnWorld");
	}

	@Override
	public org.bukkit.World getWorld(String arg0) {
		throw notYet("getWorld");
	}

	@Override
	public org.bukkit.World getWorld(java.util.UUID arg0) {
		throw notYet("getWorld");
	}

	@Override
	public org.bukkit.World getWorld(net.kyori.adventure.key.Key arg0) {
		throw notYet("getWorld");
	}

	@Override
	public org.bukkit.WorldBorder createWorldBorder() {
		throw notYet("createWorldBorder");
	}

	@Override
	public org.bukkit.map.MapView getMap(int arg0) {
		throw notYet("getMap");
	}

	@Override
	public org.bukkit.map.MapView createMap(org.bukkit.World arg0) {
		throw notYet("createMap");
	}

	@Override
	public org.bukkit.inventory.ItemStack createExplorerMap(org.bukkit.World arg0, org.bukkit.Location arg1, org.bukkit.StructureType arg2, int arg3, boolean arg4) {
		throw notYet("createExplorerMap");
	}

	@Override
	public org.bukkit.inventory.ItemStack createExplorerMap(org.bukkit.World arg0, org.bukkit.Location arg1, org.bukkit.generator.structure.StructureType arg2, org.bukkit.map.MapCursor.Type arg3, int arg4, boolean arg5) {
		throw notYet("createExplorerMap");
	}

	@Override
	public void reload() {
		throw notYet("reload");
	}

	@Override
	public void reloadData() {
		throw notYet("reloadData");
	}

	@Override
	public void updateResources() {
		throw notYet("updateResources");
	}

	@Override
	public void updateRecipes() {
		throw notYet("updateRecipes");
	}

	@Override
	public java.util.logging.Logger getLogger() {
		return logger;
	}

	@Override
	public org.bukkit.command.PluginCommand getPluginCommand(String arg0) {
		throw notYet("getPluginCommand");
	}

	@Override
	public void savePlayers() {
		throw notYet("savePlayers");
	}

	@Override
	public boolean dispatchCommand(org.bukkit.command.CommandSender arg0, String arg1) throws org.bukkit.command.CommandException {
		throw notYet("dispatchCommand");
	}

	@Override
	public boolean addRecipe(org.bukkit.inventory.Recipe arg0, boolean arg1) {
		throw notYet("addRecipe");
	}

	@Override
	public java.util.List<org.bukkit.inventory.Recipe> getRecipesFor(org.bukkit.inventory.ItemStack arg0) {
		throw notYet("getRecipesFor");
	}

	@Override
	public org.bukkit.inventory.Recipe getRecipe(org.bukkit.NamespacedKey arg0) {
		throw notYet("getRecipe");
	}

	@Override
	public org.bukkit.inventory.Recipe getCraftingRecipe(org.bukkit.inventory.ItemStack[] arg0, org.bukkit.World arg1) {
		throw notYet("getCraftingRecipe");
	}

	@Override
	public org.bukkit.inventory.ItemCraftResult craftItemResult(org.bukkit.inventory.ItemStack[] arg0, org.bukkit.World arg1, org.bukkit.entity.Player arg2) {
		throw notYet("craftItemResult");
	}

	@Override
	public org.bukkit.inventory.ItemCraftResult craftItemResult(org.bukkit.inventory.ItemStack[] arg0, org.bukkit.World arg1) {
		throw notYet("craftItemResult");
	}

	@Override
	public java.util.Iterator<org.bukkit.inventory.Recipe> recipeIterator() {
		throw notYet("recipeIterator");
	}

	@Override
	public void clearRecipes() {
		throw notYet("clearRecipes");
	}

	@Override
	public void resetRecipes() {
		throw notYet("resetRecipes");
	}

	@Override
	public boolean removeRecipe(org.bukkit.NamespacedKey arg0, boolean arg1) {
		throw notYet("removeRecipe");
	}

	@Override
	public java.util.Map<String, String[]> getCommandAliases() {
		throw notYet("getCommandAliases");
	}

	@Override
	public int getSpawnRadius() {
		throw notYet("getSpawnRadius");
	}

	@Override
	public void setSpawnRadius(int arg0) {
		throw notYet("setSpawnRadius");
	}

	@Override
	public boolean isEnforcingSecureProfiles() {
		throw notYet("isEnforcingSecureProfiles");
	}

	@Override
	public boolean isAcceptingTransfers() {
		throw notYet("isAcceptingTransfers");
	}

	@Override
	public boolean getHideOnlinePlayers() {
		throw notYet("getHideOnlinePlayers");
	}

	@Override
	public boolean getOnlineMode() {
		throw notYet("getOnlineMode");
	}

	@Override
	public io.papermc.paper.configuration.ServerConfiguration getServerConfig() {
		throw notYet("getServerConfig");
	}

	@Override
	public boolean getAllowFlight() {
		throw notYet("getAllowFlight");
	}

	@Override
	public boolean isHardcore() {
		throw notYet("isHardcore");
	}

	@Override
	public void shutdown() {
		throw notYet("shutdown");
	}

	@Override
	public int broadcast(net.kyori.adventure.text.Component arg0, String arg1) {
		throw notYet("broadcast");
	}

	@Override
	public org.bukkit.OfflinePlayer getOfflinePlayer(String arg0) {
		throw notYet("getOfflinePlayer");
	}

	@Override
	public org.bukkit.OfflinePlayer getOfflinePlayerIfCached(String arg0) {
		throw notYet("getOfflinePlayerIfCached");
	}

	@Override
	public org.bukkit.OfflinePlayer getOfflinePlayer(java.util.UUID arg0) {
		throw notYet("getOfflinePlayer");
	}

	@Override
	public org.bukkit.profile.PlayerProfile createPlayerProfile(java.util.UUID arg0, String arg1) {
		throw notYet("createPlayerProfile");
	}

	@Override
	public org.bukkit.profile.PlayerProfile createPlayerProfile(java.util.UUID arg0) {
		throw notYet("createPlayerProfile");
	}

	@Override
	public org.bukkit.profile.PlayerProfile createPlayerProfile(String arg0) {
		throw notYet("createPlayerProfile");
	}

	@Override
	public java.util.Set<String> getIPBans() {
		throw notYet("getIPBans");
	}

	@Override
	public void banIP(String arg0) {
		throw notYet("banIP");
	}

	@Override
	public void unbanIP(String arg0) {
		throw notYet("unbanIP");
	}

	@Override
	public void banIP(java.net.InetAddress arg0) {
		throw notYet("banIP");
	}

	@Override
	public void unbanIP(java.net.InetAddress arg0) {
		throw notYet("unbanIP");
	}

	@Override
	public java.util.Set<org.bukkit.OfflinePlayer> getBannedPlayers() {
		throw notYet("getBannedPlayers");
	}

	@Override
	public <T extends org.bukkit.BanList<?>> T getBanList(org.bukkit.BanList.Type arg0) {
		throw notYet("getBanList");
	}

	@Override
	public <B extends org.bukkit.BanList<E>, E> B getBanList(io.papermc.paper.ban.BanListType<B> arg0) {
		throw notYet("getBanList");
	}

	@Override
	public java.util.Set<org.bukkit.OfflinePlayer> getOperators() {
		throw notYet("getOperators");
	}

	@Override
	public org.bukkit.GameMode getDefaultGameMode() {
		throw notYet("getDefaultGameMode");
	}

	@Override
	public void setDefaultGameMode(org.bukkit.GameMode arg0) {
		throw notYet("setDefaultGameMode");
	}

	@Override
	public boolean forcesDefaultGameMode() {
		throw notYet("forcesDefaultGameMode");
	}

	@Override
	public org.bukkit.command.ConsoleCommandSender getConsoleSender() {
		throw notYet("getConsoleSender");
	}

	@Override
	public org.bukkit.command.CommandSender createCommandSender(java.util.function.Consumer<? super net.kyori.adventure.text.Component> arg0) {
		throw notYet("createCommandSender");
	}

	@Override
	public java.io.File getWorldContainer() {
		throw notYet("getWorldContainer");
	}

	@Override
	public java.nio.file.Path getLevelDirectory() {
		throw notYet("getLevelDirectory");
	}

	@Override
	public org.bukkit.OfflinePlayer[] getOfflinePlayers() {
		throw notYet("getOfflinePlayers");
	}

	@Override
	public org.bukkit.plugin.messaging.Messenger getMessenger() {
		throw notYet("getMessenger");
	}

	@Override
	public org.bukkit.help.HelpMap getHelpMap() {
		throw notYet("getHelpMap");
	}

	@Override
	public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder arg0, org.bukkit.event.inventory.InventoryType arg1) {
		throw notYet("createInventory");
	}

	@Override
	public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder arg0, org.bukkit.event.inventory.InventoryType arg1, net.kyori.adventure.text.Component arg2) {
		throw notYet("createInventory");
	}

	@Override
	public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder arg0, org.bukkit.event.inventory.InventoryType arg1, String arg2) {
		throw notYet("createInventory");
	}

	@Override
	public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder arg0, int arg1) throws IllegalArgumentException {
		throw notYet("createInventory");
	}

	@Override
	public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder arg0, int arg1, net.kyori.adventure.text.Component arg2) throws IllegalArgumentException {
		throw notYet("createInventory");
	}

	@Override
	public org.bukkit.inventory.Inventory createInventory(org.bukkit.inventory.InventoryHolder arg0, int arg1, String arg2) throws IllegalArgumentException {
		throw notYet("createInventory");
	}

	@Override
	public org.bukkit.inventory.Merchant createMerchant(net.kyori.adventure.text.Component arg0) {
		throw notYet("createMerchant");
	}

	@Override
	public org.bukkit.inventory.Merchant createMerchant(String arg0) {
		throw notYet("createMerchant");
	}

	@Override
	public int getMaxChainedNeighborUpdates() {
		throw notYet("getMaxChainedNeighborUpdates");
	}

	@Override
	public org.bukkit.inventory.Merchant createMerchant() {
		throw notYet("createMerchant");
	}

	@Override
	public int getSpawnLimit(org.bukkit.entity.SpawnCategory arg0) {
		throw notYet("getSpawnLimit");
	}

	@Override
	public boolean isPrimaryThread() {
		return true;
	}

	@Override
	public net.kyori.adventure.text.Component motd() {
		throw notYet("motd");
	}

	@Override
	public void motd(net.kyori.adventure.text.Component arg0) {
		throw notYet("motd");
	}

	@Override
	public net.kyori.adventure.text.Component shutdownMessage() {
		throw notYet("shutdownMessage");
	}

	@Override
	public String getMotd() {
		throw notYet("getMotd");
	}

	@Override
	public void setMotd(String arg0) {
		throw notYet("setMotd");
	}

	@Override
	public org.bukkit.ServerLinks getServerLinks() {
		throw notYet("getServerLinks");
	}

	@Override
	public String getShutdownMessage() {
		throw notYet("getShutdownMessage");
	}

	@Override
	public org.bukkit.Warning.WarningState getWarningState() {
		throw notYet("getWarningState");
	}

	@Override
	public org.bukkit.inventory.ItemFactory getItemFactory() {
		throw notYet("getItemFactory");
	}

	@Override
	public org.bukkit.entity.EntityFactory getEntityFactory() {
		throw notYet("getEntityFactory");
	}

	@Override
	public org.bukkit.scoreboard.ScoreboardManager getScoreboardManager() {
		throw notYet("getScoreboardManager");
	}

	@Override
	public org.bukkit.scoreboard.Criteria getScoreboardCriteria(String arg0) {
		throw notYet("getScoreboardCriteria");
	}

	@Override
	public org.bukkit.util.CachedServerIcon getServerIcon() {
		throw notYet("getServerIcon");
	}

	@Override
	public org.bukkit.util.CachedServerIcon loadServerIcon(java.io.File arg0) throws IllegalArgumentException, Exception {
		throw notYet("loadServerIcon");
	}

	@Override
	public org.bukkit.util.CachedServerIcon loadServerIcon(java.awt.image.BufferedImage arg0) throws IllegalArgumentException, Exception {
		throw notYet("loadServerIcon");
	}

	@Override
	public void setIdleTimeout(int arg0) {
		throw notYet("setIdleTimeout");
	}

	@Override
	public int getIdleTimeout() {
		throw notYet("getIdleTimeout");
	}

	@Override
	public int getPauseWhenEmptyTime() {
		throw notYet("getPauseWhenEmptyTime");
	}

	@Override
	public void setPauseWhenEmptyTime(int arg0) {
		throw notYet("setPauseWhenEmptyTime");
	}

	@Override
	public org.bukkit.generator.ChunkGenerator.ChunkData createChunkData(org.bukkit.World arg0) {
		throw notYet("createChunkData");
	}

	@Override
	public org.bukkit.boss.BossBar createBossBar(String arg0, org.bukkit.boss.BarColor arg1, org.bukkit.boss.BarStyle arg2, org.bukkit.boss.BarFlag... arg3) {
		throw notYet("createBossBar");
	}

	@Override
	public org.bukkit.boss.KeyedBossBar createBossBar(org.bukkit.NamespacedKey arg0, String arg1, org.bukkit.boss.BarColor arg2, org.bukkit.boss.BarStyle arg3, org.bukkit.boss.BarFlag... arg4) {
		throw notYet("createBossBar");
	}

	@Override
	public java.util.Iterator<org.bukkit.boss.KeyedBossBar> getBossBars() {
		throw notYet("getBossBars");
	}

	@Override
	public org.bukkit.boss.KeyedBossBar getBossBar(org.bukkit.NamespacedKey arg0) {
		throw notYet("getBossBar");
	}

	@Override
	public boolean removeBossBar(org.bukkit.NamespacedKey arg0) {
		throw notYet("removeBossBar");
	}

	@Override
	public org.bukkit.entity.Entity getEntity(java.util.UUID arg0) {
		throw notYet("getEntity");
	}

	@Override
	public double[] getTPS() {
		throw notYet("getTPS");
	}

	@Override
	public long[] getTickTimes() {
		throw notYet("getTickTimes");
	}

	@Override
	public double getAverageTickTime() {
		throw notYet("getAverageTickTime");
	}

	@Override
	public org.bukkit.command.CommandMap getCommandMap() {
		throw notYet("getCommandMap");
	}

	@Override
	public org.bukkit.advancement.Advancement getAdvancement(org.bukkit.NamespacedKey arg0) {
		throw notYet("getAdvancement");
	}

	@Override
	public java.util.Iterator<org.bukkit.advancement.Advancement> advancementIterator() {
		throw notYet("advancementIterator");
	}

	@Override
	public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material arg0) {
		throw notYet("createBlockData");
	}

	@Override
	public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material arg0, java.util.function.Consumer<? super org.bukkit.block.data.BlockData> arg1) {
		throw notYet("createBlockData");
	}

	@Override
	public org.bukkit.block.data.BlockData createBlockData(String arg0) throws IllegalArgumentException {
		throw notYet("createBlockData");
	}

	@Override
	public org.bukkit.block.data.BlockData createBlockData(org.bukkit.Material arg0, String arg1) throws IllegalArgumentException {
		throw notYet("createBlockData");
	}

	@Override
	public <T extends org.bukkit.Keyed> org.bukkit.Tag<T> getTag(String arg0, org.bukkit.NamespacedKey arg1, Class<T> arg2) {
		throw notYet("getTag");
	}

	@Override
	public <T extends org.bukkit.Keyed> Iterable<org.bukkit.Tag<T>> getTags(String arg0, Class<T> arg1) {
		throw notYet("getTags");
	}

	@Override
	public org.bukkit.loot.LootTable getLootTable(org.bukkit.NamespacedKey arg0) {
		throw notYet("getLootTable");
	}

	@Override
	public java.util.List<org.bukkit.entity.Entity> selectEntities(org.bukkit.command.CommandSender arg0, String arg1) throws IllegalArgumentException {
		throw notYet("selectEntities");
	}

	@Override
	public org.bukkit.structure.StructureManager getStructureManager() {
		throw notYet("getStructureManager");
	}

	@Override
	public <T extends org.bukkit.Keyed> org.bukkit.Registry<T> getRegistry(Class<T> arg0) {
		throw notYet("getRegistry");
	}

	@Override
	public org.bukkit.UnsafeValues getUnsafe() {
		throw notYet("getUnsafe");
	}

	@Override
	public org.bukkit.Server.Spigot spigot() {
		throw notYet("spigot");
	}

	@Override
	public void restart() {
		throw notYet("restart");
	}

	@Override
	public void reloadPermissions() {
		throw notYet("reloadPermissions");
	}

	@Override
	public boolean reloadCommandAliases() {
		throw notYet("reloadCommandAliases");
	}

	@Override
	public boolean suggestPlayerNamesWhenNullTabCompletions() {
		throw notYet("suggestPlayerNamesWhenNullTabCompletions");
	}

	@Override
	public String getPermissionMessage() {
		throw notYet("getPermissionMessage");
	}

	@Override
	public net.kyori.adventure.text.Component permissionMessage() {
		throw notYet("permissionMessage");
	}

	@Override
	public com.destroystokyo.paper.profile.PlayerProfile createProfile(java.util.UUID arg0) {
		throw notYet("createProfile");
	}

	@Override
	public com.destroystokyo.paper.profile.PlayerProfile createProfile(String arg0) {
		throw notYet("createProfile");
	}

	@Override
	public com.destroystokyo.paper.profile.PlayerProfile createProfile(java.util.UUID arg0, String arg1) {
		throw notYet("createProfile");
	}

	@Override
	public com.destroystokyo.paper.profile.PlayerProfile createProfileExact(java.util.UUID arg0, String arg1) {
		throw notYet("createProfileExact");
	}

	@Override
	public int getCurrentTick() {
		throw notYet("getCurrentTick");
	}

	@Override
	public boolean isStopping() {
		throw notYet("isStopping");
	}

	@Override
	public com.destroystokyo.paper.entity.ai.MobGoals getMobGoals() {
		throw notYet("getMobGoals");
	}

	@Override
	public io.papermc.paper.datapack.DatapackManager getDatapackManager() {
		throw notYet("getDatapackManager");
	}

	@Override
	public org.bukkit.potion.PotionBrewer getPotionBrewer() {
		throw notYet("getPotionBrewer");
	}

	@Override
	public io.papermc.paper.threadedregions.scheduler.RegionScheduler getRegionScheduler() {
		throw notYet("getRegionScheduler");
	}

	@Override
	public io.papermc.paper.threadedregions.scheduler.AsyncScheduler getAsyncScheduler() {
		throw notYet("getAsyncScheduler");
	}

	@Override
	public io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler getGlobalRegionScheduler() {
		throw notYet("getGlobalRegionScheduler");
	}

	@Override
	public boolean isOwnedByCurrentRegion(org.bukkit.World arg0, io.papermc.paper.math.Position arg1) {
		throw notYet("isOwnedByCurrentRegion");
	}

	@Override
	public boolean isOwnedByCurrentRegion(org.bukkit.World arg0, io.papermc.paper.math.Position arg1, int arg2) {
		throw notYet("isOwnedByCurrentRegion");
	}

	@Override
	public boolean isOwnedByCurrentRegion(org.bukkit.Location arg0) {
		throw notYet("isOwnedByCurrentRegion");
	}

	@Override
	public boolean isOwnedByCurrentRegion(org.bukkit.Location arg0, int arg1) {
		throw notYet("isOwnedByCurrentRegion");
	}

	@Override
	public boolean isOwnedByCurrentRegion(org.bukkit.World arg0, int arg1, int arg2) {
		throw notYet("isOwnedByCurrentRegion");
	}

	@Override
	public boolean isOwnedByCurrentRegion(org.bukkit.World arg0, int arg1, int arg2, int arg3) {
		throw notYet("isOwnedByCurrentRegion");
	}

	@Override
	public boolean isOwnedByCurrentRegion(org.bukkit.World arg0, int arg1, int arg2, int arg3, int arg4) {
		throw notYet("isOwnedByCurrentRegion");
	}

	@Override
	public boolean isOwnedByCurrentRegion(org.bukkit.entity.Entity arg0) {
		throw notYet("isOwnedByCurrentRegion");
	}

	@Override
	public boolean isGlobalTickThread() {
		throw notYet("isGlobalTickThread");
	}

	@Override
	public boolean isPaused() {
		throw notYet("isPaused");
	}

	@Override
	public void allowPausing(org.bukkit.plugin.Plugin arg0, boolean arg1) {
		throw notYet("allowPausing");
	}

	@Override
	public void sendPluginMessage(org.bukkit.plugin.Plugin arg0, String arg1, byte[] arg2) {
		throw notYet("sendPluginMessage");
	}

	@Override
	public java.util.Set<String> getListeningPluginChannels() {
		throw notYet("getListeningPluginChannels");
	}

	@Override
	public Iterable<? extends net.kyori.adventure.audience.Audience> audiences() {
		throw notYet("audiences");
	}
}
