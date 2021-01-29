package io.mycat.sqlhandler.ddl;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.statement.SQLDropDatabaseStatement;
import io.mycat.*;
import io.mycat.config.MycatRouterConfigOps;
import io.mycat.datasource.jdbc.datasource.DefaultConnection;
import io.mycat.datasource.jdbc.datasource.JdbcConnectionManager;
import io.mycat.MetadataManager;
import io.mycat.sqlhandler.AbstractSQLHandler;
import io.mycat.sqlhandler.ConfigUpdater;
import io.mycat.sqlhandler.SQLRequest;
import io.mycat.Response;
import io.vertx.core.impl.future.PromiseInternal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DropDatabaseSQLHandler extends AbstractSQLHandler<SQLDropDatabaseStatement> {
    private static final Logger LOGGER = LoggerFactory.getLogger(DropDatabaseSQLHandler.class);

    @Override
    protected PromiseInternal<Void> onExecute(SQLRequest<SQLDropDatabaseStatement> request, MycatDataContext dataContext, Response response) throws Exception {
        SQLDropDatabaseStatement dropDatabaseStatement = request.getAst();
        String schemaName = SQLUtils.normalize(dropDatabaseStatement.getDatabaseName());
        try (MycatRouterConfigOps ops = ConfigUpdater.getOps()) {
            ops.dropSchema(schemaName);
            ops.commit();
            onPhysics(schemaName);
            return response.sendOk();
        }
    }
    protected void onPhysics(String name) {
        MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);
        JdbcConnectionManager jdbcConnectionManager = MetaClusterCurrent.wrapper(JdbcConnectionManager.class);
        try (DefaultConnection connection = jdbcConnectionManager.getConnection(metadataManager.getPrototype())) {
            connection.executeUpdate(String.format(
                    "DROP DATABASE IF EXISTS %s;",
                    name),false);
        }catch (Throwable t){
            LOGGER.warn("",t);
        }
    }
}
