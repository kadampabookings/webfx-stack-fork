package dev.webfx.stack.session.state.client.fx;

import dev.webfx.kit.util.properties.FXProperties;
import dev.webfx.kit.util.properties.Unregisterable;
import dev.webfx.platform.console.Console;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;

/**
 * @author Bruno Salmon
 */
public final class FXConnected {

    private final static BooleanProperty connectedProperty = FXProperties.newBooleanProperty(connected -> {
        Console.log("FXConnected = " + connected);
        FXConnectionLost.refreshValue();
        FXReconnected.refreshValue();
    });

    public static ReadOnlyBooleanProperty connectedProperty() {
        return connectedProperty;
    }

    public static boolean isConnected() {
        return connectedProperty.get();
    }

    static void setConnected(boolean connected) {
        connectedProperty.set(connected);
    }

    public static Unregisterable runOnEachConnected(Runnable runnable) {
        return FXProperties.runNowAndOnPropertyChange(connected -> {
            if (connected) // Only on false -> true transition
                runnable.run();
        }, connectedProperty());
    }

    static { // All FXClass in this package should call FXInit.init() in their static initializer
        FXInit.init(); // See FXInit comments to understand why
    }

}
