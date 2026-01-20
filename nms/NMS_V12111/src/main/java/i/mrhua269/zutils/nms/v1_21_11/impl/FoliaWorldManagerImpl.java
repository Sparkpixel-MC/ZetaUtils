package i.mrhua269.zutils.nms.v1_21_11.impl;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import com.google.common.collect.ImmutableList;
import com.mojang.serialization.Dynamic;
import i.mrhua269.zutils.api.WorldManager;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.village.VillageSiege;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.level.levelgen.PhantomSpawner;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.craftbukkit.CraftServer;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class FoliaWorldManagerImpl implements WorldManager {

    private static final Method TRY_KILL_METHOD;
    private static final Field CRAFT_WORLDS_FIELD;
    private static final Field REGIONIZED_WORLDS_FIELD;
    private static final Field REGION_DATA_FIELD;

    static {
        try {
            TRY_KILL_METHOD = ThreadedRegionizer.ThreadedRegion.class.getDeclaredMethod("tryKill");
            TRY_KILL_METHOD.setAccessible(true);
            CRAFT_WORLDS_FIELD = CraftServer.class.getDeclaredField("worlds");
            CRAFT_WORLDS_FIELD.setAccessible(true);
            REGIONIZED_WORLDS_FIELD = RegionizedServer.class.getDeclaredField("worlds");
            REGIONIZED_WORLDS_FIELD.setAccessible(true);
            REGION_DATA_FIELD = ThreadedRegionizer.ThreadedRegion.class.getDeclaredField("data");
            REGION_DATA_FIELD.setAccessible(true);
        } catch (Exception e) {
            throw new RuntimeException("Folia 1.21.1 NMS 反射初始化失败，请检查 Mapping 是否匹配", e);
        }
    }

    @Override
    public boolean unloadWorld(@NotNull String name, boolean save) {
        World world = Bukkit.getWorld(name);
        return world != null && unloadWorld(world, save);
    }

    @Override
    public boolean unloadWorld(@NotNull World world, boolean save) {
        RegionizedServer.ensureGlobalTickThread("Unload world must be called on Global Tick Thread");
        ServerLevel handle = ((CraftWorld) world).getHandle();
        if (handle.dimension() == net.minecraft.world.level.Level.OVERWORLD || !handle.players().isEmpty()) {
            return false;
        }
        WorldUnloadEvent event = new WorldUnloadEvent(world);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return false;
        }
        ChunkHolderManager holderManager = handle.moonrise$getChunkTaskScheduler().chunkHolderManager;
        List<CompletableFuture<Void>> regionFutures = new ArrayList<>();
        handle.regioniser.computeForAllRegions(region -> {
            CompletableFuture<Void> future = new CompletableFuture<>();
            try {
                Object data = REGION_DATA_FIELD.get(region);
                Method getExecutor = data.getClass().getMethod("getRegionExecutor");
                Object executor = getExecutor.invoke(data);
                Method executeMethod = executor.getClass().getMethod("execute", Runnable.class);
                executeMethod.invoke(executor, (Runnable) () -> {
                    try {
                        saveChunksInSpecificRegion(handle, holderManager, region);
                        future.complete(null);
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
                regionFutures.add(future);
            } catch (Exception e) {
                future.complete(null);
            }
        });
        CompletableFuture.allOf(regionFutures.toArray(new CompletableFuture[0]))
                .thenRun(() -> RegionizedServer.getInstance().addTask(() -> finalizeUnloadLogic(handle, save)));
        return true;
    }

    private void saveChunksInSpecificRegion(ServerLevel world, ChunkHolderManager holderManager, ThreadedRegionizer.ThreadedRegion<?, ?> region) {
        final int regionShift = world.moonrise$getRegionChunkShift();
        final int width = 1 << regionShift;
        for (LongIterator it = region.getOwnedSectionsUnsynchronised(); it.hasNext();) {
            long sectionKey = it.nextLong();
            int offsetX = CoordinateUtils.getChunkX(sectionKey) << regionShift;
            int offsetZ = CoordinateUtils.getChunkZ(sectionKey) << regionShift;
            for (int dz = 0; dz < width; ++dz) {
                for (int dx = 0; dx < width; ++dx) {
                    NewChunkHolder holder = holderManager.getChunkHolder(offsetX | dx, offsetZ | dz);
                    if (holder != null) {
                        holder.save(true); 
                    }
                }
            }
        }
    }

    private void finalizeUnloadLogic(ServerLevel handle, boolean shouldSave) {
        try {
            handle.moonrise$getChunkTaskScheduler().halt(true, TimeUnit.SECONDS.toNanos(30));
            List<ServerLevel> foliaWorlds = (List<ServerLevel>) REGIONIZED_WORLDS_FIELD.get(RegionizedServer.getInstance());
            foliaWorlds.remove(handle);
            handle.regioniser.computeForAllRegions(region -> {
                try {
                    while (!((boolean) TRY_KILL_METHOD.invoke(region))) {
                        Thread.onSpinWait();
                    }
                } catch (Exception ignored) {}
            });

            if (shouldSave) {
                handle.saveLevelData(false);
                MoonriseRegionFileIO.flush(handle);
                MoonriseRegionFileIO.flushRegionStorages(handle);
            }

            for (MoonriseRegionFileIO.RegionFileType type : MoonriseRegionFileIO.RegionFileType.values()) {
                MoonriseRegionFileIO.getControllerFor(handle, type).getCache().close();
            }
            handle.chunkSource.getDataStorage().close();
            handle.close();
            Map<String, World> worldsMap = (Map<String, World>) CRAFT_WORLDS_FIELD.get(Bukkit.getServer());
            worldsMap.remove(handle.getWorld().getName().toLowerCase(Locale.ENGLISH));
            handle.getServer().removeLevel(handle);

            MinecraftServer.LOGGER.info("Successfully fully unloaded world: {}", handle.getWorld().getName());
        } catch (Exception ex) {
            MinecraftServer.LOGGER.error("Error during final unload of world: {}", handle.getWorld().getName(), ex);
        }
    }

    @Override
    @Nullable
    public World createWorld(@NotNull WorldCreator creator) {
        RegionizedServer.ensureGlobalTickThread("World creation must be on Global Tick Thread");

        CraftServer craftServer = (CraftServer) Bukkit.getServer();
        DedicatedServer console = craftServer.getServer();

        String name = creator.name();
        if (craftServer.getWorld(name) != null) {
            return craftServer.getWorld(name);
        }
        LevelStorageSource.LevelStorageAccess worldSession;
        try {
            worldSession = LevelStorageSource.createDefault(craftServer.getWorldContainer().toPath())
                    .validateAndCreateAccess(name, LevelStem.OVERWORLD);
        } catch (Exception ex) {
            throw new RuntimeException("Failed to access level storage for " + name, ex);
        }
        WorldLoader.DataLoadContext loaderContext = console.worldLoaderContext;
        Registry<LevelStem> stemRegistry = loaderContext.datapackDimensions().lookupOrThrow(Registries.LEVEL_STEM);
        PrimaryLevelData worldData;
        try {
            Dynamic<?> dynamic = worldSession.hasWorldData() ? worldSession.getDataTag() : null;
            if (dynamic != null) {
                LevelDataAndDimensions result = LevelStorageSource.getLevelDataAndDimensions(dynamic, loaderContext.dataConfiguration(), stemRegistry, loaderContext.datapackWorldgen());
                worldData = (PrimaryLevelData) result.worldData();
            } else {
                WorldOptions options = new WorldOptions(creator.seed(), creator.generateStructures(), false);
                LevelSettings settings = new LevelSettings(name, GameType.SURVIVAL, creator.hardcore(), Difficulty.EASY, false, new GameRules(loaderContext.dataConfiguration().enabledFeatures()), loaderContext.dataConfiguration());
                WorldDimensions.Complete complete = new DedicatedServerProperties.WorldDimensionData(GsonHelper.parse("{}"), "overworld").create(loaderContext.datapackWorldgen()).bake(stemRegistry);
                worldData = new PrimaryLevelData(settings, options, complete.specialWorldProperty(), complete.lifecycle());
            }
        } catch (Exception ex) {
            throw new RuntimeException("Failed to initialize world data for " + name, ex);
        }

        worldData.checkName(name);
        List<CustomSpawner> spawners = ImmutableList.of(
                new PhantomSpawner(),
                new PatrolSpawner(),
                new CatSpawner(),
                new VillageSiege(),
                new WanderingTraderSpawner(worldData)
        );

        LevelStem stem = stemRegistry.getOrThrow(LevelStem.OVERWORLD).value();

        ServerLevel internal = null;
        if (creator.generator() != null) {
            if (creator.biomeProvider() != null) {
                internal = new ServerLevel(console, console.executor, worldSession, worldData, ResourceKey.create(Registries.DIMENSION, Identifier.parse(name.toLowerCase())), stem, worldData.isDebugWorld(), BiomeManager.obfuscateSeed(creator.seed()), spawners,
                        true, null, creator.environment(), creator.generator(), creator.biomeProvider()
                );
            }
        }
        if (internal != null) {
            console.addLevel(internal);
        }
        if (internal != null) {
            internal.setSpawnSettings(true);
        }
        RegionizedServer.getInstance().addWorld(internal);

        if (internal != null) {
            Bukkit.getPluginManager().callEvent(new WorldLoadEvent(internal.getWorld()));
        }

        if (internal != null) {
            return internal.getWorld();
        }
        return null;
    }
}