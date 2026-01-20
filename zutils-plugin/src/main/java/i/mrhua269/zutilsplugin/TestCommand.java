package i.mrhua269.zutilsplugin;

import com.mojang.brigadier.arguments.StringArgumentType;
import i.mrhua269.zutils.api.WorldManager;
import i.mrhua269.zutils.api.ZAPIEntryPoint;
import io.papermc.paper.command.brigadier.Commands;
import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;

import java.util.List;

public class TestCommand {

    public static void register(Plugin plugin, Commands commands) {
        // --- testcreateworld <name> ---
        commands.register(
                Commands.literal("testcreateworld")
                        .requires(source -> source.getSender().hasPermission("zutils.admin"))
                        .then(Commands.argument("worldName", StringArgumentType.string())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "worldName");
                                    ctx.getSource().getSender().sendMessage("§e[ZUtils] 正在准备创建世界: " + name);

                                    // 关键：Folia 必须调度到 GlobalRegion 才能创建世界
                                    Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                                        WorldManager manager = ZAPIEntryPoint.getWorldManager();
                                        try {
                                            manager.createWorld(new WorldCreator(name));
                                            Bukkit.getConsoleSender().sendMessage("§a[ZUtils] 世界 " + name + " 创建成功！");
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    });
                                    return 1;
                                })
                        ).build(),
                "测试创建世界", List.of()
        );

        // --- testunloadworld <name> ---
        commands.register(
                Commands.literal("testunloadworld")
                        .requires(source -> source.getSender().hasPermission("zutils.admin"))
                        .then(Commands.argument("worldName", StringArgumentType.string())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "worldName");
                                    ctx.getSource().getSender().sendMessage("§e[ZUtils] 正在准备卸载世界: " + name);

                                    Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                                        WorldManager manager = ZAPIEntryPoint.getWorldManager();
                                        boolean success = manager.unloadWorld(name, true);
                                        if (success) {
                                            Bukkit.getConsoleSender().sendMessage("§a[ZUtils] 世界 " + name + " 卸载任务已提交。");
                                        } else {
                                            Bukkit.getConsoleSender().sendMessage("§c[ZUtils] 世界 " + name + " 卸载失败（可能是主世界或有玩家）。");
                                        }
                                    });
                                    return 1;
                                })
                        ).build(),
                "测试卸载世界", List.of()
        );
    }
}