package dev.webfx.stack.webpush.buscall;

/**
 * Bus address constants for the Web Push BO operations. Keep in sync with the
 * client-side address constants (in {@code kbs3-react/shared/src/bus/constants.ts}).
 *
 * @author Bruno Salmon
 */
public final class WebPushBusAddress {

    /** Send a Web Push notification to subscribers matching a host-defined target. */
    public static final String SEND_PUSH_NOTIFICATION_ADDRESS = "service/push/sendNotification";

    private WebPushBusAddress() {}
}
