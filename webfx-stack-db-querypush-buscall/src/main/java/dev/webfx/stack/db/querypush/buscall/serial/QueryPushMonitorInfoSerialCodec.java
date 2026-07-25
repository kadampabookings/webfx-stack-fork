package dev.webfx.stack.db.querypush.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import dev.webfx.stack.db.querypush.QueryPushMonitorInfo;
import dev.webfx.stack.db.querypush.QueryStreamMonitorInfo;

public final class QueryPushMonitorInfoSerialCodec extends SerialCodecBase<QueryPushMonitorInfo> {

    private static final String CODEC_ID = "QueryPushMonitorInfo";
    private static final String PUSH_CLIENTS_COUNT_KEY = "pushClientsCount";
    private static final String SUBSCRIBED_USERS_COUNT_KEY = "subscribedUsersCount";
    private static final String QUERY_STREAMS_KEY = "queryStreams";

    public QueryPushMonitorInfoSerialCodec() {
        super(QueryPushMonitorInfo.class, CODEC_ID);
    }

    @Override
    public void encode(QueryPushMonitorInfo arg, AstObject serial) {
        encodeInteger(serial, PUSH_CLIENTS_COUNT_KEY,     arg.getPushClientsCount());
        encodeInteger(serial, SUBSCRIBED_USERS_COUNT_KEY, arg.getSubscribedUsersCount());
        encodeArray(  serial, QUERY_STREAMS_KEY,          arg.getQueryStreams());
    }

    @Override
    public QueryPushMonitorInfo decode(ReadOnlyAstObject serial) {
        return new QueryPushMonitorInfo(
                decodeInteger(serial, PUSH_CLIENTS_COUNT_KEY, 0),
                decodeInteger(serial, SUBSCRIBED_USERS_COUNT_KEY, 0),
                decodeArray(  serial, QUERY_STREAMS_KEY, QueryStreamMonitorInfo.class)
        );
    }

}
