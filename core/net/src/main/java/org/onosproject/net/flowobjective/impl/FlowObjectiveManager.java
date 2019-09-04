/*
 * Copyright 2015-present Open Networking Foundation
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
package org.onosproject.net.flowobjective.impl;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.osgi.DefaultServiceDirectory;
import org.onlab.osgi.ServiceDirectory;
import org.onlab.util.ItemNotFoundException;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cluster.ClusterService;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.behaviour.NextGroup;
import org.onosproject.net.behaviour.Pipeliner;
import org.onosproject.net.behaviour.PipelinerContext;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.driver.DriverEvent;
import org.onosproject.net.driver.DriverHandler;
import org.onosproject.net.driver.DriverListener;
import org.onosproject.net.driver.DriverService;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flowobjective.FilteringObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.FlowObjectiveStore;
import org.onosproject.net.flowobjective.FlowObjectiveStoreDelegate;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.NextObjective;
import org.onosproject.net.flowobjective.Objective;
import org.onosproject.net.flowobjective.Objective.Operation;
import org.onosproject.net.flowobjective.ObjectiveError;
import org.onosproject.net.flowobjective.ObjectiveEvent;
import org.onosproject.net.flowobjective.ObjectiveEvent.Type;
import org.onosproject.net.group.GroupService;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Strings.isNullOrEmpty;
import static java.util.concurrent.Executors.newFixedThreadPool;
import static java.util.concurrent.Executors.newSingleThreadScheduledExecutor;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.net.AnnotationKeys.DRIVER;
import static org.onosproject.security.AppGuard.checkPermission;
import static org.onosproject.security.AppPermission.Type.FLOWRULE_WRITE;

/**
 * Provides implementation of the flow objective programming service.
 */
@Component(enabled = false)
@Service
public class FlowObjectiveManager implements FlowObjectiveService {

    private static final int INSTALL_RETRY_ATTEMPTS = 5;
    private static final long INSTALL_RETRY_INTERVAL = 1000; // ms

    private static final String WORKER_PATTERN = "objective-installer-%d";
    private static final String GROUP_THREAD_NAME = "onos/objective-installer";
    private static final String NUM_THREAD = "numThreads";

    private final Logger log = LoggerFactory.getLogger(getClass());

    private static final int DEFAULT_NUM_THREADS = 4;
    @Property(name = NUM_THREAD,
             intValue = DEFAULT_NUM_THREADS,
             label = "Number of worker threads")
    private int numThreads = DEFAULT_NUM_THREADS;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DriverService driverService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    // Note: The following dependencies are added on behalf of the pipeline
    // driver behaviours to assure these services are available for their
    // initialization.
    @SuppressWarnings("unused")
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowRuleService flowRuleService;

    @SuppressWarnings("unused")
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected GroupService groupService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveStore flowObjectiveStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService cfgService;

    final FlowObjectiveStoreDelegate delegate = new InternalStoreDelegate();

    private final Map<DeviceId, DriverHandler> driverHandlers = Maps.newConcurrentMap();
    protected final Map<DeviceId, Pipeliner> pipeliners = Maps.newConcurrentMap();

    private final PipelinerContext context = new InnerPipelineContext();
    private final DeviceListener deviceListener = new InnerDeviceListener();
    private final DriverListener driverListener = new InnerDriverListener();

    private ServiceDirectory serviceDirectory = new DefaultServiceDirectory();

    // local stores for queuing fwd and next objectives that are waiting for an
    // associated next objective execution to complete. The signal for completed
    // execution comes from a pipeline driver, in this or another controller
    // instance, via the DistributedFlowObjectiveStore.
    // TODO Making these cache and timeout the entries
    final Map<Integer, Set<PendingFlowObjective>> pendingForwards = Maps.newConcurrentMap();
    final Map<Integer, List<PendingFlowObjective>> pendingNexts = Maps.newConcurrentMap();

    // local store to track which nextObjectives were sent to which device
    // for debugging purposes
    private Map<Integer, DeviceId> nextToDevice = Maps.newConcurrentMap();

    ExecutorService executorService;
    protected ExecutorService devEventExecutor;

    @Activate
    protected void activate() {
        cfgService.registerProperties(getClass());
        executorService = newFixedThreadPool(numThreads,
                                             groupedThreads(GROUP_THREAD_NAME, WORKER_PATTERN, log));
        devEventExecutor = newSingleThreadScheduledExecutor(
                                       groupedThreads("onos/flowobj-dev-events", "events-%d", log));
        flowObjectiveStore.setDelegate(delegate);
        deviceService.addListener(deviceListener);
        driverService.addListener(driverListener);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        flowObjectiveStore.unsetDelegate(delegate);
        deviceService.removeListener(deviceListener);
        driverService.removeListener(driverListener);
        executorService.shutdown();
        devEventExecutor.shutdownNow();
        devEventExecutor = null;
        pipeliners.clear();
        driverHandlers.clear();
        nextToDevice.clear();
        log.info("Stopped");
    }

    @Modified
    protected void modified(ComponentContext context) {
        String propertyValue =
                Tools.get(context.getProperties(), NUM_THREAD);
        int newNumThreads = isNullOrEmpty(propertyValue) ? numThreads : Integer.parseInt(propertyValue);

        if (newNumThreads != numThreads && newNumThreads > 0) {
            numThreads = newNumThreads;
            ExecutorService oldWorkerExecutor = executorService;
            executorService = newFixedThreadPool(numThreads,
                                                 groupedThreads(GROUP_THREAD_NAME, WORKER_PATTERN, log));
            if (oldWorkerExecutor != null) {
                oldWorkerExecutor.shutdown();
            }
            log.info("Reconfigured number of worker threads to {}", numThreads);
        }
    }

    /**
     * Task that passes the flow objective down to the driver. The task will
     * make a few attempts to find the appropriate driver, then eventually give
     * up and report an error if no suitable driver could be found.
     */
    class ObjectiveInstaller implements Runnable {
        final DeviceId deviceId;
        final Objective objective;

        private final int numAttempts;

        ObjectiveInstaller(DeviceId deviceId, Objective objective) {
            this(deviceId, objective, 1);
        }

        ObjectiveInstaller(DeviceId deviceId, Objective objective, int attemps) {
            this.deviceId = checkNotNull(deviceId);
            this.objective = checkNotNull(objective);
            this.numAttempts = attemps;
        }

        @Override
        public void run() {
            try {
                Pipeliner pipeliner = getDevicePipeliner(deviceId);

                if (pipeliner != null) {
                    if (objective instanceof NextObjective) {
                        nextToDevice.put(objective.id(), deviceId);
                        pipeliner.next((NextObjective) objective);
                    } else if (objective instanceof ForwardingObjective) {
                        pipeliner.forward((ForwardingObjective) objective);
                    } else {
                        pipeliner.filter((FilteringObjective) objective);
                    }
                    //Attempts to check if pipeliner is null for retry attempts
                } else if (numAttempts < INSTALL_RETRY_ATTEMPTS) {
                    Thread.sleep(INSTALL_RETRY_INTERVAL);
                    executorService.execute(new ObjectiveInstaller(deviceId, objective, numAttempts + 1));
                } else {
                    // Otherwise we've tried a few times and failed, report an
                    // error back to the user.
                    objective.context().ifPresent(
                            c -> c.onError(objective, ObjectiveError.NOPIPELINER));
                }
                //Exception thrown
            } catch (Exception e) {
                log.warn("Exception while installing flow objective", e);
            }
        }
    }

    @Override
    public void filter(DeviceId deviceId, FilteringObjective filteringObjective) {
        checkPermission(FLOWRULE_WRITE);
        executorService.execute(new ObjectiveInstaller(deviceId, filteringObjective));
    }

    @Override
    public void forward(DeviceId deviceId, ForwardingObjective forwardingObjective) {
        checkPermission(FLOWRULE_WRITE);
        if (forwardingObjective.nextId() == null ||
                flowObjectiveStore.getNextGroup(forwardingObjective.nextId()) != null ||
                !queueFwdObjective(deviceId, forwardingObjective)) {
            // fast path
            executorService.execute(new ObjectiveInstaller(deviceId, forwardingObjective));
        }
    }

    @Override
    public void next(DeviceId deviceId, NextObjective nextObjective) {
        checkPermission(FLOWRULE_WRITE);
        if (nextObjective.op() == Operation.ADD ||
                nextObjective.op() == Operation.VERIFY ||
                flowObjectiveStore.getNextGroup(nextObjective.id()) != null ||
                !queueNextObjective(deviceId, nextObjective)) {
            // either group exists or we are trying to create it - let it through
            executorService.execute(new ObjectiveInstaller(deviceId, nextObjective));
        }
    }

    @Override
    public int allocateNextId() {
        checkPermission(FLOWRULE_WRITE);
        return flowObjectiveStore.allocateNextId();
    }

    @Override
    public void initPolicy(String policy) {
    }

    boolean queueFwdObjective(DeviceId deviceId, ForwardingObjective fwd) {
        boolean queued = false;
        synchronized (pendingForwards) {
            // double check the flow objective store, because this block could run
            // after a notification arrives
            if (flowObjectiveStore.getNextGroup(fwd.nextId()) == null) {
                pendingForwards.compute(fwd.nextId(), (id, pending) -> {
                    PendingFlowObjective pendfo = new PendingFlowObjective(deviceId, fwd);
                    if (pending == null) {
                        return Sets.newLinkedHashSet(ImmutableSet.of(pendfo));
                    } else {
                        pending.add(pendfo);
                        return pending;
                    }
                });
                queued = true;
            }
        }
        if (queued) {
            log.debug("Queued forwarding objective {} for nextId {} meant for device {}",
                      fwd.id(), fwd.nextId(), deviceId);
        }
        return queued;
    }

    boolean queueNextObjective(DeviceId deviceId, NextObjective next) {
        // we need to hold off on other operations till we get notified that the
        // initial group creation has succeeded
        boolean queued = false;
        synchronized (pendingNexts) {
            // double check the flow objective store, because this block could run
            // after a notification arrives
            if (flowObjectiveStore.getNextGroup(next.id()) == null) {
                pendingNexts.compute(next.id(), (id, pending) -> {
                    PendingFlowObjective pendfo = new PendingFlowObjective(deviceId, next);
                    if (pending == null) {
                        return Lists.newArrayList(pendfo);
                    } else {
                        pending.add(pendfo);
                        return pending;
                    }
                });
                queued = true;
            }
        }
        if (queued) {
            log.debug("Queued next objective {} with operation {} meant for device {}",
                      next.id(), next.op(), deviceId);
        }
        return queued;
    }

    /**
     * Retrieves (if it exists) the device pipeline behaviour from the cache.
     * Otherwise it warms the caches and triggers the init method of the Pipeline.
     *
     * @param deviceId the id of the device associated to the pipeline
     * @return the implementation of the Pipeliner behaviour
     */
    private Pipeliner getDevicePipeliner(DeviceId deviceId) {
        return pipeliners.computeIfAbsent(deviceId, this::initPipelineHandler);
    }

    /**
     * Retrieves (if it exists) the device pipeline behaviour from the cache and
     * and triggers the init method of the pipeline. Otherwise (DEVICE_ADDED) it warms
     * the caches and triggers the init method of the Pipeline. The rationale of this
     * method is for managing the scenario of a switch that goes down for a failure
     * and goes up after a while.
     *
     * @param deviceId the id of the device associated to the pipeline
     * @return the implementation of the Pipeliner behaviour
     */
    private Pipeliner getAndInitDevicePipeliner(DeviceId deviceId) {
        return pipeliners.compute(deviceId, (deviceIdValue, pipelinerValue) -> {
            if (pipelinerValue != null) {
                pipelinerValue.init(deviceId, context);
                return pipelinerValue;
            }
            return this.initPipelineHandler(deviceId);
        });
    }

    /**
     * Creates and initialize {@link Pipeliner}.
     * <p>
     * Note: Expected to be called under per-Device lock.
     *      e.g., {@code pipeliners}' Map#compute family methods
     *
     * @param deviceId Device to initialize pipeliner
     * @return {@link Pipeliner} instance or null
     */
    private Pipeliner initPipelineHandler(DeviceId deviceId) {
        start = now();

        // Attempt to lookup the handler in the cache
        DriverHandler handler = driverHandlers.get(deviceId);
        cTime = now();

        if (handler == null) {
            try {
                // Otherwise create it and if it has pipeline behaviour, cache it
                handler = driverService.createHandler(deviceId);
                dTime = now();
                if (!handler.driver().hasBehaviour(Pipeliner.class)) {
                    log.debug("Pipeline behaviour not supported for device {}",
                             deviceId);
                    return null;
                }
            } catch (ItemNotFoundException e) {
                log.warn("No applicable driver for device {}", deviceId);
                return null;
            }

            driverHandlers.put(deviceId, handler);
            eTime = now();
        }

        // Always (re)initialize the pipeline behaviour
        log.info("Driver {} bound to device {} ... initializing driver",
                 handler.driver().name(), deviceId);
        hTime = now();
        Pipeliner pipeliner = handler.behaviour(Pipeliner.class);
        hbTime = now();
        pipeliner.init(deviceId, context);
        stopWatch();
        return pipeliner;
    }

    private void invalidatePipelinerIfNecessary(Device device) {
        DriverHandler handler = driverHandlers.get(device.id());
        if (handler != null &&
                !Objects.equals(handler.driver().name(),
                                device.annotations().value(DRIVER))) {
            invalidatePipeliner(device.id());
        }
    }

    private void invalidatePipeliner(DeviceId id) {
        log.info("Invalidating cached pipeline behaviour for {}", id);
        driverHandlers.remove(id);
        pipeliners.remove(id);
        if (deviceService.isAvailable(id)) {
            getAndInitDevicePipeliner(id);
        }
    }

    // Triggers driver setup when a device is (re)detected.
    private class InnerDeviceListener implements DeviceListener {
        @Override
        public void event(DeviceEvent event) {
          if (devEventExecutor != null) {
            switch (event.type()) {
                case DEVICE_ADDED:
                case DEVICE_AVAILABILITY_CHANGED:
                    log.debug("Device either added or availability changed {}",
                              event.subject().id());
                    devEventExecutor.execute(() -> {
                      if (deviceService.isAvailable(event.subject().id())) {
                        log.debug("Device is now available {}", event.subject().id());
                        getAndInitDevicePipeliner(event.subject().id());
                      } else {
                        log.debug("Device is no longer available {}", event.subject().id());
                      }
                    });
                    break;
                case DEVICE_UPDATED:
                    // Invalidate pipeliner and handler caches if the driver name
                    // device annotation changed.
                    devEventExecutor.execute(() -> invalidatePipelinerIfNecessary(event.subject()));
                    break;
                case DEVICE_REMOVED:
                    // evict Pipeliner and Handler cache, when
                    // the Device was administratively removed.
                    //
                    // System expect the user to clear all existing flows,
                    // before removing device, especially if they intend to
                    // replace driver/pipeliner assigned to the device.
                    devEventExecutor.execute(() -> {
                      driverHandlers.remove(event.subject().id());
                      pipeliners.remove(event.subject().id());
                    });
                    break;
                case DEVICE_SUSPENDED:
                    break;
                case PORT_ADDED:
                    break;
                case PORT_UPDATED:
                    break;
                case PORT_REMOVED:
                    break;
                default:
                    break;
            }
          }
        }
    }

    // Monitors driver configuration changes and invalidates the pipeliner cache entries.
    // Note that this may leave stale entries on the device if the driver changes
    // in manner where the new driver does not produce backward compatible flow objectives.
    // In such cases, it is the operator's responsibility to force device re-connect.
    private class InnerDriverListener implements DriverListener {
        @Override
        public void event(DriverEvent event) {
            String driverName = event.subject().name();
            driverHandlers.entrySet().stream()
                    .filter(e -> driverName.equals(e.getValue().driver().name()))
                    .map(Map.Entry::getKey)
                    .distinct()
                    .forEach(FlowObjectiveManager.this::invalidatePipeliner);
        }
    }

    // Temporary mechanism to monitor pipeliner setup time-cost; there are
    // intermittent time where this takes in excess of 2 seconds. Why?
    private long start = 0, totals = 0, count = 0;
    private long cTime, dTime, eTime, hTime, hbTime;
    private static final long LIMIT = 500;

    private long now() {
        return System.currentTimeMillis();
    }

    private void stopWatch() {
        long duration = System.currentTimeMillis() - start;
        totals += duration;
        count += 1;
        if (duration > LIMIT) {
            log.info("Pipeline setup took {} ms; avg {} ms; cTime={}, dTime={}, eTime={}, hTime={}, hbTime={}",
                     duration, totals / count, diff(cTime), diff(dTime), diff(eTime), diff(hTime), diff(hbTime));
        }
    }

    private long diff(long bTime) {
        long diff = bTime - start;
        return diff < 0 ? 0 : diff;
    }

    // Processing context for initializing pipeline driver behaviours.
    private class InnerPipelineContext implements PipelinerContext {
        @Override
        public ServiceDirectory directory() {
            return serviceDirectory;
        }

        @Override
        public FlowObjectiveStore store() {
            return flowObjectiveStore;
        }
    }

    private class InternalStoreDelegate implements FlowObjectiveStoreDelegate {
        @Override
        public void notify(ObjectiveEvent event) {
            if (event.type() == Type.ADD) {
                log.debug("Received notification of obj event {}", event);
                Set<PendingFlowObjective> pending;

                // first send all pending flows
                synchronized (pendingForwards) {
                    // needs to be synchronized for queueObjective lookup
                    pending = pendingForwards.remove(event.subject());
                }
                if (pending == null) {
                    log.debug("No forwarding objectives pending for this "
                            + "obj event {}", event);
                } else {
                    log.debug("Processing {} pending forwarding objectives for nextId {}",
                              pending.size(), event.subject());
                    pending.forEach(p -> getDevicePipeliner(p.deviceId())
                                    .forward((ForwardingObjective) p.flowObjective()));
                }

                // now check for pending next-objectives
                List<PendingFlowObjective> pendNexts;
                synchronized (pendingNexts) {
                    // needs to be synchronized for queueObjective lookup
                    pendNexts = pendingNexts.remove(event.subject());
                }
                if (pendNexts == null) {
                    log.debug("No next objectives pending for this "
                            + "obj event {}", event);
                } else {
                    log.debug("Processing {} pending next objectives for nextId {}",
                              pendNexts.size(), event.subject());
                    pendNexts.forEach(p -> getDevicePipeliner(p.deviceId())
                                    .next((NextObjective) p.flowObjective()));
                }
            }
        }
    }

    /**
     * Data class used to hold a pending flow objective that could not
     * be processed because the associated next object was not present.
     * Note that this pending flow objective could be a forwarding objective
     * waiting for a next objective to complete execution. Or it could a
     * next objective (with a different operation - remove, addToExisting, or
     * removeFromExisting) waiting for a next objective with the same id to
     * complete execution.
     */
    protected class PendingFlowObjective {
        private final DeviceId deviceId;
        private final Objective flowObj;

        PendingFlowObjective(DeviceId deviceId, Objective flowObj) {
            this.deviceId = deviceId;
            this.flowObj = flowObj;
        }

        public DeviceId deviceId() {
            return deviceId;
        }

        public Objective flowObjective() {
            return flowObj;
        }

        @Override
        public int hashCode() {
            return Objects.hash(deviceId, flowObj);
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PendingFlowObjective)) {
                return false;
            }
            final PendingFlowObjective other = (PendingFlowObjective) obj;

            return this.deviceId.equals(other.deviceId) &&
                    this.flowObj.equals(other.flowObj);
        }
    }

    @Override
    public List<String> getNextMappings() {
        List<String> mappings = new ArrayList<>();
        Map<Integer, NextGroup> allnexts = flowObjectiveStore.getAllGroups();
        // XXX if the NextGroup after de-serialization actually stored info of the deviceId
        // then info on any nextObj could be retrieved from one controller instance.
        // Right now the drivers on one instance can only fetch for next-ids that came
        // to them.
        // Also, we still need to send the right next-id to the right driver as potentially
        // there can be different drivers for different devices. But on that account,
        // no instance should be decoding for another instance's nextIds.

        for (Map.Entry<Integer, NextGroup> e : allnexts.entrySet()) {
            // get the device this next Objective was sent to
            DeviceId deviceId = nextToDevice.get(e.getKey());
            mappings.add("NextId " + e.getKey() + ": " +
                    ((deviceId != null) ? deviceId : "nextId not in this onos instance"));
            if (deviceId != null) {
                // this instance of the controller sent the nextObj to a driver
                Pipeliner pipeliner = getDevicePipeliner(deviceId);
                List<String> nextMappings = pipeliner.getNextMappings(e.getValue());
                if (nextMappings != null) {
                    mappings.addAll(nextMappings);
                }
            }
        }
        return mappings;
    }

    @Override
    public List<String> getPendingFlowObjectives() {
        List<String> pendingFlowObjectives = new ArrayList<>();

        for (Integer nextId : pendingForwards.keySet()) {
            Set<PendingFlowObjective> pfwd = pendingForwards.get(nextId);
            StringBuilder pend = new StringBuilder();
            pend.append("NextId: ")
                    .append(nextId);
            for (PendingFlowObjective pf : pfwd) {
                pend.append("\n    FwdId: ")
                        .append(String.format("%11s", pf.flowObjective().id()))
                        .append(", DeviceId: ")
                        .append(pf.deviceId())
                        .append(", Selector: ")
                        .append(((ForwardingObjective) pf.flowObjective())
                                    .selector().criteria());
            }
            pendingFlowObjectives.add(pend.toString());
        }

        for (Integer nextId : pendingNexts.keySet()) {
            List<PendingFlowObjective> pnext = pendingNexts.get(nextId);
            StringBuilder pend = new StringBuilder();
            pend.append("NextId: ")
                    .append(nextId);
            for (PendingFlowObjective pn : pnext) {
                pend.append("\n    NextOp: ")
                        .append(pn.flowObjective().op())
                        .append(", DeviceId: ")
                        .append(pn.deviceId())
                        .append(", Treatments: ")
                        .append(((NextObjective) pn.flowObjective())
                                    .next());
            }
            pendingFlowObjectives.add(pend.toString());
        }

        return pendingFlowObjectives;
    }
}
