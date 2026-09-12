package com.ysmef.geomodel;

import com.ysmef.geomodel.config.YSMCompatConfig;
import com.ysmef.geomodel.ysm.script.Molang;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Mod(YSMGeoCompat.MODID)
public class YSMGeoCompat {

    public static final String MODID = "ysm_geo_compat";
    public static final Logger LOGGER = LogManager.getLogger("YSM-GEO Compat");

    public YSMGeoCompat() {
        YSMCompatConfig.register();
        installMolangErrorReporter();
        LOGGER.info("YSM-GEO Compat: Initialized successfully");
    }

    /**
     * Route molang compile failures to the mod log, once per distinct source
     * string. Molang itself stays free of any logging dependency (it is a plain
     * evaluator) and a broken expression previously produced a silent 0, which
     * showed up only as "this variant stopped appearing".
     */
    private static void installMolangErrorReporter() {
        Set<String> reported = ConcurrentHashMap.newKeySet();
        Molang.setErrorReporter((source, error) -> {
            if (reported.add(source)) {
                LOGGER.warn("YSM-GEO Compat: failed to compile molang '{}': {} (the expression evaluates to 0)",
                        source, error.toString());
            }
        });
    }
}
