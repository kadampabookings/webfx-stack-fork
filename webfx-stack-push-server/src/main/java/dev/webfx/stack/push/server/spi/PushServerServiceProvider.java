package dev.webfx.stack.push.server.spi;

import dev.webfx.stack.com.bus.DeliveryOptions;
import dev.webfx.stack.push.server.UnresponsivePushClientListener;
import dev.webfx.platform.async.Future;
import dev.webfx.stack.com.bus.Bus;
import dev.webfx.stack.push.ClientPushBusAddressesSharedByBothClientAndServer;

/**
 * @author Bruno Salmon
 */
public interface PushServerServiceProvider {

    <T> Future<T> push(String clientServiceAddress, Object javaArgument, DeliveryOptions options, Bus bus, Object clientRunId);

    default Future<Void> pushPing(DeliveryOptions options, Bus bus, Object clientRunId) {
        return push(ClientPushBusAddressesSharedByBothClientAndServer.PUSH_PING_CLIENT_LISTENER_SERVICE_ADDRESS, "Push ping to client " + clientRunId, options, bus, clientRunId);
    }

    void clientIsLive(Object clientRunId);

    /**
     * Returns the number of clients currently registered on this push server (i.e. clients the
     * server has pushed to at least once — in practice every connected client, since the server
     * pushes authorizations to each of them on connection). For monitoring purposes.
     */
    default int getPushClientsCount() {
        return 0;
    }

    void addUnresponsivePushClientListener(UnresponsivePushClientListener listener);

    void removeUnresponsivePushClientListener(UnresponsivePushClientListener listener);

}
