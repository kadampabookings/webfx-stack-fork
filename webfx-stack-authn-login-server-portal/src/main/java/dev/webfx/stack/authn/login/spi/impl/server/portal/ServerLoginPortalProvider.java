package dev.webfx.stack.authn.login.spi.impl.server.portal;

import dev.webfx.platform.async.Future;
import dev.webfx.platform.service.MultipleServiceProviders;
import dev.webfx.stack.authn.login.LoginUiContext;
import dev.webfx.stack.authn.login.spi.LoginServiceProvider;
import dev.webfx.stack.authn.login.spi.impl.server.gateway.ServerLoginGateway;

import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * @author Bruno Salmon
 */
public class ServerLoginPortalProvider implements LoginServiceProvider {

    private static List<ServerLoginGateway> getGateways() {
        return MultipleServiceProviders.getProviders(ServerLoginGateway.class, () -> ServiceLoader.load(ServerLoginGateway.class));
    }

    public ServerLoginPortalProvider() { // Called first time on server start through LoginService.getProvider() call in GetLoginUiInputMethodEndpoint.
        // We instantiate the gateways (such as Google, Facebook, etc...) and call their boot() method, which they will
        // probably use to register their callback route (must be done as soon as possible, i.e. on server start).
        for (ServerLoginGateway gateway : getGateways())
            gateway.boot();
    }

    @Override
    public Future<?> getLoginUiInput(LoginUiContext loginUiContext) {
        Object gatewayId = loginUiContext.getGatewayId();
        for (ServerLoginGateway gateway : getGateways()) {
            if (Objects.equals(gateway.getGatewayId(), gatewayId))
                return gateway.getLoginUiInput(loginUiContext.getGatewayContext());
        }
        return Future.failedFuture("No server login gateway found with id='" + gatewayId + "'");
    }

}
