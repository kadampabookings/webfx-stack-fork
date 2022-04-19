// File managed by WebFX (DO NOT EDIT MANUALLY)

module webfx.platform.client.windowhistory {

    // Direct dependencies modules
    requires java.base;
    requires webfx.platform.client.windowlocation;
    requires webfx.platform.shared.async;
    requires webfx.platform.shared.json;
    requires webfx.platform.shared.util;

    // Exported packages
    exports dev.webfx.platform.client.services.windowhistory;
    exports dev.webfx.platform.client.services.windowhistory.spi;
    exports dev.webfx.platform.client.services.windowhistory.spi.impl;

    // Used services
    uses dev.webfx.platform.client.services.windowhistory.spi.WindowHistoryProvider;

}