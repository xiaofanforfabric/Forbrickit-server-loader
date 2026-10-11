/*
 * Forbric Bukkit bridge — an {@code org.bukkit.World} backed by a Minecraft {@code ServerLevel}.
 * *** GENERATED FILE — DO NOT EDIT ***
 *
 * Produced by tools/gen_stub.py from tools/stubs/ForbricWorld.table. Edits belong in the table or this template.
 */
package org.cn.xiaofan.bukkit;

/**
 * One loaded world, as a plugin sees it.
 *
 * <p>Every method {@code org.bukkit.World} declares is present; the ones with real meaning read from the
 * {@code ServerLevel} this wraps, and the rest refuse with a named exception. A refusal is deliberate: a stub
 * returning a plausible wrong value (an empty chunk, a zero difficulty) is far harder to trace than a message
 * that names the method.
 *
 * <p><b>Time is not where it used to be.</b> Through 1.21 the game kept one {@code dayTime} on the level, reached
 * with {@code getDayTime()}/{@code setDayTime()}. 26.2 replaced that with named clocks
 * ({@code net.minecraft.world.clock}): the overworld's is {@code Level.getOverworldClockTime()}, a dimension's
 * own is {@code getDefaultClockTime()}, and writing one goes through
 * {@code ServerClockManager.setTotalTicks(Holder<WorldClock>, long)}. None of the old names still exists, which
 * is how this was found — every one of them is a compile error.
 *
 * <p>Coordinates need no conversion, and that is worth stating rather than leaving implied: Bukkit's
 * {@code (x, y, z)} and Minecraft's {@code BlockPos} are both world-absolute and both count y upward from the
 * bottom of the world. What DOES come from the dimension rather than a constant is the height range.
 */
final class ForbricWorld implements org.bukkit.World {

	private final net.minecraft.server.level.ServerLevel level;
	private final ServerImpl server;

	private ForbricWorld(net.minecraft.server.level.ServerLevel level, ServerImpl server) {
		this.level = level;
		this.server = server;
	}

	/**
	 * Wraps a level. The one place a {@code ServerLevel} becomes an {@code org.bukkit.World}.
	 *
	 * <p>A fresh wrapper per call rather than a cache: two wrappers for one level are equal through
	 * {@link #equals} and hash alike, so nothing downstream can tell them apart — and a cache would need
	 * invalidating when a level unloads, which is a correctness problem bought for nothing.
	 */
	static ForbricWorld of(net.minecraft.server.level.ServerLevel level, ServerImpl server) {
		return new ForbricWorld(level, server);
	}

	/** The wrapped level, for the bridge's own use by {@link ForbricBlock} and the event layer. */
	net.minecraft.server.level.ServerLevel level() {
		return level;
	}

	private static UnsupportedOperationException notYet(String what) {
		return new UnsupportedOperationException(
				"[Forbric/Bukkit] World." + what + "() is not implemented by this bridge yet");
	}

	/**
	 * The world's name, as Bukkit knows it: the folder it is stored under.
	 *
	 * <p>Minecraft names a dimension with a {@code ResourceKey} ({@code minecraft:overworld}); Bukkit names a
	 * world with the folder in {@code saves/} ({@code world}). The key's path half is what a plugin writes when
	 * it says {@code getWorld("world")}, and it is the half that stays put when a datapack moves a dimension
	 * between namespaces.
	 */
	@Override
	public String getName() {
		return level.dimension().identifier().getPath();
	}

	/**
	 * A stable id, derived from the dimension rather than generated.
	 *
	 * <p>Bukkit hands a world a UUID and a plugin may save it. Deriving it from the dimension key means a saved
	 * reference resolves again after a restart; a random one would not, and would look correct until the second
	 * launch.
	 */
	@Override
	public java.util.UUID getUID() {
		return java.util.UUID.nameUUIDFromBytes(
				("forbric:world:" + level.dimension().identifier()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	@Override
	public int getMinHeight() {
		return level.getMinY();
	}

	@Override
	public int getMaxHeight() {
		// Bukkit's is exclusive, Minecraft's inclusive. Off by one here puts a plugin's top-of-world loop one
		// block above the world, where every write is accepted and discarded.
		return level.getMaxY() + 1;
	}

	@Override
	public int getSeaLevel() {
		return level.getSeaLevel();
	}

	@Override
	public long getTime() {
		// Bukkit's getTime() is the time of day, 0..24000, on THIS dimension's clock. Reporting the overworld's
		// clock here would make a plugin's day/night logic wrong in the Nether and the End — the two dimensions
		// where it is least visibly wrong and so hardest to notice.
		return Math.floorMod(level.getDefaultClockTime(), 24000L);
	}

	@Override
	public void setTime(long time) {
		// Set the time OF DAY, keeping the day count: Bukkit's setTime(6000) means noon today, not noon on day
		// zero. A clock is one long, so the day is its high part and the time of day its low one.
		long current = level.getDefaultClockTime();
		long day = current - Math.floorMod(current, 24000L);
		level.clockManager().setTotalTicks(clock(),
				day + Math.floorMod(time, 24000L));
	}

	@Override
	public long getFullTime() {
		// The whole clock, day count included — what a plugin compares to notice that time moved backwards.
		return level.getDefaultClockTime();
	}

	@Override
	public void setFullTime(long time) {
		level.clockManager().setTotalTicks(clock(), time);
	}

	@Override
	public boolean isDayTime() {
		// Computed, because 26.2 has no day/night predicate to delegate to: the game decides light from the sky
		// darkening value, and the clock is a separate concern. Bukkit's own contract is the 0..12000 window, so
		// that is what this answers — a plugin comparing it against Bukkit's documentation gets what it expects.
		return getTime() < 12000L;
	}

	@Override
	public org.bukkit.Difficulty getDifficulty() {
		return switch (level.getDifficulty()) {
			case PEACEFUL -> org.bukkit.Difficulty.PEACEFUL;
			case EASY -> org.bukkit.Difficulty.EASY;
			case NORMAL -> org.bukkit.Difficulty.NORMAL;
			case HARD -> org.bukkit.Difficulty.HARD;
		};
	}

	@Override
	public org.bukkit.Location getSpawnLocation() {
		// Bukkit's Location wants a block's centre on x and z and its floor on y. Handing it the raw BlockPos
		// puts a teleport in a corner of the block rather than at the spawn point the game would use.
		net.minecraft.core.BlockPos spawn = level.getRespawnData().pos();
		return new org.bukkit.Location(this, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
	}

	@Override
	public org.bukkit.block.Block getBlockAt(int x, int y, int z) {
		return ForbricBlock.at(this, x, y, z);
	}

	@Override
	public java.util.List<org.bukkit.entity.Player> getPlayers() {
		java.util.List<org.bukkit.entity.Player> players = new java.util.ArrayList<>();
		for (net.minecraft.server.level.ServerPlayer player : level.getPlayers(ignored -> true)) {
			players.add(ForbricPlayer.of(player, server));
		}
		return players;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ForbricWorld world && world.level == level;
	}

	@Override
	public int hashCode() {
		return level.hashCode();
	}

	@Override
	public String toString() {
		return "ForbricWorld[" + level.dimension().identifier() + "]";
	}

	/**
	 * The overworld clock, as the {@code Holder} {@code setTotalTicks} takes.
	 *
	 * <p>Resolved through the running server's registry access, not a static constant: the clock registry is a
	 * datapack registry, so it exists once the server is up and not before, and a static reach lands on a
	 * registry the game never populated.
	 *
	 * <p>{@code OVERWORLD} rather than the dimension's own clock because that is the clock a plugin means by
	 * "the time": 26.2 lets a dimension have its own, and Bukkit has one number. Choosing the overworld's makes
	 * {@code setTime(6000)} mean noon in the world a plugin is almost always thinking of.
	 */
	private net.minecraft.core.Holder<net.minecraft.world.clock.WorldClock> clock() {
		return server.minecraft().registryAccess()
				.lookupOrThrow(net.minecraft.core.registries.Registries.WORLD_CLOCK)
				.getOrThrow(net.minecraft.world.clock.WorldClocks.OVERWORLD);
	}

	@Override
	public boolean isVoidDamageEnabled() {
		throw notYet("isVoidDamageEnabled");
	}

	@Override
	public void setVoidDamageEnabled(boolean arg0) {
		throw notYet("setVoidDamageEnabled");
	}

	@Override
	public float getVoidDamageAmount() {
		throw notYet("getVoidDamageAmount");
	}

	@Override
	public void setVoidDamageAmount(float arg0) {
		throw notYet("setVoidDamageAmount");
	}

	@Override
	public double getVoidDamageMinBuildHeightOffset() {
		throw notYet("getVoidDamageMinBuildHeightOffset");
	}

	@Override
	public void setVoidDamageMinBuildHeightOffset(double arg0) {
		throw notYet("setVoidDamageMinBuildHeightOffset");
	}

	@Override
	public int getEntityCount() {
		throw notYet("getEntityCount");
	}

	@Override
	public int getTileEntityCount() {
		throw notYet("getTileEntityCount");
	}

	@Override
	public int getTickableTileEntityCount() {
		throw notYet("getTickableTileEntityCount");
	}

	@Override
	public int getChunkCount() {
		throw notYet("getChunkCount");
	}

	@Override
	public int getPlayerCount() {
		throw notYet("getPlayerCount");
	}

	@Override
	public boolean hasStructureAt(io.papermc.paper.math.Position arg0, org.bukkit.generator.structure.Structure arg1) {
		throw notYet("hasStructureAt");
	}

	@Override
	public org.bukkit.Chunk getChunkAt(int arg0, int arg1) {
		throw notYet("getChunkAt");
	}

	@Override
	public org.bukkit.Chunk getChunkAt(int arg0, int arg1, boolean arg2) {
		throw notYet("getChunkAt");
	}

	@Override
	public org.bukkit.Chunk getChunkAt(org.bukkit.block.Block arg0) {
		throw notYet("getChunkAt");
	}

	@Override
	public boolean isChunkLoaded(org.bukkit.Chunk arg0) {
		throw notYet("isChunkLoaded");
	}

	@Override
	public org.bukkit.Chunk[] getLoadedChunks() {
		throw notYet("getLoadedChunks");
	}

	@Override
	public void loadChunk(org.bukkit.Chunk arg0) {
		throw notYet("loadChunk");
	}

	@Override
	public boolean isChunkLoaded(int arg0, int arg1) {
		throw notYet("isChunkLoaded");
	}

	@Override
	public boolean isChunkGenerated(int arg0, int arg1) {
		throw notYet("isChunkGenerated");
	}

	@Override
	public boolean isChunkInUse(int arg0, int arg1) {
		throw notYet("isChunkInUse");
	}

	@Override
	public boolean loadChunk(int arg0, int arg1, boolean arg2) {
		throw notYet("loadChunk");
	}

	@Override
	public boolean unloadChunk(int arg0, int arg1, boolean arg2) {
		throw notYet("unloadChunk");
	}

	@Override
	public boolean unloadChunkRequest(int arg0, int arg1) {
		throw notYet("unloadChunkRequest");
	}

	@Override
	public boolean refreshChunk(int arg0, int arg1) {
		throw notYet("refreshChunk");
	}

	@Override
	public java.util.Collection<org.bukkit.entity.Player> getPlayersSeeingChunk(org.bukkit.Chunk arg0) {
		throw notYet("getPlayersSeeingChunk");
	}

	@Override
	public java.util.Collection<org.bukkit.entity.Player> getPlayersSeeingChunk(int arg0, int arg1) {
		throw notYet("getPlayersSeeingChunk");
	}

	@Override
	public boolean isChunkForceLoaded(int arg0, int arg1) {
		throw notYet("isChunkForceLoaded");
	}

	@Override
	public void setChunkForceLoaded(int arg0, int arg1, boolean arg2) {
		throw notYet("setChunkForceLoaded");
	}

	@Override
	public java.util.Collection<org.bukkit.Chunk> getForceLoadedChunks() {
		throw notYet("getForceLoadedChunks");
	}

	@Override
	public boolean addPluginChunkTicket(int arg0, int arg1, org.bukkit.plugin.Plugin arg2) {
		throw notYet("addPluginChunkTicket");
	}

	@Override
	public boolean removePluginChunkTicket(int arg0, int arg1, org.bukkit.plugin.Plugin arg2) {
		throw notYet("removePluginChunkTicket");
	}

	@Override
	public void removePluginChunkTickets(org.bukkit.plugin.Plugin arg0) {
		throw notYet("removePluginChunkTickets");
	}

	@Override
	public java.util.Collection<org.bukkit.plugin.Plugin> getPluginChunkTickets(int arg0, int arg1) {
		throw notYet("getPluginChunkTickets");
	}

	@Override
	public java.util.Map<org.bukkit.plugin.Plugin, java.util.Collection<org.bukkit.Chunk>> getPluginChunkTickets() {
		throw notYet("getPluginChunkTickets");
	}

	@Override
	public java.util.Collection<org.bukkit.Chunk> getIntersectingChunks(org.bukkit.util.BoundingBox arg0) {
		throw notYet("getIntersectingChunks");
	}

	@Override
	public org.bukkit.entity.Item dropItem(org.bukkit.Location arg0, org.bukkit.inventory.ItemStack arg1, java.util.function.Consumer<? super org.bukkit.entity.Item> arg2) {
		throw notYet("dropItem");
	}

	@Override
	public org.bukkit.entity.Item dropItemNaturally(org.bukkit.Location arg0, org.bukkit.inventory.ItemStack arg1, java.util.function.Consumer<? super org.bukkit.entity.Item> arg2) {
		throw notYet("dropItemNaturally");
	}

	@Override
	public <T extends org.bukkit.entity.AbstractArrow> T spawnArrow(org.bukkit.Location arg0, org.bukkit.util.Vector arg1, float arg2, float arg3, java.lang.Class<T> arg4) {
		throw notYet("spawnArrow");
	}

	@Override
	public boolean generateTree(org.bukkit.Location arg0, org.bukkit.TreeType arg1) {
		throw notYet("generateTree");
	}

	@Override
	public boolean generateTree(org.bukkit.Location arg0, org.bukkit.TreeType arg1, org.bukkit.BlockChangeDelegate arg2) {
		throw notYet("generateTree");
	}

	@Override
	public org.bukkit.entity.LightningStrike strikeLightning(org.bukkit.Location arg0) {
		throw notYet("strikeLightning");
	}

	@Override
	public org.bukkit.entity.LightningStrike strikeLightningEffect(org.bukkit.Location arg0) {
		throw notYet("strikeLightningEffect");
	}

	@Override
	public org.bukkit.Location findLightningRod(org.bukkit.Location arg0) {
		throw notYet("findLightningRod");
	}

	@Override
	public org.bukkit.Location findLightningTarget(org.bukkit.Location arg0) {
		throw notYet("findLightningTarget");
	}

	@Override
	public java.util.List<org.bukkit.entity.Entity> getEntities() {
		throw notYet("getEntities");
	}

	@Override
	public java.util.List<org.bukkit.entity.LivingEntity> getLivingEntities() {
		throw notYet("getLivingEntities");
	}

	@Override
	public <T extends org.bukkit.entity.Entity> java.util.Collection<T> getEntitiesByClass(java.lang.Class<T>... arg0) {
		throw notYet("getEntitiesByClass");
	}

	@Override
	public <T extends org.bukkit.entity.Entity> java.util.Collection<T> getEntitiesByClass(java.lang.Class<T> arg0) {
		throw notYet("getEntitiesByClass");
	}

	@Override
	public java.util.Collection<org.bukkit.entity.Entity> getEntitiesByClasses(java.lang.Class<?>... arg0) {
		throw notYet("getEntitiesByClasses");
	}

	@Override
	public void getChunkAtAsync(int arg0, int arg1, boolean arg2, boolean arg3, java.util.function.Consumer<? super org.bukkit.Chunk> arg4) {
		throw notYet("getChunkAtAsync");
	}

	@Override
	public void getChunksAtAsync(int arg0, int arg1, int arg2, int arg3, boolean arg4, java.lang.Runnable arg5) {
		throw notYet("getChunksAtAsync");
	}

	@Override
	public org.bukkit.entity.Entity getEntity(java.util.UUID arg0) {
		throw notYet("getEntity");
	}

	@Override
	public java.util.Collection<org.bukkit.entity.Entity> getNearbyEntities(org.bukkit.Location arg0, double arg1, double arg2, double arg3, java.util.function.Predicate<? super org.bukkit.entity.Entity> arg4) {
		throw notYet("getNearbyEntities");
	}

	@Override
	public java.util.Collection<org.bukkit.entity.Entity> getNearbyEntities(org.bukkit.util.BoundingBox arg0, java.util.function.Predicate<? super org.bukkit.entity.Entity> arg1) {
		throw notYet("getNearbyEntities");
	}

	@Override
	public org.bukkit.util.RayTraceResult rayTraceEntities(io.papermc.paper.math.Position arg0, org.bukkit.util.Vector arg1, double arg2, double arg3, java.util.function.Predicate<? super org.bukkit.entity.Entity> arg4) {
		throw notYet("rayTraceEntities");
	}

	@Override
	public org.bukkit.util.RayTraceResult rayTraceBlocks(io.papermc.paper.math.Position arg0, org.bukkit.util.Vector arg1, double arg2, org.bukkit.FluidCollisionMode arg3, boolean arg4, java.util.function.Predicate<? super org.bukkit.block.Block> arg5) {
		throw notYet("rayTraceBlocks");
	}

	@Override
	public org.bukkit.util.RayTraceResult rayTrace(io.papermc.paper.math.Position arg0, org.bukkit.util.Vector arg1, double arg2, org.bukkit.FluidCollisionMode arg3, boolean arg4, double arg5, java.util.function.Predicate<? super org.bukkit.entity.Entity> arg6, java.util.function.Predicate<? super org.bukkit.block.Block> arg7) {
		throw notYet("rayTrace");
	}

	@Override
	public org.bukkit.util.RayTraceResult rayTrace(java.util.function.Consumer<io.papermc.paper.raytracing.PositionedRayTraceConfigurationBuilder> arg0) {
		throw notYet("rayTrace");
	}

	@Override
	public boolean setSpawnLocation(org.bukkit.Location arg0) {
		throw notYet("setSpawnLocation");
	}

	@Override
	public boolean setSpawnLocation(int arg0, int arg1, int arg2, float arg3) {
		throw notYet("setSpawnLocation");
	}

	@Override
	public long getGameTime() {
		throw notYet("getGameTime");
	}

	@Override
	public boolean hasStorm() {
		throw notYet("hasStorm");
	}

	@Override
	public void setStorm(boolean arg0) {
		throw notYet("setStorm");
	}

	@Override
	public int getWeatherDuration() {
		throw notYet("getWeatherDuration");
	}

	@Override
	public void setWeatherDuration(int arg0) {
		throw notYet("setWeatherDuration");
	}

	@Override
	public boolean isThundering() {
		throw notYet("isThundering");
	}

	@Override
	public void setThundering(boolean arg0) {
		throw notYet("setThundering");
	}

	@Override
	public int getThunderDuration() {
		throw notYet("getThunderDuration");
	}

	@Override
	public void setThunderDuration(int arg0) {
		throw notYet("setThunderDuration");
	}

	@Override
	public boolean isClearWeather() {
		throw notYet("isClearWeather");
	}

	@Override
	public void setClearWeatherDuration(int arg0) {
		throw notYet("setClearWeatherDuration");
	}

	@Override
	public int getClearWeatherDuration() {
		throw notYet("getClearWeatherDuration");
	}

	@Override
	public boolean createExplosion(double arg0, double arg1, double arg2, float arg3, boolean arg4, boolean arg5, org.bukkit.entity.Entity arg6) {
		throw notYet("createExplosion");
	}

	@Override
	public boolean createExplosion(org.bukkit.entity.Entity arg0, org.bukkit.Location arg1, float arg2, boolean arg3, boolean arg4, boolean arg5) {
		throw notYet("createExplosion");
	}

	@Override
	public boolean createExplosion(org.bukkit.Location arg0, float arg1, boolean arg2, boolean arg3, org.bukkit.entity.Entity arg4) {
		throw notYet("createExplosion");
	}

	@Override
	public boolean getPVP() {
		throw notYet("getPVP");
	}

	@Override
	public void setPVP(boolean arg0) {
		throw notYet("setPVP");
	}

	@Override
	public org.bukkit.generator.ChunkGenerator getGenerator() {
		throw notYet("getGenerator");
	}

	@Override
	public org.bukkit.generator.BiomeProvider getBiomeProvider() {
		throw notYet("getBiomeProvider");
	}

	@Override
	public void save(boolean arg0) {
		throw notYet("save");
	}

	@Override
	public java.util.List<org.bukkit.generator.BlockPopulator> getPopulators() {
		throw notYet("getPopulators");
	}

	@Override
	public <T extends org.bukkit.entity.LivingEntity> T spawn(org.bukkit.Location arg0, java.lang.Class<T> arg1, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason arg2, boolean arg3, java.util.function.Consumer<? super T> arg4) throws java.lang.IllegalArgumentException {
		throw notYet("spawn");
	}

	@Override
	public org.bukkit.entity.FallingBlock spawnFallingBlock(org.bukkit.Location arg0, org.bukkit.material.MaterialData arg1) throws java.lang.IllegalArgumentException {
		throw notYet("spawnFallingBlock");
	}

	@Override
	public org.bukkit.entity.FallingBlock spawnFallingBlock(org.bukkit.Location arg0, org.bukkit.block.data.BlockData arg1) throws java.lang.IllegalArgumentException {
		throw notYet("spawnFallingBlock");
	}

	@Override
	public org.bukkit.entity.FallingBlock spawnFallingBlock(org.bukkit.Location arg0, org.bukkit.Material arg1, byte arg2) throws java.lang.IllegalArgumentException {
		throw notYet("spawnFallingBlock");
	}

	@Override
	public void playEffect(org.bukkit.Location arg0, org.bukkit.Effect arg1, int arg2, int arg3) {
		throw notYet("playEffect");
	}

	@Override
	public <T> void playEffect(org.bukkit.Location arg0, org.bukkit.Effect arg1, T arg2, int arg3) {
		throw notYet("playEffect");
	}

	@Override
	public org.bukkit.ChunkSnapshot getEmptyChunkSnapshot(int arg0, int arg1, boolean arg2, boolean arg3) {
		throw notYet("getEmptyChunkSnapshot");
	}

	@Override
	public void setAllowMonsterSpawning(boolean arg0) {
		throw notYet("setAllowMonsterSpawning");
	}

	@Override
	public void setSpawnFlags(boolean arg0, boolean arg1) {
		throw notYet("setSpawnFlags");
	}

	@Override
	public boolean getAllowAnimals() {
		throw notYet("getAllowAnimals");
	}

	@Override
	public boolean getAllowMonsters() {
		throw notYet("getAllowMonsters");
	}

	@Override
	public void setBiome(int arg0, int arg1, org.bukkit.block.Biome arg2) {
		throw notYet("setBiome");
	}

	@Override
	public double getTemperature(int arg0, int arg1, int arg2) {
		throw notYet("getTemperature");
	}

	@Override
	public double getHumidity(int arg0, int arg1, int arg2) {
		throw notYet("getHumidity");
	}

	@Override
	public int getLogicalHeight() {
		throw notYet("getLogicalHeight");
	}

	@Override
	public boolean isNatural() {
		throw notYet("isNatural");
	}

	@Override
	public boolean isBedWorks() {
		throw notYet("isBedWorks");
	}

	@Override
	public boolean hasSkyLight() {
		throw notYet("hasSkyLight");
	}

	@Override
	public boolean hasCeiling() {
		throw notYet("hasCeiling");
	}

	@Override
	public boolean isPiglinSafe() {
		throw notYet("isPiglinSafe");
	}

	@Override
	public boolean isRespawnAnchorWorks() {
		throw notYet("isRespawnAnchorWorks");
	}

	@Override
	public boolean hasRaids() {
		throw notYet("hasRaids");
	}

	@Override
	public boolean isUltraWarm() {
		throw notYet("isUltraWarm");
	}

	@Override
	public boolean isAutoSave() {
		throw notYet("isAutoSave");
	}

	@Override
	public void setAutoSave(boolean arg0) {
		throw notYet("setAutoSave");
	}

	@Override
	public void setDifficulty(org.bukkit.Difficulty arg0) {
		throw notYet("setDifficulty");
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
	public java.nio.file.Path getWorldPath() {
		throw notYet("getWorldPath");
	}

	@Override
	public org.bukkit.WorldType getWorldType() {
		throw notYet("getWorldType");
	}

	@Override
	public boolean canGenerateStructures() {
		throw notYet("canGenerateStructures");
	}

	@Override
	public boolean hasBonusChest() {
		throw notYet("hasBonusChest");
	}

	@Override
	public boolean isHardcore() {
		throw notYet("isHardcore");
	}

	@Override
	public void setHardcore(boolean arg0) {
		throw notYet("setHardcore");
	}

	@Override
	public long getTicksPerSpawns(org.bukkit.entity.SpawnCategory arg0) {
		throw notYet("getTicksPerSpawns");
	}

	@Override
	public void setTicksPerSpawns(org.bukkit.entity.SpawnCategory arg0, int arg1) {
		throw notYet("setTicksPerSpawns");
	}

	@Override
	public int getSpawnLimit(org.bukkit.entity.SpawnCategory arg0) {
		throw notYet("getSpawnLimit");
	}

	@Override
	public void setSpawnLimit(org.bukkit.entity.SpawnCategory arg0, int arg1) {
		throw notYet("setSpawnLimit");
	}

	@Override
	public void playSound(org.bukkit.Location arg0, org.bukkit.Sound arg1, org.bukkit.SoundCategory arg2, float arg3, float arg4) {
		throw notYet("playSound");
	}

	@Override
	public void playSound(org.bukkit.Location arg0, java.lang.String arg1, org.bukkit.SoundCategory arg2, float arg3, float arg4) {
		throw notYet("playSound");
	}

	@Override
	public void playSound(org.bukkit.Location arg0, org.bukkit.Sound arg1, org.bukkit.SoundCategory arg2, float arg3, float arg4, long arg5) {
		throw notYet("playSound");
	}

	@Override
	public void playSound(org.bukkit.Location arg0, java.lang.String arg1, org.bukkit.SoundCategory arg2, float arg3, float arg4, long arg5) {
		throw notYet("playSound");
	}

	@Override
	public void playSound(org.bukkit.entity.Entity arg0, org.bukkit.Sound arg1, org.bukkit.SoundCategory arg2, float arg3, float arg4) {
		throw notYet("playSound");
	}

	@Override
	public void playSound(org.bukkit.entity.Entity arg0, java.lang.String arg1, org.bukkit.SoundCategory arg2, float arg3, float arg4) {
		throw notYet("playSound");
	}

	@Override
	public void playSound(org.bukkit.entity.Entity arg0, org.bukkit.Sound arg1, org.bukkit.SoundCategory arg2, float arg3, float arg4, long arg5) {
		throw notYet("playSound");
	}

	@Override
	public void playSound(org.bukkit.entity.Entity arg0, java.lang.String arg1, org.bukkit.SoundCategory arg2, float arg3, float arg4, long arg5) {
		throw notYet("playSound");
	}

	@Override
	public java.lang.String[] getGameRules() {
		throw notYet("getGameRules");
	}

	@Override
	public java.lang.String getGameRuleValue(java.lang.String arg0) {
		throw notYet("getGameRuleValue");
	}

	@Override
	public boolean setGameRuleValue(java.lang.String arg0, java.lang.String arg1) {
		throw notYet("setGameRuleValue");
	}

	@Override
	public boolean isGameRule(java.lang.String arg0) {
		throw notYet("isGameRule");
	}

	@Override
	public <T> T getGameRuleValue(org.bukkit.GameRule<T> arg0) {
		throw notYet("getGameRuleValue");
	}

	@Override
	public <T> boolean setGameRule(org.bukkit.GameRule<T> arg0, T arg1) {
		throw notYet("setGameRule");
	}

	@Override
	public org.bukkit.WorldBorder getWorldBorder() {
		throw notYet("getWorldBorder");
	}

	@Override
	public <T> void spawnParticle(org.bukkit.Particle arg0, java.util.List<org.bukkit.entity.Player> arg1, org.bukkit.entity.Player arg2, double arg3, double arg4, double arg5, int arg6, double arg7, double arg8, double arg9, double arg10, T arg11, boolean arg12) {
		throw notYet("spawnParticle");
	}

	@Override
	public org.bukkit.Location locateNearestStructure(org.bukkit.Location arg0, org.bukkit.StructureType arg1, int arg2, boolean arg3) {
		throw notYet("locateNearestStructure");
	}

	@Override
	public org.bukkit.util.StructureSearchResult locateNearestStructure(org.bukkit.Location arg0, org.bukkit.generator.structure.StructureType arg1, int arg2, boolean arg3) {
		throw notYet("locateNearestStructure");
	}

	@Override
	public org.bukkit.util.StructureSearchResult locateNearestStructure(org.bukkit.Location arg0, org.bukkit.generator.structure.Structure arg1, int arg2, boolean arg3) {
		throw notYet("locateNearestStructure");
	}

	@Override
	public org.bukkit.Location locateNearestPoi(org.bukkit.Location arg0, io.papermc.paper.entity.poi.PoiType arg1, int arg2, io.papermc.paper.entity.poi.PoiType.Occupancy arg3) {
		throw notYet("locateNearestPoi");
	}

	@Override
	public java.util.List<io.papermc.paper.entity.poi.PoiSearchResult> locateAllPoiInRange(org.bukkit.Location arg0, java.util.function.Predicate<io.papermc.paper.entity.poi.PoiType> arg1, int arg2, io.papermc.paper.entity.poi.PoiType.Occupancy arg3) {
		throw notYet("locateAllPoiInRange");
	}

	@Override
	public double getCoordinateScale() {
		throw notYet("getCoordinateScale");
	}

	@Override
	public boolean isFixedTime() {
		throw notYet("isFixedTime");
	}

	@Override
	public java.util.Collection<org.bukkit.Material> getInfiniburn() {
		throw notYet("getInfiniburn");
	}

	@Override
	public void sendGameEvent(org.bukkit.entity.Entity arg0, org.bukkit.GameEvent arg1, org.bukkit.util.Vector arg2) {
		throw notYet("sendGameEvent");
	}

	@Override
	public org.bukkit.World.Spigot spigot() {
		throw notYet("spigot");
	}

	@Override
	public org.bukkit.util.BiomeSearchResult locateNearestBiome(org.bukkit.Location arg0, int arg1, int arg2, int arg3, org.bukkit.block.Biome... arg4) {
		throw notYet("locateNearestBiome");
	}

	@Override
	public org.bukkit.Raid locateNearestRaid(org.bukkit.Location arg0, int arg1) {
		throw notYet("locateNearestRaid");
	}

	@Override
	public org.bukkit.Raid getRaid(int arg0) {
		throw notYet("getRaid");
	}

	@Override
	public java.util.List<org.bukkit.Raid> getRaids() {
		throw notYet("getRaids");
	}

	@Override
	public org.bukkit.boss.DragonBattle getEnderDragonBattle() {
		throw notYet("getEnderDragonBattle");
	}

	@Override
	public java.util.Set<org.bukkit.FeatureFlag> getFeatureFlags() {
		throw notYet("getFeatureFlags");
	}

	@Override
	public void setViewDistance(int arg0) {
		throw notYet("setViewDistance");
	}

	@Override
	public void setSimulationDistance(int arg0) {
		throw notYet("setSimulationDistance");
	}

	@Override
	public int getSendViewDistance() {
		throw notYet("getSendViewDistance");
	}

	@Override
	public void setSendViewDistance(int arg0) {
		throw notYet("setSendViewDistance");
	}

	@Override
	public java.util.Collection<org.bukkit.generator.structure.GeneratedStructure> getStructures(int arg0, int arg1) {
		throw notYet("getStructures");
	}

	@Override
	public java.util.Collection<org.bukkit.generator.structure.GeneratedStructure> getStructures(int arg0, int arg1, org.bukkit.generator.structure.Structure arg2) {
		throw notYet("getStructures");
	}

	@Override
	public org.bukkit.block.Biome getBiome(int arg0, int arg1, int arg2) {
		throw notYet("getBiome");
	}

	@Override
	public org.bukkit.block.Biome getComputedBiome(int arg0, int arg1, int arg2) {
		throw notYet("getComputedBiome");
	}

	@Override
	public void setBiome(int arg0, int arg1, int arg2, org.bukkit.block.Biome arg3) {
		throw notYet("setBiome");
	}

	@Override
	public org.bukkit.block.BlockState getBlockState(int arg0, int arg1, int arg2) {
		throw notYet("getBlockState");
	}

	@Override
	public io.papermc.paper.block.fluid.FluidData getFluidData(int arg0, int arg1, int arg2) {
		throw notYet("getFluidData");
	}

	@Override
	public org.bukkit.block.data.BlockData getBlockData(int arg0, int arg1, int arg2) {
		throw notYet("getBlockData");
	}

	@Override
	public org.bukkit.Material getType(int arg0, int arg1, int arg2) {
		throw notYet("getType");
	}

	@Override
	public void setBlockData(int arg0, int arg1, int arg2, org.bukkit.block.data.BlockData arg3) {
		throw notYet("setBlockData");
	}

	@Override
	public boolean generateTree(org.bukkit.Location arg0, java.util.Random arg1, org.bukkit.TreeType arg2) {
		throw notYet("generateTree");
	}

	@Override
	public boolean generateTree(org.bukkit.Location arg0, java.util.Random arg1, org.bukkit.TreeType arg2, java.util.function.Consumer<? super org.bukkit.block.BlockState> arg3) {
		throw notYet("generateTree");
	}

	@Override
	public boolean generateTree(org.bukkit.Location arg0, java.util.Random arg1, org.bukkit.TreeType arg2, java.util.function.Predicate<? super org.bukkit.block.BlockState> arg3) {
		throw notYet("generateTree");
	}

	@Override
	public org.bukkit.entity.Entity spawnEntity(org.bukkit.Location arg0, org.bukkit.entity.EntityType arg1, boolean arg2) {
		throw notYet("spawnEntity");
	}

	@Override
	public <T extends org.bukkit.entity.Entity> T createEntity(org.bukkit.Location arg0, java.lang.Class<T> arg1) {
		throw notYet("createEntity");
	}

	@Override
	public <T extends org.bukkit.entity.Entity> T spawn(org.bukkit.Location arg0, java.lang.Class<T> arg1, java.util.function.Consumer<? super T> arg2, org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason arg3) throws java.lang.IllegalArgumentException {
		throw notYet("spawn");
	}

	@Override
	public <T extends org.bukkit.entity.Entity> T spawn(org.bukkit.Location arg0, java.lang.Class<T> arg1, boolean arg2, java.util.function.Consumer<? super T> arg3) throws java.lang.IllegalArgumentException {
		throw notYet("spawn");
	}

	@Override
	public int getHighestBlockYAt(int arg0, int arg1) {
		throw notYet("getHighestBlockYAt");
	}

	@Override
	public int getHighestBlockYAt(org.bukkit.Location arg0) {
		throw notYet("getHighestBlockYAt");
	}

	@Override
	public int getHighestBlockYAt(int arg0, int arg1, org.bukkit.HeightMap arg2) {
		throw notYet("getHighestBlockYAt");
	}

	@Override
	public int getHighestBlockYAt(org.bukkit.Location arg0, org.bukkit.HeightMap arg1) {
		throw notYet("getHighestBlockYAt");
	}

	@Override
	public <T extends org.bukkit.entity.Entity> T addEntity(T arg0) {
		throw notYet("addEntity");
	}

	@Override
	public io.papermc.paper.world.MoonPhase getMoonPhase() {
		throw notYet("getMoonPhase");
	}

	@Override
	public org.bukkit.NamespacedKey getKey() {
		throw notYet("getKey");
	}

	@Override
	public boolean lineOfSightExists(org.bukkit.Location arg0, org.bukkit.Location arg1) {
		throw notYet("lineOfSightExists");
	}

	@Override
	public boolean hasCollisionsIn(org.bukkit.util.BoundingBox arg0) {
		throw notYet("hasCollisionsIn");
	}

	@Override
	public net.kyori.adventure.key.Key key() {
		throw notYet("key");
	}

	@Override
	public org.bukkit.World.Environment getEnvironment() {
		throw notYet("getEnvironment");
	}

	@Override
	public long getSeed() {
		throw notYet("getSeed");
	}

	@Override
	public org.bukkit.generator.BiomeProvider vanillaBiomeProvider() {
		throw notYet("vanillaBiomeProvider");
	}

	@Override
	public void sendPluginMessage(org.bukkit.plugin.Plugin arg0, java.lang.String arg1, byte[] arg2) {
		throw notYet("sendPluginMessage");
	}

	@Override
	public java.util.Set<java.lang.String> getListeningPluginChannels() {
		throw notYet("getListeningPluginChannels");
	}

	@Override
	public void setMetadata(java.lang.String arg0, org.bukkit.metadata.MetadataValue arg1) {
		throw notYet("setMetadata");
	}

	@Override
	public java.util.List<org.bukkit.metadata.MetadataValue> getMetadata(java.lang.String arg0) {
		throw notYet("getMetadata");
	}

	@Override
	public boolean hasMetadata(java.lang.String arg0) {
		throw notYet("hasMetadata");
	}

	@Override
	public void removeMetadata(java.lang.String arg0, org.bukkit.plugin.Plugin arg1) {
		throw notYet("removeMetadata");
	}

	@Override
	public org.bukkit.persistence.PersistentDataContainer getPersistentDataContainer() {
		throw notYet("getPersistentDataContainer");
	}

	@Override
	public java.lang.Iterable<? extends net.kyori.adventure.audience.Audience> audiences() {
		throw notYet("audiences");
	}}
