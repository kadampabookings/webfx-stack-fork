package dev.webfx.stack.db.querypush.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import dev.webfx.stack.db.querypush.ActiveDbQueryInfo;
import dev.webfx.stack.db.querypush.DatabaseHealthMonitorInfo;

public final class DatabaseHealthMonitorInfoSerialCodec extends SerialCodecBase<DatabaseHealthMonitorInfo> {

    private static final String CODEC_ID = "DatabaseHealthMonitorInfo";
    private static final String MAX_CONNECTIONS_KEY = "maxConnections";
    private static final String TOTAL_CONNECTIONS_KEY = "totalConnections";
    private static final String ACTIVE_CONNECTIONS_KEY = "activeConnections";
    private static final String IDLE_CONNECTIONS_KEY = "idleConnections";
    private static final String ACTIVE_QUERIES_KEY = "activeQueries";

    public DatabaseHealthMonitorInfoSerialCodec() {
        super(DatabaseHealthMonitorInfo.class, CODEC_ID);
    }

    @Override
    public void encode(DatabaseHealthMonitorInfo arg, AstObject serial) {
        encodeInteger(serial, MAX_CONNECTIONS_KEY,    arg.getMaxConnections());
        encodeInteger(serial, TOTAL_CONNECTIONS_KEY,  arg.getTotalConnections());
        encodeInteger(serial, ACTIVE_CONNECTIONS_KEY, arg.getActiveConnections());
        encodeInteger(serial, IDLE_CONNECTIONS_KEY,   arg.getIdleConnections());
        encodeArray(  serial, ACTIVE_QUERIES_KEY,     arg.getActiveQueries());
    }

    @Override
    public DatabaseHealthMonitorInfo decode(ReadOnlyAstObject serial) {
        return new DatabaseHealthMonitorInfo(
            decodeInteger(serial, MAX_CONNECTIONS_KEY, -1),
            decodeInteger(serial, TOTAL_CONNECTIONS_KEY, 0),
            decodeInteger(serial, ACTIVE_CONNECTIONS_KEY, 0),
            decodeInteger(serial, IDLE_CONNECTIONS_KEY, 0),
            decodeArray(  serial, ACTIVE_QUERIES_KEY, ActiveDbQueryInfo.class));
    }
}
