package io.mycat.router.function;

import com.alibaba.druid.sql.SQLUtils;
import io.mycat.DataNode;
import io.mycat.RangeVariable;
import io.mycat.router.CustomRuleFunction;
import io.mycat.router.ShardingTableHandler;

import java.text.MessageFormat;
import java.util.*;
import java.util.function.Function;

public class AutoFunction extends CustomRuleFunction {
    String name;
    int dbNum;
    int tableNum;
    Object dbMethod;
    Object tableMethod;
    private Set<String> dbKeys;
    private Set<String> tableKeys;
    Function<Map<String, Collection<RangeVariable>>, List<DataNode>> function;

    public AutoFunction(int dbNum,
                        int tableNum,
                        Object dbMethod,
                        Object tableMethod,
                        Set<String> dbKeys,
                        Set<String> tableKeys,
                        Function<Map<String, Collection<RangeVariable>>, List<DataNode>> function, String erUniqueName) {
        this.dbNum = dbNum;
        this.tableNum = tableNum;
        this.dbMethod = dbMethod;
        this.tableMethod = tableMethod;
        this.dbKeys = dbKeys;
        this.tableKeys = tableKeys;
        this.function = function;
        this.name = MessageFormat.format("dbNum:{0} tableNum:{1} dbMethod:{2} tableMethod:{3}",
                dbNum, tableNum, dbMethod, tableMethod);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<DataNode> calculate(Map<String, Collection<RangeVariable>> values) {
        return Objects.requireNonNull(function.apply(values));
    }

    @Override
    protected void init(ShardingTableHandler tableHandler, Map<String, Object> properties, Map<String, Object> ranges) {

    }

    @Override
    public boolean isShardingKey(String name) {
        name = SQLUtils.normalize(name);
        return dbKeys.contains(name) || tableKeys.contains(name);
    }

    @Override
    public boolean isShardingDbKey(String name) {
        name = SQLUtils.normalize(name);
        return dbKeys.contains(name);
    }

    @Override
    public boolean isShardingTableKey(String name) {
        name = SQLUtils.normalize(name);
        return tableKeys.contains(name);
    }

    @Override
    public boolean isSameDistribution(CustomRuleFunction customRuleFunction) {
        if (customRuleFunction == null) return false;
        if (AutoFunction.class.isAssignableFrom(customRuleFunction.getClass())) {
            AutoFunction ruleFunction = (AutoFunction) customRuleFunction;
            return Objects.equals(this.name, ruleFunction.name);
        }
        return false;
    }

    @Override
    public String getErUniqueID() {
        return name;
    }
}
