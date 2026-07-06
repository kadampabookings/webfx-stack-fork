package dev.webfx.stack.db.migration;

import dev.webfx.platform.boot.ApplicationReadiness;
import dev.webfx.platform.boot.spi.ApplicationJob;
import dev.webfx.platform.conf.ConfigLoader;
import dev.webfx.platform.console.Console;
import dev.webfx.platform.service.SingleServiceProvider;
import dev.webfx.stack.db.datasource.LocalDataSourceService;
import dev.webfx.stack.db.migration.spi.MigrationScriptsProvider;

import java.util.List;
import java.util.ServiceLoader;

/**
 * Boot job that applies the pending DB migrations before the application becomes ready. onInit() runs at the
 * JOBS_INIT boot level, i.e. before the HTTP server starts, and registers a readiness gate that keeps /health
 * answering 503 until the migrations have been applied successfully. On failure everything has been rolled
 * back and the gate is never completed: the application stays alive but unhealthy, so a blue/green deployment
 * never switches traffic to it and rolls back to the previous version (which keeps running against the
 * untouched database). Failures are also recorded in the db_migration table (success = false rows).
 *
 * @author Bruno Salmon
 */
public final class DbMigrationJob implements ApplicationJob {

    private static final String READINESS_GATE_NAME = "db-migration";
    private static final String CONFIG_PATH = "webfx.stack.db.migration";
    // Set `webfx.stack.db.migration.apply = false` (e.g. in a dev machine's conf/ override) to prevent this
    // instance from applying migrations — the deployed pipeline is then the only writer of the shared
    // database's schema. The job just logs the bundled scripts and completes the readiness gate.
    private static final String APPLY_CONFIG_KEY = "apply";

    @Override
    public void onInit() {
        // Note: exceptions must never escape onInit() (the boot sequence doesn't handle them consistently);
        // every failure path below just logs and leaves the readiness gate pending.
        MigrationScriptsProvider scriptsProvider = SingleServiceProvider.getProvider(MigrationScriptsProvider.class,
            () -> ServiceLoader.load(MigrationScriptsProvider.class), SingleServiceProvider.NotFoundPolicy.RETURN_NULL);
        if (scriptsProvider == null) {
            log("No MigrationScriptsProvider registered — skipping DB migrations");
            return;
        }
        Runnable markReady = ApplicationReadiness.registerPendingReadinessGate(READINESS_GATE_NAME);
        List<MigrationScript> scripts;
        try {
            scripts = scriptsProvider.getMigrationScripts();
        } catch (RuntimeException e) {
            Console.error("❌ DB MIGRATION FAILED — the bundled migration scripts are invalid; this application will stay unhealthy (/health = 503)", e);
            return;
        }
        if (scripts.isEmpty()) {
            log("No DB migration scripts bundled");
            markReady.run();
            return;
        }
        ConfigLoader.onConfigLoaded(CONFIG_PATH, config -> { // config is null when the path is not declared anywhere
            if (config != null && Boolean.FALSE.equals(config.getBoolean(APPLY_CONFIG_KEY, Boolean.TRUE))) {
                log("⏭️ DB migration apply is disabled on this instance (" + CONFIG_PATH + "." + APPLY_CONFIG_KEY + " = false) — " + scripts.size() + " scripts bundled but not applied; the deployed pipeline is responsible for applying them");
                markReady.run();
                return;
            }
            log("Waiting for the datasource before checking DB migrations (" + scripts.size() + " scripts bundled)...");
            LocalDataSourceService.onInitialised(() ->
                new DbMigrationRunner(scriptsProvider.getDataSourceId(), scripts).run()
                    .onSuccess(result -> {
                        log("✅ DB migration: " + result);
                        markReady.run();
                    })
                    .onFailure(e -> Console.error("❌ DB MIGRATION FAILED — all changes have been rolled back, and this application will stay unhealthy (/health = 503) so a blue/green deployment rolls back to the previous version. Check the db_migration table (success = false rows) and the logs above.", e)));
        });
    }
}
