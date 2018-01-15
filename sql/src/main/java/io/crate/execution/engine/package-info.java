/*
 * Licensed to Crate under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.  Crate licenses this file
 * to you under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.  See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial
 * agreement.
 */

/**
 * <p>
 *     The execution.engine package contains the components used to execute queries described by {@link io.crate.execution.dsl}.
 *     <br />
 *     When referring to "queries" or "query execution" within this package it refers to queries which involve a data collection step.
 *     That includes DQL and certain DML queries, but excludes DDL statements.
 * </p>
 *
 * <p>
 *     Query execution in CrateDB involves several components. This should give you a high level overview:
 * </p>
 * <ul>
 *     <li>The entry point on the handler node: {@link io.crate.execution.engine.ExecutionPhasesTask}</li>
 *     <li>The entry point on remote nodes: {@link io.crate.execution.engine.transport.TransportJobAction}</li>
 *     <li>Both of the above use {@link io.crate.execution.engine.setup.ContextPreparer}
 *     to setup {@link io.crate.jobs.JobExecutionContext}, which contains the
 *     {@link io.crate.jobs.ExecutionSubContext}s which model the operations that should be executed</li>
 *     <li>
 *         There are different kind of contexts, which model different operations. Most often used are:
 *         <ul>
 *             <li>{@link io.crate.operation.collect.JobCollectContext} which is used to collect data;
 *             E.g. from Lucene or in-memory structures. Created based on a {@link io.crate.execution.dsl.phases.CollectPhase}</li>
 *             <li>{@link io.crate.jobs.PageDownstreamContext} which is used to process data from an upstream executionPhase.
 *             Created based on a {@link io.crate.execution.dsl.phases.MergePhase}</li>
 *         </ul>
 *     </li>
 * </ul>
 *
 * <p>
 *     Within those contexts a pull based iterator model is used to process the data/rows.
 *     See {@link io.crate.data} for a more detailed description.
 *
 *     Note though, that *triggering* this pull based iteration is done *push* based.
 *
 *     An example:
 *     <pre>
 *
 *       - We've a chain/pipeline of {@link io.crate.data.BatchIterator}s
 *       - We've a {@link io.crate.data.RowConsumer}
 *
 *       (both setup within the ContextPreparer and part of a ExecutionSubContext)
 *
 *       - The "trigger" is that {@link io.crate.data.RowConsumer#accept(io.crate.data.BatchIterator, java.lang.Throwable)} is called (push)
 *       - The RowConsumer consumes the BatchIterator (pull)
 *     </pre>
 * </p>
 *
 *
 *
 * <p>
 *     The following is an example of how a distributed group by is executed. First the DSL:
 * </p>
 * <pre>
 *     Handler: n1
 *     Nodes involved: [n1, n2]
 *
 *          n1                        n2
 *     CollectPhase         CollectPhase
 *             \             /  (modulo based distribution based on the group by key)
 *              \___________/   (aggregation to "partial")
 *             /            \
 *            /              \
 *     MergePhase           MergePhase         // reducer
 *          |     _________/    (aggregation to "final")
 *          |    /
 *     MergePhase                              // localMerge
 *           (concat results; apply final limit)
 * </pre>
 *
 * <p>
 *     This involves the following components:
 * </p>
 *
 * <pre>
 *     - On the handler the {@link io.crate.execution.engine.ExecutionPhasesTask} is used to:
 *       - Create a local {@link io.crate.jobs.JobExecutionContext}, this contains:
 *          - A {@link io.crate.operation.collect.JobCollectContext}
 *          - Two {@link io.crate.jobs.PageDownstreamContext}, one for each MergePhase
 *       - Fire a {@link io.crate.execution.engine.transport.JobRequest} to n2
 *
 *     - {@link io.crate.execution.engine.transport.TransportJobAction} will receive this request and based on it create
 *        - A {@link io.crate.operation.collect.JobCollectContext}
 *        - One {@link io.crate.jobs.PageDownstreamContext}
 *
 *     - {@link io.crate.jobs.JobExecutionContext#start()} triggers the start method on the sub-contexts which will:
 *
 *       - Initiate the collect operation
 *       (This means a RowConsumer, in this case a {@link io.crate.execution.engine.transport.DistributingConsumer}, will receive a {@link io.crate.data.BatchIterator};
 *       consume it's data and forward it's result.
 *
 *       - The data is received by {@link io.crate.execution.engine.transport.TransportDistributedResultAction}
 *       which looks up a {@link io.crate.jobs.PageDownstreamContext} to process it.
 *
 *       This fills up a {@link io.crate.data.BatchIterator} with the data that was received and also invokes a {@link io.crate.data.RowConsumer}.
 *       Again a {@link io.crate.execution.engine.transport.DistributingConsumer}
 *
 *       - The data is again received by a {@link io.crate.jobs.PageDownstreamContext} (MergePhase on the handler).
 *         The data is again being made available to a {@link io.crate.data.RowConsumer} via a {@link io.crate.data.BatchIterator}.
 *         The RowConsumer here is a {@link io.crate.action.sql.RowConsumerToResultReceiver}, which forwards the result to a client.
 *
 *
 *       Visually, this may look as follows:
 *
 *       ## n1
 *
 *              (src: LuceneBatchIterator)
 *       CollectingBatchIterator (does the grouping)
 *          |
 *       DistributingConsumer
 *          |
 *          .
 *          . Network I/O
 *          |
 *       TransportDistributedResultAction (also receives results from n2)
 *          |
 *       PageDownstreamContext
 *          |
 *          |   (src: BatchPagingIterator)
 *       CollectingBatchIterator
 *          |
 *       DistributingConsumer
 *          |
 *          .
 *          . Network I/O
 *          |
 *       TransportDistributedResultAction  (also receives results from n2)
 *          |
 *       PageDownstreamContext
 *          |
 *          |   (src: BatchPagingIterator)
 *       LimitingBatchIterator
 *          |
 *       RowConsumerToResultReceiver
 * </pre>
 *
 * Note that this is a just one specific example. The execution can look very different depending on the DSL.
 * But this should give an overview / idea, how a query execution can look like.
 */
package io.crate.execution.engine;
