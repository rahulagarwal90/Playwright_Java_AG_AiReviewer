package com.framework.config;

import org.aeonbits.owner.ConfigFactory;

public class FrameworkConfigManager {
    private static FrameworkConfig config;

    private FrameworkConfigManager() {}

    public static FrameworkConfig getConfig() {
        if (config == null) {
            config = ConfigFactory.create(FrameworkConfig.class);
        }
        return config;
    }
}
