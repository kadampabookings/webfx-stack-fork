package dev.webfx.stack.http.server.vertx;

import dev.webfx.platform.ast.ReadOnlyAstArray;
import dev.webfx.platform.vertx.common.VertxInstance;
import io.vertx.core.Vertx;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.*;

import java.nio.file.Path;

/**
 * @author Bruno Salmon
 */
final class VertxHttpRouterConfigurator {

    static Router initialiseRouter() {
        Vertx vertx = VertxInstance.getVertx();
        Router router = Router.router(vertx);

        // Logging web requests
        router.route().handler(LoggerHandler.create());

        // The session store to use
        router.route().handler(SessionHandler.create(VertxInstance.getSessionStore()));

        // GWT perfect caching (xxx.cache.xxx files will never change again)
        router.routeWithRegex(".*\\.cache\\..*").handler(routingContext -> {
            routingContext.response().putHeader("cache-control", "public, max-age=31556926");
            routingContext.next();
        });

        // SPA root page shouldn't be cached (to always return the latest version with the latest GWT compilation)
        // We assume the SPA is hosted under the root / or under any path ending with / or /index.html or any path
        // including /#/ (which is used for UI routing). This applies also to xxx.nocache.xxx GWT files.
        router.routeWithRegex(".*/|.*/index.html|.*/#/.*|.*\\.nocache\\..*").handler(routingContext -> {
            routingContext.response().putHeader("cache-control", "no-cache");
            routingContext.next();
        });

        return router;
    }

    static void addStaticRoute(String routePattern, ReadOnlyAstArray hostnamePatterns, String pathToStaticFolder) {
        if (hostnamePatterns == null)
            addStaticRoute(routePattern, (String) null, pathToStaticFolder);
        else {
            for (int i = 0; i < hostnamePatterns.size(); i++)
                addStaticRoute(routePattern, hostnamePatterns.getString(i), pathToStaticFolder);
        }
    }

    static void addStaticRoute(String routePattern, String hostnamePattern, String pathToStaticFolder) {
        Router router = VertxInstance.getHttpRouter();
        Route route = router.route(routePattern);
        if (hostnamePattern != null)
            route = route.virtualHost(hostnamePattern);
        boolean absolute = Path.of(pathToStaticFolder).isAbsolute();
        route.handler(StaticHandler.create(absolute ? FileSystemAccess.ROOT : FileSystemAccess.RELATIVE, pathToStaticFolder));
    }

    static void finaliseRouter() {
        Router router = VertxInstance.getHttpRouter();

        router.route().handler(BodyHandler.create());
    }
}
