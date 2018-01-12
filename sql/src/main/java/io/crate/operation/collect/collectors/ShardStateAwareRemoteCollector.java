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

package io.crate.operation.collect.collectors;

import io.crate.data.RowConsumer;
import io.crate.exceptions.TableUnknownException;
import io.crate.metadata.TableIdent;
import io.crate.operation.collect.CrateCollector;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateObserver;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.cluster.routing.IllegalShardRoutingStateException;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRouting;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.index.IndexNotFoundException;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.shard.IndexShard;
import org.elasticsearch.index.shard.ShardNotFoundException;
import org.elasticsearch.indices.IndicesService;

import javax.annotation.Nullable;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * This component will collect data from a remote node when the provided shard is not available on the local node due to
 * a relocation.
 * In case the cluster state does not yet reflect the fact that the target shard was relocated (ie. it is closed locally
 * but it's routing status is still RELOCATING) it will wait for the shard to get started and then start collecting
 * (remote collecting in case the shard relocated to a different node or a local collection if the shard ended up back
 * on the local node)
 */
public class ShardStateAwareRemoteCollector implements CrateCollector {

    private static final Logger LOGGER = Loggers.getLogger(ShardStateAwareRemoteCollector.class);

    private final String localNode;
    private final RowConsumer consumer;
    private final Object killLock = new Object();

    private final ClusterService clusterService;
    private final int shardId;
    private final String indexName;
    private final IndicesService indicesService;
    private final Function<IndexShard, CrateCollector> localCollectorProvider;
    private final Function<String, RemoteCollector> remoteCollectorProvider;
    private final ExecutorService executorService;
    private final ThreadContext threadContext;
    private boolean collectorKilled = false;
    private CrateCollector collector;
    private TimeValue stateObserverWaitForChangeTimeout = new TimeValue(60000);

    public ShardStateAwareRemoteCollector(String indexName,
                                          int shardId,
                                          RowConsumer consumer,
                                          ClusterService clusterService,
                                          IndicesService indicesService,
                                          Function<IndexShard, CrateCollector> localCollectorProvider,
                                          Function<String, RemoteCollector> remoteCollectorProvider,
                                          ExecutorService executorService,
                                          ThreadContext threadContext) {
        this.indexName = indexName;
        this.shardId = shardId;
        this.consumer = consumer;
        this.clusterService = clusterService;
        this.localNode = clusterService.localNode().getId();
        this.indicesService = indicesService;
        this.localCollectorProvider = localCollectorProvider;
        this.remoteCollectorProvider = remoteCollectorProvider;
        this.executorService = executorService;
        this.threadContext = threadContext;
    }

    @Override
    public void doCollect() {
        ClusterState clusterState = clusterService.state();
        checkStateAndCollect(clusterState);
    }

    private void checkStateAndCollect(ClusterState clusterState) {
        IndexShardRoutingTable shardRoutings;
        try {
            shardRoutings = clusterState.routingTable().shardRoutingTable(indexName, shardId);
        } catch (ShardNotFoundException | IndexNotFoundException e) {
            consumer.accept(null, e);
            return;
        }

        // for update operations primaryShards must be used
        // (for others that wouldn't be the case, but at this point it is not easily visible which is the case)
        ShardRouting shardRouting = shardRoutings.primaryShard();
        String remoteNode = shardRouting.currentNodeId();
        if (localNode.equals(remoteNode) == false) {
            synchronized (killLock) {
                if (collectorKilled == false) {
                    collector = remoteCollectorProvider.apply(remoteNode);
                } else {
                    consumer.accept(null, new InterruptedException());
                }
            }
            // collect outside the synchronized block as the collectors handle kill signals that arrive while collecting
            if (collector != null) {
                collector.doCollect();
            }
        } else {
            if (shardRouting.started()) {
                // The shard is on the local node, so don't create a remote collector but a local one.
                // We might've arrived here as a result of the cluster state observer detecting the shard relocated, in
                // which case the ClusterState-Update thread is running this.
                // The creation of a ShardCollectorProvider accesses the clusterState, which leads to an assertionError
                // if accessed within a ClusterState-Update thread.

                executorService.submit(() -> {
                    IndexMetaData indexMetaData = clusterState.metaData().index(indexName);
                    if (indexMetaData != null) {
                        IndexService indexShards = indicesService.indexService(indexMetaData.getIndex());
                        if (indexShards == null) {
                            consumer.accept(null, new IndexNotFoundException(indexName));
                            return;
                        }
                        try {
                            synchronized (killLock) {
                                if (collectorKilled == false) {
                                    IndexShard indexShard = indexShards.getShard(shardId);
                                    collector = localCollectorProvider.apply(indexShard);
                                } else {
                                    consumer.accept(null, new InterruptedException());
                                    return;
                                }
                            }
                            // collect outside the synchronized block as the collectors handle kill signals that arrive
                            // while collecting
                            if (collector != null) {
                                collector.doCollect();
                            }
                        } catch (Exception e) {
                            consumer.accept(null, e);
                        }
                    } else {
                        consumer.accept(null, new TableUnknownException(TableIdent.fromIndexName(indexName)));
                    }
                });
            } else {
                LOGGER.warn("Unable to collect from a shard that's not started. Waiting for primary shard {} to start.",
                    shardRouting.shardId());

                ClusterStateObserver clusterStateObserver = new ClusterStateObserver(clusterState, clusterService,
                    stateObserverWaitForChangeTimeout, LOGGER, threadContext);
                clusterStateObserver.waitForNextChange(getClusterStateListener(shardRouting),
                    newState -> {
                        try {
                            return newState.routingTable().shardRoutingTable(indexName, shardId).primaryShard().started();
                        } catch (IndexNotFoundException | ShardNotFoundException e) {
                            // we'll handle this as a shard/index deleted change
                            return true;
                        }
                    });
            }
        }
    }

    private ClusterStateObserver.Listener getClusterStateListener(ShardRouting shardRouting) {
        return new ClusterStateObserver.Listener() {
            @Override
            public void onNewClusterState(ClusterState state) {
                checkStateAndCollect(state);
            }

            @Override
            public void onClusterServiceClose() {
                consumer.accept(null,
                    new InterruptedException("The cluster service was closed while waiting for shard " +
                                             shardRouting.shardId() + " to start"));
            }

            @Override
            public void onTimeout(TimeValue timeout) {
                consumer.accept(null, new IllegalShardRoutingStateException(shardRouting,
                    "Timed out waiting for shard to start"));
            }
        };
    }

    @Override
    public void kill(@Nullable Throwable throwable) {
        synchronized (killLock) {
            collectorKilled = true;
            if (collector != null) {
                collector.kill(throwable);
            }
        }
    }
}
