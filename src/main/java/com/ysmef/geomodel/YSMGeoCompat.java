package com.ysmef.geomodel;

import com.ysmef.geomodel.config.YSMCompatConfig;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(YSMGeoCompat.MODID)
public class YSMGeoCompat {

    public static final String MODID = "ysm_geo_compat";
    public static final Logger LOGGER = LogManager.getLogger("YSM-GEO Compat");

    public YSMGeoCompat() {
        YSMCompatConfig.register();
        LOGGER.info("YSM-GEO Compat: Initialized successfully");
    }
}
