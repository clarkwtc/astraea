/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.astraea.common.balancer;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.astraea.common.Utils;
import org.astraea.common.admin.Admin;
import org.astraea.common.admin.ClusterBean;
import org.astraea.common.admin.ClusterInfo;
import org.astraea.common.admin.Replica;
import org.astraea.common.balancer.executor.RebalanceAdmin;
import org.astraea.common.balancer.executor.StraightPlanExecutor;
import org.astraea.common.balancer.generator.ShufflePlanGenerator;
import org.astraea.common.cost.ClusterCost;
import org.astraea.common.cost.HasClusterCost;
import org.astraea.common.cost.ReplicaLeaderCost;
import org.astraea.common.scenario.Scenario;
import org.astraea.it.RequireBrokerCluster;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BalancerTest extends RequireBrokerCluster {

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testLeaderCountRebalance(boolean greedy) {
    try (Admin admin = Admin.of(bootstrapServers())) {
      var topicName = Utils.randomString();
      var currentLeaders =
          (Supplier<Map<Integer, Long>>)
              () ->
                  admin.replicas().stream()
                      .filter(Replica::isLeader)
                      .map(replica -> replica.nodeInfo().id())
                      .collect(Collectors.groupingBy(x -> x, Collectors.counting()));
      var currentImbalanceFactor =
          (Supplier<Long>)
              () ->
                  currentLeaders.get().values().stream().mapToLong(x -> x).max().orElseThrow()
                      - currentLeaders.get().values().stream()
                          .mapToLong(x -> x)
                          .min()
                          .orElseThrow();
      Scenario.build(0.1)
          .topicName(topicName)
          .numberOfPartitions(100)
          .numberOfReplicas((short) 1)
          .binomialProbability(0.1)
          .build()
          .apply(admin);
      var imbalanceFactor0 = currentImbalanceFactor.get();
      Assertions.assertNotEquals(
          0, imbalanceFactor0, "This cluster is completely balanced in terms of leader count");

      var plan =
          Balancer.builder()
              .planGenerator(new ShufflePlanGenerator(1, 10))
              .clusterCost(new ReplicaLeaderCost())
              .limit(Duration.ofSeconds(10))
              .greedy(greedy)
              .build()
              .offer(admin.clusterInfo(), topic -> topic.equals(topicName), admin.brokerFolders())
              .orElseThrow();
      new StraightPlanExecutor().run(RebalanceAdmin.of(admin), plan.proposal().rebalancePlan());

      var imbalanceFactor1 = currentImbalanceFactor.get();
      Assertions.assertTrue(
          imbalanceFactor1 < imbalanceFactor0,
          "Leader count should be closer, original: "
              + imbalanceFactor0
              + ". now: "
              + imbalanceFactor1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testFilter(boolean greedy) {
    try (Admin admin = Admin.of(bootstrapServers())) {
      var theTopic = Utils.randomString();
      var topic1 = Utils.randomString();
      var topic2 = Utils.randomString();
      var topic3 = Utils.randomString();
      admin.creator().topic(theTopic).numberOfPartitions(10).create();
      admin.creator().topic(topic1).numberOfPartitions(10).create();
      admin.creator().topic(topic2).numberOfPartitions(10).create();
      admin.creator().topic(topic3).numberOfPartitions(10).create();
      Utils.sleep(Duration.ofSeconds(3));

      var randomScore =
          new HasClusterCost() {
            @Override
            public ClusterCost clusterCost(
                ClusterInfo<Replica> clusterInfo, ClusterBean clusterBean) {
              return () -> ThreadLocalRandom.current().nextDouble();
            }
          };

      var clusterInfo = admin.clusterInfo();
      var brokerFolders = admin.brokerFolders();
      var newAllocation =
          Balancer.builder()
              .planGenerator(new ShufflePlanGenerator(50, 100))
              .clusterCost(randomScore)
              .limit(500)
              .greedy(greedy)
              .build()
              .offer(clusterInfo, t -> t.equals(theTopic), brokerFolders)
              .get()
              .proposal()
              .rebalancePlan();

      var currentCluster = admin.clusterInfo();
      var newCluster = BalancerUtils.update(currentCluster, newAllocation);

      Assertions.assertTrue(
          ClusterInfo.diff(currentCluster, newCluster).stream()
              .allMatch(replica -> replica.topic().equals(theTopic)),
          "With filter, only specific topic has been balanced");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void testExecutionTime(boolean greedy) throws ExecutionException, InterruptedException {
    try (Admin admin = Admin.of(bootstrapServers())) {
      var theTopic = Utils.randomString();
      var topic1 = Utils.randomString();
      var topic2 = Utils.randomString();
      var topic3 = Utils.randomString();
      admin.creator().topic(theTopic).numberOfPartitions(10).create();
      admin.creator().topic(topic1).numberOfPartitions(10).create();
      admin.creator().topic(topic2).numberOfPartitions(10).create();
      admin.creator().topic(topic3).numberOfPartitions(10).create();
      Utils.sleep(Duration.ofSeconds(3));
      var future =
          CompletableFuture.supplyAsync(
              () ->
                  Balancer.builder()
                      .planGenerator(new ShufflePlanGenerator(50, 100))
                      .clusterCost((clusterInfo, bean) -> Math::random)
                      .limit(Duration.ofSeconds(3))
                      .greedy(greedy)
                      .build()
                      .offer(admin.clusterInfo(), admin.brokerFolders())
                      .get()
                      .proposal()
                      .rebalancePlan());
      Utils.sleep(Duration.ofMillis(1000));
      Assertions.assertFalse(future.isDone());
      Utils.sleep(Duration.ofMillis(2500));
      Assertions.assertTrue(future.isDone());
      Assertions.assertFalse(future.isCompletedExceptionally());
      Assertions.assertNotNull(future.get());
    }
  }
}