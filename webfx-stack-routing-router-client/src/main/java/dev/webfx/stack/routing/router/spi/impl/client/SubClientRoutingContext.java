package dev.webfx.stack.routing.router.spi.impl.client;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.stack.routing.router.RoutingContext;
import dev.webfx.stack.session.Session;

import java.util.Collection;

/**
 * @author Bruno Salmon
 */
final class SubClientRoutingContext extends ClientRoutingContextBase {

    final RoutingContext inner;
    private final String mountPoint;

    SubClientRoutingContext(String mountPoint, String path, Collection<ClientRoute> routes, RoutingContext inner) {
        this(mountPoint, path, routes, inner, false);
    }

    SubClientRoutingContext(String mountPoint, String path, Collection<ClientRoute> routes, RoutingContext inner, boolean redirected) {
        super(mountPoint, path, routes, null, redirected);
        this.inner = inner;
        if (mountPoint == null)
            this.mountPoint = null;
        else {
            // Removing the trailing slash or we won't match
            if (mountPoint.endsWith("/"))
                mountPoint = mountPoint.substring(0, mountPoint.length() - 1);
            String parentMountPoint = inner.mountPoint();
            this.mountPoint = parentMountPoint == null ? mountPoint : parentMountPoint + mountPoint;
        }
    }

    @Override
    public String mountPoint() {
        return mountPoint;
    }

    @Override
    public AstObject getParams() {
        return inner.getParams();
    }

    @Override
    public void next() {
        if (!super.iterateNext()) {
            // We didn't route request to anything so go to parent
            inner.next();
        }
    }

    @Override
    public void fail(int statusCode) {
        inner.fail(statusCode);
    }

    @Override
    public void fail(Throwable throwable) {
        inner.fail(throwable);
    }

    @Override
    public int statusCode() {
        return inner.statusCode();
    }

    @Override
    public boolean failed() {
        return inner.failed();
    }

    @Override
    public Throwable failure() {
        return inner.failure();
    }

    @Override
    public Session session() {
        return inner.session();
    }
}
