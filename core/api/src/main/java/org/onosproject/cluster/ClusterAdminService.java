/*
 * Copyright 2014-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.cluster;

import java.util.Set;

import org.onlab.packet.IpAddress;

/**
 * Service for administering the cluster node membership.
 */
public interface ClusterAdminService extends ClusterService {

    /**
     * Forms cluster configuration based on the specified set of node
     * information.&nbsp; This method resets and restarts the controller
     * instance.
     *
     * @param nodes    set of nodes that form the cluster
     * @deprecated since 1.14
     */
    @Deprecated
    void formCluster(Set<ControllerNode> nodes);

    /**
     * Forms cluster configuration based on the specified set of node
     * information.&nbsp; This method resets and restarts the controller
     * instance.
     *
     * @param nodes    set of nodes that form the cluster
     * @param partitionSize number of nodes to compose a partition
     * @deprecated since 1.14
     */
    @Deprecated
    void formCluster(Set<ControllerNode> nodes, int partitionSize);

    /**
     * Adds a new controller node to the cluster.
     *
     * @param nodeId  controller node identifier
     * @param ip      node IP listen address
     * @param tcpPort tcp listen port
     * @return newly added node
     * @deprecated since 1.14
     */
    @Deprecated
    ControllerNode addNode(NodeId nodeId, IpAddress ip, int tcpPort);

    /**
     * Removes the specified node from the cluster node list.
     *
     * @param nodeId controller node identifier
     * @deprecated since 1.14
     */
    @Deprecated
    void removeNode(NodeId nodeId);

    /**
     * Marks the current node as fully started or not.
     *
     * @param started true indicates all components have been started
     */
    void markFullyStarted(boolean started);

}
