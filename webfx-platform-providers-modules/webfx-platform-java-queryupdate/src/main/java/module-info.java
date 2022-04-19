// File managed by WebFX (DO NOT EDIT MANUALLY)

module webfx.platform.java.queryupdate {

    // Direct dependencies modules
    requires java.base;
    requires java.sql;
    requires static com.zaxxer.hikari;
    requires webfx.platform.shared.async;
    requires webfx.platform.shared.datasource;
    requires webfx.platform.shared.query;
    requires webfx.platform.shared.submit;
    requires webfx.platform.shared.util;

    // Exported packages
    exports dev.webfx.platform.java.services_shared_code.queryupdate.jdbc;

}