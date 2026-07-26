package dev.webfx.stack.db.querypush.buscall.serial;

import dev.webfx.platform.ast.AstObject;
import dev.webfx.platform.ast.ReadOnlyAstObject;
import dev.webfx.stack.com.serial.spi.impl.SerialCodecBase;
import dev.webfx.stack.db.querypush.SqlExecutionMonitorInfo;

public final class SqlExecutionMonitorInfoSerialCodec extends SerialCodecBase<SqlExecutionMonitorInfo> {

    private static final String CODEC_ID = "SqlExecutionMonitorInfo";
    private static final String READ_KEY = "read";
    private static final String WRITE_KEY = "write";

    public SqlExecutionMonitorInfoSerialCodec() {
        super(SqlExecutionMonitorInfo.class, CODEC_ID);
    }

    @Override
    public void encode(SqlExecutionMonitorInfo arg, AstObject serial) {
        encodeObject(serial, READ_KEY,  arg.getRead());
        encodeObject(serial, WRITE_KEY, arg.getWrite());
    }

    @Override
    public SqlExecutionMonitorInfo decode(ReadOnlyAstObject serial) {
        return new SqlExecutionMonitorInfo(
            decodeObject(serial, READ_KEY),
            decodeObject(serial, WRITE_KEY));
    }
}
