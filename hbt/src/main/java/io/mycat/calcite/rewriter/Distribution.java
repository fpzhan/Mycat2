/**
 * Copyright (C) <2021>  <chen junwen>
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
package io.mycat.calcite.rewriter;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.mycat.*;
import io.mycat.calcite.table.GlobalTable;
import io.mycat.calcite.table.NormalTable;
import io.mycat.calcite.table.ShardingTable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.calcite.rex.RexNode;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@EqualsAndHashCode
@Getter
public class Distribution {

    List<ShardingTable> shardingTables;
    List<GlobalTable> globalTables;
    List<NormalTable> normalTables;

    public static Distribution of(ShardingTable shardingTable) {
        return new Distribution(ImmutableList.of(shardingTable),
                ImmutableList.of(), ImmutableList.of());
    }

    public static Distribution of(GlobalTable globalTable) {
        return new Distribution(ImmutableList.of(),
                ImmutableList.of(globalTable), ImmutableList.of());
    }

    public static Distribution of(NormalTable normalTable) {
        return new Distribution(ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(normalTable));
    }

    public Distribution(List<ShardingTable> shardingTables,
                        List<GlobalTable> globalTables,
                        List<NormalTable> normalTables) {
        this.shardingTables = shardingTables;
        this.globalTables = globalTables;
        this.normalTables = normalTables;
    }

    public static Distribution of(List<String> unquineName) {
        MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);
        List<ShardingTable> shardingTables = new ArrayList<>();
        List<GlobalTable> globalTables = new ArrayList<>();
        List<NormalTable> normalTables = new ArrayList<>();
        Distribution distribution = new Distribution(shardingTables, globalTables, normalTables);

        unquineName.stream().map(i->{
            String[] split = i.split("\\.");
            return SchemaInfo.of(split[0],split[1]);})
                .map(n -> metadataManager.getTable(n.getTargetSchema(), n.getTargetTable())).forEach(t -> {
            switch (t.getType()) {
                case SHARDING:
                    shardingTables.add((ShardingTable) t);
                    break;
                case GLOBAL:
                    globalTables.add((GlobalTable) t);
                    break;
                case NORMAL:
                    normalTables.add((NormalTable) t);
                    break;
                case CUSTOM:
                    throw new UnsupportedOperationException();
            }
        });

        return distribution;
    }

    public static Distribution fromJson(List<String> list) {
        return Distribution.of( list);
    }

    public List<String> toNameList(){
        ArrayList<TableHandler> tableHandlers = new ArrayList<>();
        tableHandlers.addAll(shardingTables);
        tableHandlers.addAll(globalTables);
        tableHandlers.addAll(normalTables);
        return tableHandlers.stream().map(i->(i.getSchemaName()+"."+i.getTableName())).sorted().collect(Collectors.toList());
    }


    public Type type() {
        if (!globalTables.isEmpty() && shardingTables.isEmpty() && normalTables.isEmpty()) {
            return Type.BroadCast;
        }
        if (globalTables.isEmpty() && shardingTables.isEmpty() && !normalTables.isEmpty()) {
            return Type.PHY;
        }
        if (!globalTables.isEmpty() && shardingTables.isEmpty() && !normalTables.isEmpty()) {
            return Type.PHY;
        }
        if (globalTables.isEmpty() && !shardingTables.isEmpty() && normalTables.isEmpty()) {
            return Type.Sharding;
        }
        return Type.Sharding;
    }

    public Optional<Distribution> join(Distribution arg) {
        switch (arg.type()) {
            case PHY:
                switch (this.type()) {
                    case PHY:
                        if (this.normalTables.get(0).getDataNode().getTargetName()
                                .equals(arg.normalTables.get(0).getDataNode().getTargetName())) {
                            return Optional.of(
                                    new Distribution(this.shardingTables,
                                            this.globalTables,
                                            merge(this.normalTables, arg.normalTables)));
                        }
                        return Optional.empty();
                    case BroadCast:
                        return Optional.of(
                                new Distribution(merge(this.shardingTables, arg.shardingTables),
                                        merge(this.globalTables, arg.globalTables),
                                        merge(this.normalTables, arg.normalTables)));
                    case Sharding:
                        return Optional.empty();
                    default:
                        throw new IllegalStateException("Unexpected value: " + this.type());
                }
            case BroadCast:
                return Optional.of(
                        new Distribution(merge(this.shardingTables, arg.shardingTables),
                                merge(this.globalTables, arg.globalTables),
                                merge(this.normalTables, arg.normalTables)));
            case Sharding:
                switch (this.type()) {
                    case PHY:
                        return Optional.empty();
                    case BroadCast:
                        return Optional.of(
                                new Distribution(merge(this.shardingTables, arg.shardingTables),
                                        merge(this.globalTables, arg.globalTables),
                                        merge(this.normalTables, arg.normalTables)));
                    case Sharding:
                        if (this.shardingTables.get(0).getShardingFuntion()
                                .isSameDistribution(
                                        (arg.shardingTables.get(0).getShardingFuntion()))) {
                            return Optional.of(
                                    new Distribution(merge(this.shardingTables, arg.shardingTables),
                                            merge(this.globalTables, arg.globalTables),
                                            merge(this.normalTables, arg.normalTables)));
                        }
                        return Optional.empty();
                    default:
                        throw new IllegalStateException("Unexpected value: " + this.type());
                }
            default:
                throw new IllegalStateException("Unexpected value: " + arg.type());
        }
    }


    @NotNull
    private static List merge(List left, List right) {
        ImmutableList.Builder<Object> builder = ImmutableList.builder();
        builder.addAll(left);
        builder.addAll(right);
        return builder.build();
    }

    public Stream<Map<String, DataNode>> getDataNodes() {
        return getDataNodes(table -> table.dataNodes());
    }

    public Iterable<DataNode> getDataNodesAsSingleTableUpdate(IndexCondition conditions, List<Object> readOnlyParameters) {
        if (normalTables.size() == 1) {
            return Collections.singletonList(normalTables.get(0).getDataNode());
        }
        if (globalTables.size() == 1) {
            return ImmutableList.copyOf(globalTables.get(0).getGlobalDataNode());
        }
        if (shardingTables.size() == 1) {
            return IndexCondition.getObject(shardingTables.get(0).getShardingFuntion(), conditions, readOnlyParameters);
        }
        throw new UnsupportedOperationException();
    }

    public Stream<Map<String, DataNode>> getDataNodes(Function<ShardingTable, List<DataNode>> function) {
        switch (this.type()) {
            case BroadCast:
            case PHY: {
                Map<String, DataNode> builder = new HashMap<>();
                for (NormalTable normalTable : this.normalTables) {
                    builder.put(normalTable.getUniqueName(), normalTable.getDataNode());
                }
                for (GlobalTable globalTable : this.globalTables) {
                    builder.put(globalTable.getUniqueName(), globalTable.getDataNode());
                }
                return Stream.of(builder);
            }
            case Sharding: {
                ImmutableMap.Builder<String, DataNode> globalbuilder = ImmutableMap.builder();
                for (GlobalTable globalTable : this.globalTables) {
                    globalbuilder.put(globalTable.getUniqueName(), globalTable.getDataNode());
                }
                ImmutableMap<String, DataNode> globalMap = globalbuilder.build();
                ShardingTable shardingTable = this.shardingTables.get(0);
                String primaryTableUniqueName = shardingTable.getLogicTable().getUniqueName();
                List<DataNode> primaryTableFilterDataNodes = function.apply(shardingTable);
//                Map<String, List<DataNode>> collect = this.shardingTables.stream()
//                        .collect(Collectors.toMap(k -> k.getUniqueName(), v -> v.getShardingFuntion().calculate(Collections.emptyMap())));
                MetadataManager metadataManager = MetaClusterCurrent.wrapper(MetadataManager.class);
                List<ShardingTable> shardingTables = metadataManager.getErTableGroup().getOrDefault(shardingTable.getShardingFuntion().getErUniqueID(), Collections.emptyList());
                Map<String, List<DataNode>> collect = shardingTables.stream().collect(Collectors.toMap(k -> k.getUniqueName(), v -> v.dataNodes()));
                List<Integer> mappingIndex = new ArrayList<>();
                List<String> allDataNodeUniqueNames = collect.get(primaryTableUniqueName).stream().sequential().map(i -> i.getUniqueName()).collect(Collectors.toList());
                {

                    for (DataNode filterDataNode : primaryTableFilterDataNodes) {
                        int index = 0;
                        for (String allDataNodeUniqueName : allDataNodeUniqueNames) {
                            if (allDataNodeUniqueName.equals(filterDataNode.getUniqueName())) {
                                mappingIndex.add(index);
                                break;
                            }
                            index++;
                        }

                    }
                }
                TreeMap<Integer, Map<String, DataNode>> res = new TreeMap<>();
                {
                    for (Map.Entry<String, List<DataNode>> e : collect.entrySet()) {
                        String key = e.getKey();
                        List<DataNode> dataNodes = e.getValue();
                        for (Integer integer : mappingIndex) {
                            Map<String, DataNode> stringDataNodeMap = res.computeIfAbsent(integer, integer1 -> new HashMap<>());
                            stringDataNodeMap.put(key, dataNodes.get(integer));
                            stringDataNodeMap.putAll(globalMap);
                        }
                    }
                }
                return res.values().stream();
            }
            default:
                throw new IllegalStateException("Unexpected value: " + this.type());
        }
    }

    public Set<String> getTargets() {
        return getDataNodes().flatMap(i -> i.values().stream()).map(i -> i.getTargetName()).collect(Collectors.toSet());
    }


    public static enum Type {
        BroadCast,
        Sharding,
        PHY

    }

    @Override
    public String toString() {
        String builder = "Distribution{" +
                innerToString() +
                "}";
        return builder;
    }

    public String innerToString() {

        List<String> each = new ArrayList<>();

        if (!normalTables.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            builder.append("normalTables=")
                    .append(normalTables
                            .stream()
                            .map(i ->
                                    i.getSchemaName() + "." + i.getTableName()).sorted().collect(Collectors.joining(","))

                    );
            each.add(builder.toString());
        }

        if (!shardingTables.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            builder.append("shardingTables=")
                    .append(shardingTables
                            .stream()
                            .map(i -> i.getSchemaName() + "." + i.getTableName()).sorted().collect(Collectors.joining(","))
                    );
            each.add(builder.toString());
        }
        if (!globalTables.isEmpty()) {
            StringBuilder builder = new StringBuilder();
            builder.append("globalTables=")
                    .append(globalTables
                            .stream()
                            .map(i ->
                                    i.getSchemaName() + "." + i.getTableName()).sorted().collect(Collectors.joining(","))
                    );
            each.add(builder.toString());
        }

        return String.join(",", each);
    }
}