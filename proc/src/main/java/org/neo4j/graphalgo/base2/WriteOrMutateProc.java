/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.base2;

import org.neo4j.graphalgo.Algorithm;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.config.AlgoBaseConfig;
import org.neo4j.graphalgo.core.write.PropertyTranslator;
import org.neo4j.graphalgo.result.AbstractResultBuilder;

import java.util.stream.Stream;

public abstract class WriteOrMutateProc<
    ALGO extends Algorithm<ALGO, ALGO_RESULT>,
    ALGO_RESULT,
    PROC_RESULT,
    CONFIG extends AlgoBaseConfig> extends AlgoBaseProc<ALGO, ALGO_RESULT, CONFIG> {

    protected interface WriteOrMutate<A extends Algorithm<A, RESULT>, RESULT, CONFIG extends AlgoBaseConfig> {
        void apply(AbstractResultBuilder<?> writeBuilder, ComputationResult2<A, RESULT, CONFIG> computationResult);
    }

    protected abstract PropertyTranslator<ALGO_RESULT> nodePropertyTranslator(ComputationResult2<ALGO, ALGO_RESULT, CONFIG> computationResult);

    protected abstract AbstractResultBuilder<PROC_RESULT> resultBuilder(ComputationResult2<ALGO, ALGO_RESULT, CONFIG> computeResult);

    Stream<PROC_RESULT> writeOrMutate(
        ComputationResult2<ALGO, ALGO_RESULT, CONFIG> computeResult,
        WriteOrMutate<ALGO, ALGO_RESULT, CONFIG> op
    ) {
        CONFIG config = computeResult.config();
        AbstractResultBuilder<PROC_RESULT> builder = resultBuilder(computeResult)
            .withCreateMillis(computeResult.createMillis())
            .withComputeMillis(computeResult.computeMillis())
            .withConfig(config);

        if (computeResult.isGraphEmpty()) {
            return Stream.of(builder.build());
        } else {
            Graph graph = computeResult.graph();

            if (shouldWrite(config)) {
                op.apply(builder, computeResult);
                graph.releaseProperties();
            }

            return Stream.of(builder.build());
        }
    }
}