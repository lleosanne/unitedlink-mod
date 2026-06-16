package com.unitedlink.unitedlink;
import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
@Mod(UnitedLinkMod.MOD_ID)
public class UnitedLinkMod {
    public static final String MOD_ID = "unitedlink";
    public static final Logger LOGGER = LogUtils.getLogger();
    public UnitedLinkMod(IEventBus modEventBus) {
        LOGGER.info("[UnitedLink] Initializing UnitedLink mod v1.0.0");
        NeoForge.EVENT_BUS.register(new UnitedLinkCommandHandler());
        NeoForge.EVENT_BUS.register(new UnitedLinkLoginHandler());
        LOGGER.info("[UnitedLink] Command handler registered.");
    }
}
