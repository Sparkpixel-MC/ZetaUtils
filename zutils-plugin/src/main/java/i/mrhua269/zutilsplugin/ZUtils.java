package i.mrhua269.zutilsplugin;

import i.mrhua269.zutils.api.ZAPIEntryPoint;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public final class ZUtils extends JavaPlugin {

    @Override
    public void onEnable() {
        ZAPIEntryPoint.init();
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            TestCommand.register(this, event.registrar());
        });
    }

    @Override
    public void onDisable() {
        Logger.getLogger("ZetaUtils").info("ZetaUtils is disabled");
    }
}
