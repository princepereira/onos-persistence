/*
 * Copyright 2016-present Open Networking Foundation
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
package org.onosproject.openstacknetworking.impl;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.packet.ARP;
import org.onlab.packet.EthType;
import org.onlab.packet.Ethernet;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cfg.ConfigProperty;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.packet.DefaultOutboundPacket;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketService;
import org.onosproject.openstacknetworking.api.Constants;
import org.onosproject.openstacknetworking.api.InstancePort;
import org.onosproject.openstacknetworking.api.InstancePortEvent;
import org.onosproject.openstacknetworking.api.InstancePortListener;
import org.onosproject.openstacknetworking.api.InstancePortService;
import org.onosproject.openstacknetworking.api.OpenstackFlowRuleService;
import org.onosproject.openstacknetworking.api.OpenstackNetworkAdminService;
import org.onosproject.openstacknetworking.api.OpenstackNetworkService;
import org.onosproject.openstacknetworking.api.OpenstackRouterEvent;
import org.onosproject.openstacknetworking.api.OpenstackRouterListener;
import org.onosproject.openstacknetworking.api.OpenstackRouterService;
import org.onosproject.openstacknode.api.OpenstackNode;
import org.onosproject.openstacknode.api.OpenstackNodeEvent;
import org.onosproject.openstacknode.api.OpenstackNodeListener;
import org.onosproject.openstacknode.api.OpenstackNodeService;
import org.openstack4j.model.network.ExternalGateway;
import org.openstack4j.model.network.IP;
import org.openstack4j.model.network.NetFloatingIP;
import org.openstack4j.model.network.Router;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.openstacknetworking.api.Constants.ARP_BROADCAST_MODE;
import static org.onosproject.openstacknetworking.api.Constants.ARP_PROXY_MODE;
import static org.onosproject.openstacknetworking.api.Constants.DEFAULT_ARP_MODE_STR;
import static org.onosproject.openstacknetworking.api.Constants.DEFAULT_GATEWAY_MAC_STR;
import static org.onosproject.openstacknetworking.api.Constants.GW_COMMON_TABLE;
import static org.onosproject.openstacknetworking.api.Constants.OPENSTACK_NETWORKING_APP_ID;
import static org.onosproject.openstacknetworking.api.Constants.PRIORITY_ARP_CONTROL_RULE;
import static org.onosproject.openstacknetworking.api.Constants.PRIORITY_ARP_GATEWAY_RULE;
import static org.onosproject.openstacknetworking.util.OpenstackNetworkingUtil.associatedFloatingIp;
import static org.onosproject.openstacknetworking.util.OpenstackNetworkingUtil.getGwByComputeDevId;
import static org.onosproject.openstacknetworking.util.OpenstackNetworkingUtil.getGwByInstancePort;
import static org.onosproject.openstacknetworking.util.OpenstackNetworkingUtil.getPropertyValue;
import static org.onosproject.openstacknetworking.util.OpenstackNetworkingUtil.isAssociatedWithVM;
import static org.onosproject.openstacknetworking.util.OpenstackNetworkingUtil.swapStaleLocation;
import static org.onosproject.openstacknode.api.OpenstackNode.NodeType.GATEWAY;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Handle ARP requests from gateway nodes.
 */
@Component(immediate = true)
public class OpenstackRoutingArpHandler {

    private final Logger log = getLogger(getClass());

    private static final String DEVICE_OWNER_ROUTER_GW = "network:router_gateway";
    private static final String DEVICE_OWNER_FLOATING_IP = "network:floatingip";
    private static final String ARP_MODE = "arpMode";

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OpenstackNetworkAdminService osNetworkAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OpenstackRouterService osRouterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OpenstackNodeService osNodeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected InstancePortService instancePortService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OpenstackFlowRuleService osFlowRuleService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OpenstackNetworkService osNetworkService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService configService;

    @Property(name = ARP_MODE, value = DEFAULT_ARP_MODE_STR,
            label = "ARP processing mode, broadcast | proxy (default)")
    protected String arpMode = DEFAULT_ARP_MODE_STR;

    protected String gatewayMac = DEFAULT_GATEWAY_MAC_STR;

    private final OpenstackRouterListener osRouterListener = new InternalRouterEventListener();
    private final OpenstackNodeListener osNodeListener = new InternalNodeEventListener();
    private final InstancePortListener instPortListener = new InternalInstancePortListener();

    private ApplicationId appId;
    private NodeId localNodeId;

    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler", log));

    private final PacketProcessor packetProcessor = new InternalPacketProcessor();

    @Activate
    protected void activate() {
        appId = coreService.registerApplication(OPENSTACK_NETWORKING_APP_ID);
        configService.registerProperties(getClass());
        localNodeId = clusterService.getLocalNode().id();
        osRouterService.addListener(osRouterListener);
        osNodeService.addListener(osNodeListener);
        instancePortService.addListener(instPortListener);
        leadershipService.runForLeadership(appId.name());
        packetService.addProcessor(packetProcessor, PacketProcessor.director(1));
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        packetService.removeProcessor(packetProcessor);
        instancePortService.removeListener(instPortListener);
        osRouterService.removeListener(osRouterListener);
        osNodeService.removeListener(osNodeListener);
        instancePortService.removeListener(instPortListener);
        leadershipService.withdraw(appId.name());
        eventExecutor.shutdown();
        configService.unregisterProperties(getClass(), false);
        log.info("Stopped");
    }

    @Modified
    void modified(ComponentContext context) {
        log.info("Modified");
    }

    private String getArpMode() {
        Set<ConfigProperty> properties = configService.getProperties(this.getClass().getName());
        return getPropertyValue(properties, ARP_MODE);
    }

    private void processArpPacket(PacketContext context, Ethernet ethernet) {
        ARP arp = (ARP) ethernet.getPayload();

        if (arp.getOpCode() == ARP.OP_REQUEST && ARP_PROXY_MODE.equals(getArpMode())) {
            if (log.isTraceEnabled()) {
                log.trace("ARP request received from {} for {}",
                        Ip4Address.valueOf(arp.getSenderProtocolAddress()).toString(),
                        Ip4Address.valueOf(arp.getTargetProtocolAddress()).toString());
            }

            IpAddress targetIp = Ip4Address.valueOf(arp.getTargetProtocolAddress());

            MacAddress targetMac = null;

            NetFloatingIP floatingIP = osRouterService.floatingIps().stream()
                    .filter(ip -> ip.getFloatingIpAddress().equals(targetIp.toString()))
                    .findAny().orElse(null);

            //In case target ip is for associated floating ip, sets target mac to vm's.
            if (floatingIP != null && floatingIP.getPortId() != null) {
                InstancePort instPort = instancePortService.instancePort(floatingIP.getPortId());
                if (instPort == null) {
                    log.trace("Unknown target ARP request for {}, ignore it", targetIp);
                    return;
                } else {
                    targetMac = instPort.macAddress();
                }

                OpenstackNode gw = getGwByInstancePort(osNodeService.completeNodes(GATEWAY), instPort);

                if (gw == null) {
                    return;
                }

                // if the ARP packet_in received from non-relevant GWs, we simply ignore it
                if (!Objects.equals(gw.intgBridge(), context.inPacket().receivedFrom().deviceId())) {
                    return;
                }
            }

            if (isExternalGatewaySourceIp(targetIp)) {
                targetMac = Constants.DEFAULT_GATEWAY_MAC;
            }

            if (targetMac == null) {
                log.trace("Unknown target ARP request for {}, ignore it", targetIp);
                return;
            }

            Ethernet ethReply = ARP.buildArpReply(targetIp.getIp4Address(),
                    targetMac, ethernet);

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .setOutput(context.inPacket().receivedFrom().port()).build();

            packetService.emit(new DefaultOutboundPacket(
                    context.inPacket().receivedFrom().deviceId(),
                    treatment,
                    ByteBuffer.wrap(ethReply.serialize())));

            context.block();
        }

        if (arp.getOpCode() == ARP.OP_REPLY) {
            ConnectPoint cp = context.inPacket().receivedFrom();
            PortNumber receivedPortNum = cp.port();
            IpAddress spa = Ip4Address.valueOf(arp.getSenderProtocolAddress());
            MacAddress sha = MacAddress.valueOf(arp.getSenderHardwareAddress());

            log.debug("ARP reply ip: {}, mac: {}", spa, sha);

            try {

                Set<String> extRouterIps = osNetworkService.externalPeerRouters().
                        stream().map(r -> r.ipAddress().toString()).collect(Collectors.toSet());

                // if SPA is NOT contained in existing external router IP set, we ignore it
                if (!extRouterIps.contains(spa.toString())) {
                    return;
                }

                OpenstackNode node = osNodeService.node(cp.deviceId());

                if (node == null) {
                    return;
                }

                // we only handles the ARP-Reply message received by gateway node
                if (node.type() != GATEWAY) {
                    return;
                }

                if (receivedPortNum.equals(node.uplinkPortNum())) {
                    osNetworkAdminService.updateExternalPeerRouterMac(spa, sha);
                }
            } catch (Exception e) {
                log.error("Exception occurred because of {}", e);
            }
        }
    }

    private class InternalPacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {
            if (context.isHandled()) {
                return;
            }

            Set<DeviceId> gateways = osNodeService.completeNodes(GATEWAY)
                    .stream().map(OpenstackNode::intgBridge)
                    .collect(Collectors.toSet());

            if (!gateways.contains(context.inPacket().receivedFrom().deviceId())) {
                // return if the packet is not from gateway nodes
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethernet = pkt.parsed();
            if (ethernet != null &&
                    ethernet.getEtherType() == Ethernet.TYPE_ARP) {
                eventExecutor.execute(() -> processArpPacket(context, ethernet));
            }
        }
    }

    private boolean isExternalGatewaySourceIp(IpAddress targetIp) {
        return osNetworkAdminService.ports().stream()
                .filter(osPort -> Objects.equals(osPort.getDeviceOwner(),
                        DEVICE_OWNER_ROUTER_GW))
                .flatMap(osPort -> osPort.getFixedIps().stream())
                .anyMatch(ip -> IpAddress.valueOf(ip.getIpAddress()).equals(targetIp));
    }

    /**
     * Installs static ARP rules used in ARP BROAD_CAST mode.
     *
     * @param gateway gateway node
     * @param install flow rule installation flag
     */
    private void setFloatingIpArpRuleForGateway(OpenstackNode gateway, boolean install) {
        if (ARP_BROADCAST_MODE.equals(getArpMode())) {

            Set<OpenstackNode> completedGws = osNodeService.completeNodes(GATEWAY);
            Set<OpenstackNode> finalGws = Sets.newConcurrentHashSet();
            finalGws.addAll(ImmutableSet.copyOf(completedGws));

            if (install) {
                if (completedGws.contains(gateway)) {
                    if (completedGws.size() > 1) {
                        finalGws.remove(gateway);
                        osRouterService.floatingIps().forEach(fip -> {
                            if (fip.getPortId() != null) {
                                setFloatingIpArpRule(fip, fip.getPortId(), finalGws, false);
                                finalGws.add(gateway);
                            }
                        });
                    }
                    osRouterService.floatingIps().forEach(fip -> {
                        if (fip.getPortId() != null) {
                            setFloatingIpArpRule(fip, fip.getPortId(), finalGws, true);
                        }
                    });
                } else {
                    log.warn("Detected node should be included in completed gateway set");
                }
            } else {
                if (!completedGws.contains(gateway)) {
                    finalGws.add(gateway);
                    osRouterService.floatingIps().forEach(fip -> {
                        if (fip.getPortId() != null) {
                            setFloatingIpArpRule(fip, fip.getPortId(), finalGws, false);
                        }
                    });
                    finalGws.remove(gateway);
                    if (completedGws.size() >= 1) {
                        osRouterService.floatingIps().forEach(fip -> {
                            if (fip.getPortId() != null) {
                                setFloatingIpArpRule(fip, fip.getPortId(), finalGws, true);
                            }
                        });
                    }
                } else {
                    log.warn("Detected node should NOT be included in completed gateway set");
                }
            }
        }
    }

    /**
     * Installs/uninstalls ARP flow rules to the corresponding gateway by
     * looking for compute node's device ID.
     *
     * @param fip       floating IP
     * @param port      instance port
     * @param gateways  a collection of gateways
     * @param install   install flag
     */
    private void setFloatingIpArpRuleWithPortEvent(NetFloatingIP fip,
                                                   InstancePort port,
                                                   Set<OpenstackNode> gateways,
                                                   boolean install) {
        if (ARP_BROADCAST_MODE.equals(getArpMode())) {

            OpenstackNode gw = getGwByInstancePort(gateways, port);

            if (gw == null) {
                return;
            }

            String macString = osNetworkAdminService.port(fip.getPortId()).getMacAddress();

            setArpRule(fip, MacAddress.valueOf(macString), gw, install);
        }
    }

    /**
     * Installs static ARP rules used in ARP BROAD_CAST mode.
     * Note that, those rules will be only matched ARP_REQUEST packets,
     * used for telling gateway node the mapped MAC address of requested IP,
     * without the helps from controller.
     *
     * @param fip       floating IP address
     * @param portId    port identifier
     * @param gateways  a set of gateway nodes
     * @param install   flow rule installation flag
     */
    private synchronized void setFloatingIpArpRule(NetFloatingIP fip, String portId,
                                                   Set<OpenstackNode> gateways,
                                                   boolean install) {
        if (ARP_BROADCAST_MODE.equals(getArpMode())) {

            if (fip == null) {
                log.warn("Failed to set ARP broadcast rule for floating IP");
                return;
            }

            if (portId == null || fip.getPortId() == null) {
                log.trace("Unknown target ARP request for {}, ignore it",
                        fip.getFloatingIpAddress());
                return;
            }

            InstancePort instPort = instancePortService.instancePort(portId);
            MacAddress targetMac = instPort.macAddress();

            OpenstackNode gw = getGwByInstancePort(gateways, instPort);

            if (gw == null) {
                return;
            }

            setArpRule(fip, targetMac, gw, install);
        }
    }

    private void setArpRule(NetFloatingIP fip, MacAddress targetMac,
                            OpenstackNode gateway, boolean install) {
        TrafficSelector selector = DefaultTrafficSelector.builder()
                .matchEthType(EthType.EtherType.ARP.ethType().toShort())
                .matchArpOp(ARP.OP_REQUEST)
                .matchArpTpa(Ip4Address.valueOf(fip.getFloatingIpAddress()))
                .build();

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setArpOp(ARP.OP_REPLY)
                .setArpSha(targetMac)
                .setArpSpa(Ip4Address.valueOf(fip.getFloatingIpAddress()))
                .setOutput(PortNumber.IN_PORT)
                .build();

        osFlowRuleService.setRule(
                appId,
                gateway.intgBridge(),
                selector,
                treatment,
                PRIORITY_ARP_GATEWAY_RULE,
                GW_COMMON_TABLE,
                install
        );

        if (install) {
            log.info("Install ARP Rule for Floating IP {}",
                    fip.getFloatingIpAddress());
        } else {
            log.info("Uninstall ARP Rule for Floating IP {}",
                    fip.getFloatingIpAddress());
        }
    }

    private void setFakeGatewayArpRule(Router router, boolean install) {
        setFakeGatewayArpRule(router.getExternalGatewayInfo(), install);
    }

    private Set<IP> getExternalGatewaySnatIps(ExternalGateway extGw) {
        return osNetworkAdminService.ports().stream()
                .filter(port ->
                        Objects.equals(port.getNetworkId(), extGw.getNetworkId()))
                .filter(port ->
                        Objects.equals(port.getDeviceOwner(), DEVICE_OWNER_ROUTER_GW))
                .flatMap(port -> port.getFixedIps().stream())
                .collect(Collectors.toSet());
    }

    private void setFakeGatewayArpRule(ExternalGateway extGw, boolean install) {
        if (ARP_BROADCAST_MODE.equals(getArpMode())) {

            if (extGw == null) {
                return;
            }

            Set<IP> ips = getExternalGatewaySnatIps(extGw);

            ips.forEach(ip -> {
                TrafficSelector selector = DefaultTrafficSelector.builder()
                        .matchEthType(EthType.EtherType.ARP.ethType().toShort())
                        .matchArpOp(ARP.OP_REQUEST)
                        .matchArpTpa(Ip4Address.valueOf(ip.getIpAddress()))
                        .build();

                TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                        .setArpOp(ARP.OP_REPLY)
                        .setArpSha(MacAddress.valueOf(gatewayMac))
                        .setArpSpa(Ip4Address.valueOf(ip.getIpAddress()))
                        .setOutput(PortNumber.IN_PORT)
                        .build();

                osNodeService.completeNodes(GATEWAY).forEach(n ->
                        osFlowRuleService.setRule(
                                appId,
                                n.intgBridge(),
                                selector,
                                treatment,
                                PRIORITY_ARP_GATEWAY_RULE,
                                GW_COMMON_TABLE,
                                install
                        )
                );

                if (install) {
                    log.info("Install ARP Rule for Gateway Snat {}", ip.getIpAddress());
                } else {
                    log.info("Uninstall ARP Rule for Gateway Snat {}", ip.getIpAddress());
                }
            });
        }
    }

    /**
     * An internal router event listener, intended to install/uninstall
     * ARP rules for forwarding packets created from floating IPs.
     */
    private class InternalRouterEventListener implements OpenstackRouterListener {

        @Override
        public boolean isRelevant(OpenstackRouterEvent event) {
            // do not allow to proceed without leadership
            NodeId leader = leadershipService.getLeader(appId.name());
            return Objects.equals(localNodeId, leader);
        }

        @Override
        public void event(OpenstackRouterEvent event) {

            Set<OpenstackNode> completedGws = osNodeService.completeNodes(GATEWAY);

            switch (event.type()) {
                case OPENSTACK_ROUTER_CREATED:
                    eventExecutor.execute(() ->
                        // add a router with external gateway
                        setFakeGatewayArpRule(event.subject(), true)
                    );
                    break;
                case OPENSTACK_ROUTER_REMOVED:
                    eventExecutor.execute(() ->
                        // remove a router with external gateway
                        setFakeGatewayArpRule(event.subject(), false)
                    );
                    break;
                case OPENSTACK_ROUTER_GATEWAY_ADDED:
                    eventExecutor.execute(() ->
                        // add a gateway manually after adding a router
                        setFakeGatewayArpRule(event.externalGateway(), true)
                    );
                    break;
                case OPENSTACK_ROUTER_GATEWAY_REMOVED:
                    eventExecutor.execute(() ->
                        // remove a gateway from an existing router
                        setFakeGatewayArpRule(event.externalGateway(), false)
                    );
                    break;
                case OPENSTACK_FLOATING_IP_ASSOCIATED:
                    if (getValidPortId(event) != null) {
                        eventExecutor.execute(() -> {
                            // associate a floating IP with an existing VM
                            setFloatingIpArpRule(event.floatingIp(), getValidPortId(event),
                                    completedGws, true);
                        });
                    }
                    break;
                case OPENSTACK_FLOATING_IP_DISASSOCIATED:
                    if (getValidPortId(event) != null) {
                        eventExecutor.execute(() -> {
                            // disassociate a floating IP with an existing VM
                            setFloatingIpArpRule(event.floatingIp(), getValidPortId(event),
                                    completedGws, false);
                        });
                    }
                    break;
                case OPENSTACK_FLOATING_IP_CREATED:
                    // during floating IP creation, if the floating IP is
                    // associated with any port of VM, then we will set
                    // floating IP related ARP rules to gateway node
                    if (getValidPortId(event) != null) {
                        eventExecutor.execute(() -> {
                            // associate a floating IP with an existing VM
                            setFloatingIpArpRule(event.floatingIp(), getValidPortId(event),
                                    completedGws, true);
                        });
                    }
                    break;
                case OPENSTACK_FLOATING_IP_REMOVED:
                    // during floating IP deletion, if the floating IP is
                    // still associated with any port of VM, then we will
                    // remove floating IP related ARP rules from gateway node
                    if (getValidPortId(event) != null) {
                        eventExecutor.execute(() -> {
                            // associate a floating IP with an existing VM
                            setFloatingIpArpRule(event.floatingIp(), getValidPortId(event),
                                    completedGws, false);
                        });
                    }
                    break;
                default:
                    // do nothing for the other events
                    break;
            }
        }

        private String getValidPortId(OpenstackRouterEvent event) {
            NetFloatingIP osFip = event.floatingIp();
            String portId = osFip.getPortId();

            if (Strings.isNullOrEmpty(portId)) {
                portId = event.portId();
            }

            if (portId != null && instancePortService.instancePort(portId) != null) {
                return portId;
            }

            return null;
        }
    }

    private class InternalInstancePortListener implements InstancePortListener {

        @Override
        public boolean isRelevant(InstancePortEvent event) {
            // do not allow to proceed without leadership
            NodeId leader = leadershipService.getLeader(appId.name());
            return Objects.equals(localNodeId, leader);
        }

        @Override
        public void event(InstancePortEvent event) {
            InstancePort instPort = event.subject();

            Set<NetFloatingIP> ips = osRouterService.floatingIps();
            NetFloatingIP fip = associatedFloatingIp(instPort, ips);
            Set<OpenstackNode> gateways = osNodeService.completeNodes(GATEWAY);

            switch (event.type()) {
                case OPENSTACK_INSTANCE_PORT_DETECTED:

                    osRouterService.floatingIps().stream()
                            .filter(f -> f.getPortId() != null)
                            .filter(f -> f.getPortId().equals(instPort.portId()))
                            .forEach(f -> setFloatingIpArpRule(f, instPort.portId(), gateways, true));

                    break;
                case OPENSTACK_INSTANCE_MIGRATION_STARTED:

                    if (gateways.size() == 1) {
                        return;
                    }

                    if (fip != null && isAssociatedWithVM(osNetworkService, fip)) {
                        eventExecutor.execute(() ->
                            setFloatingIpArpRuleWithPortEvent(fip, event.subject(),
                                    gateways, true)
                        );
                    }

                    break;
                case OPENSTACK_INSTANCE_MIGRATION_ENDED:

                    InstancePort revisedInstPort = swapStaleLocation(event.subject());

                    if (gateways.size() == 1) {
                        return;
                    }

                    if (fip != null && isAssociatedWithVM(osNetworkService, fip)) {
                        DeviceId newDeviceId = event.subject().deviceId();
                        DeviceId oldDeviceId = revisedInstPort.deviceId();

                        OpenstackNode oldGw = getGwByComputeDevId(gateways, oldDeviceId);
                        OpenstackNode newGw = getGwByComputeDevId(gateways, newDeviceId);

                        if (oldGw != null && oldGw.equals(newGw)) {
                            return;
                        }

                        eventExecutor.execute(() ->
                                setFloatingIpArpRuleWithPortEvent(fip,
                                        revisedInstPort, gateways, false));
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private class InternalNodeEventListener implements OpenstackNodeListener {

        @Override
        public boolean isRelevant(OpenstackNodeEvent event) {
            // do not allow to proceed without leadership
            NodeId leader = leadershipService.getLeader(appId.name());
            return Objects.equals(localNodeId, leader) && event.subject().type() == GATEWAY;
        }

        @Override
        public void event(OpenstackNodeEvent event) {
            OpenstackNode osNode = event.subject();
            switch (event.type()) {
                case OPENSTACK_NODE_COMPLETE:
                    setDefaultArpRule(osNode, true);
                    setFloatingIpArpRuleForGateway(osNode, true);
                    break;
                case OPENSTACK_NODE_INCOMPLETE:
                    setDefaultArpRule(osNode, false);
                    setFloatingIpArpRuleForGateway(osNode, false);
                    break;
                default:
                    break;
            }
        }

        private void setDefaultArpRule(OpenstackNode osNode, boolean install) {
            switch (getArpMode()) {
                case ARP_PROXY_MODE:
                    setDefaultArpRuleForProxyMode(osNode, install);
                    break;
                case ARP_BROADCAST_MODE:
                    setDefaultArpRuleForBroadcastMode(osNode, install);
                    break;
                default:
                    log.warn("Invalid ARP mode {}. Please use either " +
                            "broadcast or proxy mode.", getArpMode());
                    break;
            }
        }

        private void setDefaultArpRuleForProxyMode(OpenstackNode osNode, boolean install) {
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(EthType.EtherType.ARP.ethType().toShort())
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .punt()
                    .build();

            osFlowRuleService.setRule(
                    appId,
                    osNode.intgBridge(),
                    selector,
                    treatment,
                    PRIORITY_ARP_CONTROL_RULE,
                    GW_COMMON_TABLE,
                    install
            );
        }

        private void setDefaultArpRuleForBroadcastMode(OpenstackNode osNode, boolean install) {
            // we only match ARP_REPLY in gateway node, because controller
            // somehow need to process ARP_REPLY which is issued from
            // external router...
            TrafficSelector selector = DefaultTrafficSelector.builder()
                    .matchEthType(EthType.EtherType.ARP.ethType().toShort())
                    .matchArpOp(ARP.OP_REPLY)
                    .build();

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .punt()
                    .build();

            osFlowRuleService.setRule(
                    appId,
                    osNode.intgBridge(),
                    selector,
                    treatment,
                    PRIORITY_ARP_CONTROL_RULE,
                    GW_COMMON_TABLE,
                    install
            );

            osRouterService.routers().stream()
                    .filter(router -> router.getExternalGatewayInfo() != null)
                    .forEach(router -> setFakeGatewayArpRule(router, install));
        }
    }
}
