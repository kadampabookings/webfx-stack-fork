// File managed by WebFX (DO NOT EDIT MANUALLY)

module webfx.stack.com.bus {

    // Direct dependencies modules
    requires java.base;
    requires transitive webfx.platform.async;
    requires webfx.platform.console;
    requires webfx.platform.json;
    requires webfx.platform.scheduler;
    requires webfx.platform.util;
    requires webfx.stack.conf;

    // Exported packages
    exports dev.webfx.stack.com.bus;
    exports dev.webfx.stack.com.bus.spi;
    exports dev.webfx.stack.com.bus.spi.impl;

    // Used services
    uses dev.webfx.stack.com.bus.spi.BusServiceProvider;

    // Provided services
    provides dev.webfx.stack.conf.spi.ConfigurationConsumer with dev.webfx.stack.com.bus.spi.impl.BusOptionsConfigurationConsumer;

}