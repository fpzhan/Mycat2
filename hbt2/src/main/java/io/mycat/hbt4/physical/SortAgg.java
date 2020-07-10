package io.mycat.hbt4.physical;

import com.google.common.collect.ImmutableList;
import io.mycat.hbt4.Executor;
import io.mycat.hbt4.ExecutorImplementor;
import io.mycat.hbt4.ExplainWriter;
import io.mycat.hbt4.MycatRel;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.util.ImmutableBitSet;

import java.util.List;

public class SortAgg extends Aggregate implements MycatRel {

    public SortAgg(RelOptCluster cluster, RelTraitSet traitSet, RelNode input,
                   ImmutableBitSet groupSet,
                   List<ImmutableBitSet> groupSets,
                   List<AggregateCall> aggCalls) {
        super(cluster, traitSet, ImmutableList.of(), input, groupSet, groupSets, aggCalls);
    }

    @Override
    public ExplainWriter explain(ExplainWriter writer) {
        writer.name("SortAgg");

        ((MycatRel)input).explain(writer);
        return writer.ret();
    }

    @Override
    public Executor implement(ExecutorImplementor implementor) {
        return implementor.implement(this);
    }

    @Override
    public Aggregate copy(RelTraitSet traitSet, RelNode input, ImmutableBitSet groupSet, List<ImmutableBitSet> groupSets, List<AggregateCall> aggCalls) {
        return new SortAgg(getCluster(),traitSet,input,groupSet,groupSets,aggCalls);
    }
}