/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.mycat.calcite.rules;

import io.mycat.HintTools;
import io.mycat.calcite.MycatConvention;
import io.mycat.calcite.MycatConverterRule;
import io.mycat.calcite.MycatRules;
import io.mycat.calcite.physical.MycatSortMergeJoin;
import io.mycat.calcite.physical.MycatSortMergeSemiJoin;
import org.apache.calcite.adapter.enumerable.EnumerableMergeJoin;
import org.apache.calcite.linq4j.Ord;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelFieldCollation;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinInfo;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.hint.RelHint;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.tools.RelBuilderFactory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * copy 2020-7-18
 */
public class MycatMergeJoinRule extends MycatConverterRule {

    public static final MycatMergeJoinRule INSTANCE = new MycatMergeJoinRule(MycatConvention.INSTANCE, RelFactories.LOGICAL_BUILDER);

    public MycatMergeJoinRule(MycatConvention out, RelBuilderFactory relBuilderFactory) {
        super(Join.class, (j) -> true, MycatRules.IN_CONVENTION, out, relBuilderFactory, "MycatMergeJoinRule");
    }

    @Override
    public RelNode convert(RelNode rel) {
        final Join join = (Join) rel;
        return convert(join);
    }
    public RelNode convert(Join join) {
        RelHint lastJoinHint = HintTools.getLastJoinHint(join.getHints());
        if (lastJoinHint != null) {
            switch (lastJoinHint.hintName.toLowerCase()) {
                case "use_hash_join":
                case "use_bka_join":
                case "use_nl_join":
                  return null;
                case "use_merge_join":
                default:
            }
        }
        return tryMycatSortMergeJoin((LogicalJoin) join);
    }

    @Nullable
    private MycatSortMergeJoin tryMycatSortMergeJoin(LogicalJoin rel) {
        LogicalJoin join = rel;
        final JoinInfo info = join.analyzeCondition();
        if (!EnumerableMergeJoin.isMergeJoinSupported(join.getJoinType())) {
            // EnumerableMergeJoin only supports certain join types.
            return null;
        }
        if (info.pairs().size() == 0) {
            // EnumerableMergeJoin CAN support cartesian join, but disable it for now.
            return null;
        }
        final List<RelNode> newInputs = new ArrayList<>();
        final List<RelCollation> collations = new ArrayList<>();
        int offset = 0;
        for (Ord<RelNode> ord : Ord.zip(join.getInputs())) {
            RelTraitSet traits = ord.e.getTraitSet()
                    .replace(MycatConvention.INSTANCE);
            if (!info.pairs().isEmpty()) {
                final List<RelFieldCollation> fieldCollations = new ArrayList<>();
                for (int key : info.keys().get(ord.i)) {
                    fieldCollations.add(
                            new RelFieldCollation(key, RelFieldCollation.Direction.ASCENDING,
                                    RelFieldCollation.NullDirection.LAST));
                }
                final RelCollation collation = RelCollations.of(fieldCollations);
                collations.add(RelCollations.shift(collation, offset));
                traits = traits.replace(collation);
            }
            newInputs.add(convert(ord.e, traits));
            offset += ord.e.getRowType().getFieldCount();
        }
        final RelNode left = newInputs.get(0);
        final RelNode right = newInputs.get(1);
        final RelOptCluster cluster = join.getCluster();
        RelNode newRel;

        RelTraitSet traitSet = join.getTraitSet()
                .replace(MycatConvention.INSTANCE);
        if (!collations.isEmpty()) {
            traitSet = traitSet.replace(collations);
        }
        // Re-arrange condition: first the equi-join elements, then the non-equi-join ones (if any);
        // this is not strictly necessary but it will be useful to avoid spurious errors in the
        // unit tests when verifying the plan.
        final RexBuilder rexBuilder = join.getCluster().getRexBuilder();
        final RexNode equi = info.getEquiCondition(left, right, rexBuilder);
        final RexNode condition;
        if (info.isEqui()) {
            condition = equi;
        } else {
            final RexNode nonEqui = RexUtil.composeConjunction(rexBuilder, info.nonEquiConditions);
            condition = RexUtil.composeConjunction(rexBuilder, Arrays.asList(equi, nonEqui));
        }
        if (!join.isSemiJoin()) {
            return MycatSortMergeJoin.create(
                    traitSet,
                    convert(left, out),
                    convert(right, out),
                    condition,
                    join.getJoinType());
        } else {
            return MycatSortMergeSemiJoin.create(
                    traitSet,
                    convert(left, out),
                    convert(right, out),
                    condition,
                    join.getJoinType());
        }
    }
}
