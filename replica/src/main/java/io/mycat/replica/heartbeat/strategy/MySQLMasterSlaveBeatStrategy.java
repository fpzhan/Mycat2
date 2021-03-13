/**
 * Copyright (C) <2019>  <chen junwen>
 * <p>
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.replica.heartbeat.strategy;

import io.mycat.GlobalConst;
import io.mycat.replica.heartbeat.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static io.mycat.GlobalConst.MASTER_SLAVE_HEARTBEAT_MASTER_SQL;

/**
 * @author : zhangwy date Date : 2019年05月15日 21:34
 */
public class MySQLMasterSlaveBeatStrategy extends HeartBeatStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySQLMasterSlaveBeatStrategy.class);

    public List<String> getSqls() {
        boolean master = this.heartbeatFlow.getInstance().isMaster();
        if (master) {
            return Collections.singletonList("select 1");
        }
        return Collections.singletonList(GlobalConst.MASTER_SLAVE_HEARTBEAT_SQL);
    }

    public void process(List<List<Map<String, Object>>> resultList) {
        DatasourceStatus datasourceStatus = new DatasourceStatus();
        if (this.heartbeatFlow.getInstance().isMaster()) {
            if (resultList.size() > 0) {
                datasourceStatus.setDbSynStatus(DbSynEnum.DB_SYN_NORMAL);
                heartbeatFlow.setStatus(datasourceStatus, DatasourceEnum.OK_STATUS);
                return;
            }
        }
        if (!resultList.isEmpty()) {
            List<Map<String, Object>> result = resultList.get(0);
            if (!result.isEmpty()){
                Map<String, Object> resultResult = result.get(0);
                String Slave_IO_Running =
                        resultResult != null ? (String) resultResult.get("Slave_IO_Running") : null;
                String Slave_SQL_Running =
                        resultResult != null ? (String) resultResult.get("Slave_SQL_Running") : null;
                if (Slave_IO_Running != null
                        && Slave_IO_Running.equals(Slave_SQL_Running)
                        && Slave_SQL_Running.equals("Yes")) {
                    datasourceStatus.setDbSynStatus(DbSynEnum.DB_SYN_NORMAL);
                    Long Behind_Master = Long.parseLong(Objects.toString(resultResult.get("Seconds_Behind_Master")));
                    if (Behind_Master > heartbeatFlow.getSlaveThreshold()) {
                        datasourceStatus.setSlaveBehindMaster(true);
                        LOGGER.warn("found MySQL master/slave Replication delay !!! " +
                                " binlog sync time delay: " + Behind_Master + "s");
                    } else {
                        datasourceStatus.setSlaveBehindMaster(false);
                    }
                } else if (heartbeatFlow.instance().asSelectRead()) {
                    String Last_IO_Error =
                            resultResult != null ? (String) resultResult.get("Last_IO_Error") : null;
                    LOGGER.error("found MySQL master/slave Replication err !!! "
                            + Last_IO_Error);
                    datasourceStatus.setDbSynStatus(DbSynEnum.DB_SYN_ERROR);
                }
            }
        }
        heartbeatFlow.setStatus(datasourceStatus, DatasourceEnum.OK_STATUS);
    }

    @Override
    public void onException(Exception e) {
        heartbeatFlow.setStatus(DatasourceEnum.ERROR_STATUS);
    }

    public MySQLMasterSlaveBeatStrategy() {
    }

    public MySQLMasterSlaveBeatStrategy(HeartbeatFlow heartbeatFlow) {
        super(heartbeatFlow);
    }
}
