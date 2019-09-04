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
package org.onosproject.store.flow.impl;

import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.util.KryoNamespace;
import org.onlab.util.Tools;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.CoreService;
import org.onosproject.core.IdGenerator;
import org.onosproject.mastership.MastershipService;
import org.onosproject.net.DeviceId;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.CompletedBatchOperation;
import org.onosproject.net.flow.DefaultFlowEntry;
import org.onosproject.net.flow.FlowEntry;
import org.onosproject.net.flow.FlowEntry.FlowEntryState;
import org.onosproject.net.flow.FlowId;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.oldbatch.FlowRuleBatchEntry;
import org.onosproject.net.flow.oldbatch.FlowRuleBatchEntry.FlowRuleOperation;
import org.onosproject.net.flow.oldbatch.FlowRuleBatchEvent;
import org.onosproject.net.flow.oldbatch.FlowRuleBatchOperation;
import org.onosproject.net.flow.oldbatch.FlowRuleBatchRequest;
import org.onosproject.net.flow.FlowRuleEvent;
import org.onosproject.net.flow.FlowRuleEvent.Type;
import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.FlowRuleStore;
import org.onosproject.net.flow.FlowRuleStoreDelegate;
import org.onosproject.net.flow.StoredFlowEntry;
import org.onosproject.net.flow.TableStatisticsEntry;
import org.onosproject.store.AbstractStore;
import org.onosproject.store.cluster.messaging.ClusterCommunicationService;
import org.onosproject.store.cluster.messaging.MessageSubject;
import org.onosproject.store.impl.MastershipBasedTimestamp;
import org.onosproject.store.serializers.KryoNamespaces;
import org.onosproject.store.service.AsyncDocumentTree;
import org.onosproject.store.service.DocumentPath;
import org.onosproject.store.service.DocumentTree;
import org.onosproject.store.service.EventuallyConsistentMap;
import org.onosproject.store.service.EventuallyConsistentMapEvent;
import org.onosproject.store.service.EventuallyConsistentMapListener;
import org.onosproject.store.service.IllegalDocumentModificationException;
import org.onosproject.store.service.NoSuchDocumentPathException;
import org.onosproject.store.service.Serializer;
import org.onosproject.store.service.StorageException;
import org.onosproject.store.service.StorageService;
import org.onosproject.store.service.Versioned;
import org.onosproject.store.service.WallClockTimestamp;
import org.slf4j.Logger;

import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.net.flow.FlowRuleEvent.Type.RULE_REMOVED;
import static org.onosproject.net.flow.FlowRuleEvent.Type.RULE_UPDATED;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Manages inventory of flow rules using a distributed state management protocol.
 *
 * @deprecated in Nightingale Release (1.13)
 */
@Deprecated
@Component(enabled = false)
@Service
public class DistributedFlowRuleStore
        extends AbstractStore<FlowRuleBatchEvent, FlowRuleStoreDelegate>
        implements FlowRuleStore {

    private final Logger log = getLogger(getClass());

    // Constant exception used to indicate an atomic read-modify-write operation needs to be retried.
    // We don't want to populate a stack trace every time an optimistic lock is retried.
    private static final StorageException.ConcurrentModification RETRY;

    // Initialize retry exception with an empty stack trace.
    static {
        RETRY = new StorageException.ConcurrentModification();
        RETRY.setStackTrace(new StackTraceElement[0]);
    }

    private static final int SCHEDULED_THREAD_POOL_SIZE = 8;
    private static final int MESSAGE_HANDLER_THREAD_POOL_SIZE = 8;
    private static final int MAX_RETRY_DELAY_MILLIS = 50;

    private static final String FLOW_TABLE = "onos-flow-table";

    private static final MessageSubject APPLY_BATCH_FLOWS = new MessageSubject("onos-flow-apply");
    private static final MessageSubject COMPLETE_BATCH = new MessageSubject("onos-flow-batch-complete");

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MastershipService mastershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterCommunicationService clusterCommunicator;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected StorageService storageService;

    protected final Serializer serializer = Serializer.using(KryoNamespaces.API);

    protected final KryoNamespace.Builder serializerBuilder = KryoNamespace.newBuilder()
            .register(KryoNamespaces.API)
            .register(MastershipBasedTimestamp.class);

    private EventuallyConsistentMap<DeviceId, List<TableStatisticsEntry>> deviceTableStats;
    private final EventuallyConsistentMapListener<DeviceId, List<TableStatisticsEntry>> tableStatsListener =
            new InternalTableStatsListener();

    private Set<Long> pendingBatches = Sets.newConcurrentHashSet();
    private ScheduledExecutorService scheduledExecutor;
    private ExecutorService messageHandlingExecutor;
    private final Random random = new SecureRandom();

    private AsyncDocumentTree<Map<StoredFlowEntry, StoredFlowEntry>> asyncFlows;
    private DocumentTree<Map<StoredFlowEntry, StoredFlowEntry>> flows;
    private IdGenerator idGenerator;
    private NodeId local;

    @Activate
    public void activate() {
        idGenerator = coreService.getIdGenerator(FlowRuleService.FLOW_OP_TOPIC);

        local = clusterService.getLocalNode().id();

        scheduledExecutor = Executors.newScheduledThreadPool(
                SCHEDULED_THREAD_POOL_SIZE,
                groupedThreads("onos/store/flow", "schedulers", log));

        messageHandlingExecutor = Executors.newFixedThreadPool(
                MESSAGE_HANDLER_THREAD_POOL_SIZE,
                groupedThreads("onos/store/flow", "message-handlers", log));

        deviceTableStats = storageService.<DeviceId, List<TableStatisticsEntry>>eventuallyConsistentMapBuilder()
                .withName("onos-flow-table-stats")
                .withSerializer(serializerBuilder)
                .withAntiEntropyPeriod(5, TimeUnit.SECONDS)
                .withTimestampProvider((k, v) -> new WallClockTimestamp())
                .withTombstonesDisabled()
                .build();
        deviceTableStats.addListener(tableStatsListener);

        asyncFlows = storageService.<Map<StoredFlowEntry, StoredFlowEntry>>documentTreeBuilder()
                .withName(FLOW_TABLE)
                .withSerializer(serializer)
                .buildDocumentTree();
        flows = asyncFlows.asDocumentTree();

        clusterCommunicator.addSubscriber(
                APPLY_BATCH_FLOWS,
                serializer::decode,
                this::applyBatchFlows,
                messageHandlingExecutor);
        clusterCommunicator.addSubscriber(
                COMPLETE_BATCH,
                serializer::decode,
                this::completeBatch,
                messageHandlingExecutor);

        log.info("Started");
    }

    @Deactivate
    public void deactivate() {
        deviceTableStats.removeListener(tableStatsListener);
        deviceTableStats.destroy();
        clusterCommunicator.removeSubscriber(APPLY_BATCH_FLOWS);
        clusterCommunicator.removeSubscriber(COMPLETE_BATCH);
        messageHandlingExecutor.shutdownNow();
        scheduledExecutor.shutdownNow();
        log.info("Stopped");
    }

    /**
     * Retries the given supplier until successful.
     * <p>
     * This method retries the given supplier until no {@code ConcurrentModification} exceptions are thrown. In
     * between retries, it waits a semi-random interval to attempt to avoid transaction conflicts with other processes.
     *
     * @param supplier the supplier to retry
     * @param <T> the return type
     * @return the return value of the given supplier once it runs successfully
     */
    private <T> T retryUntilSuccess(Supplier<T> supplier) {
        return Tools.retryable(
                supplier,
                StorageException.ConcurrentModification.class,
                Integer.MAX_VALUE,
                MAX_RETRY_DELAY_MILLIS)
                .get();
    }

    /**
     * Retries the given asynchronous supplier until successful.
     * <p>
     * This method retries the given supplier until no {@code ConcurrentModification} exceptions are thrown. In
     * between retries, it waits a semi-random interval to attempt to avoid transaction conflicts with other processes.
     *
     * @param supplier the supplier to retry
     * @param <T> the return type
     * @return the return value of the given supplier once it runs successfully
     */
    private <T> CompletableFuture<T> retryAsyncUntilSuccess(Supplier<CompletableFuture<T>> supplier) {
        return retryAsyncUntilSuccess(supplier, new CompletableFuture<>());
    }

    /**
     * Retries the given asynchronous supplier until successful.
     * <p>
     * This method retries the given supplier until no {@code ConcurrentModification} exceptions are thrown. In
     * between retries, it waits a semi-random interval to attempt to avoid transaction conflicts with other processes.
     *
     * @param supplier the supplier to retry
     * @param future future to be completed once the operation has been successful
     * @param <T> the return type
     * @return the return value of the given supplier once it runs successfully
     */
    private <T> CompletableFuture<T> retryAsyncUntilSuccess(
            Supplier<CompletableFuture<T>> supplier,
            CompletableFuture<T> future) {
        supplier.get().whenComplete((result, error) -> {
            if (error == null) {
                future.complete(result);
            } else {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                if (cause instanceof StorageException.ConcurrentModification) {
                    scheduledExecutor.schedule(
                            () -> retryAsyncUntilSuccess(supplier, future),
                            random.nextInt(50),
                            TimeUnit.MILLISECONDS);
                } else {
                    future.completeExceptionally(error);
                }
            }
        });
        return future;
    }

    /**
     * Return method for {@link #retryUntilSuccess(Supplier)} callbacks to indicate that the callback needs to be
     * retried after a randomized delay.
     *
     * @param <T> the return type
     * @return nothing
     * @throws StorageException.ConcurrentModification to force a retry of the callback
     */
    private <T> T retry() {
        throw RETRY;
    }

    /**
     * Handles a completed batch event received from the master node.
     * <p>
     * If this node is the source of the batch, notifies event listeners to complete the operations.
     *
     * @param event the event to handle
     */
    private void completeBatch(FlowRuleBatchEvent event) {
        if (pendingBatches.remove(event.subject().batchId())) {
            notifyDelegate(event);
        }
    }

    // This is not a efficient operation on a distributed sharded
    // flow store. We need to revisit the need for this operation or at least
    // make it device specific.
    @Override
    public int getFlowRuleCount() {
        return Streams.stream(deviceService.getDevices()).parallel()
                .mapToInt(device -> Iterables.size(getFlowEntries(device.id())))
                .sum();
    }

    /**
     * Returns the {@link DocumentPath} for the given {@link DeviceId}.
     *
     * @param deviceId the device identifier for which to return a path
     * @return the path for the given device
     */
    private DocumentPath getPathFor(DeviceId deviceId) {
        return DocumentPath.from("root", deviceId.toString());
    }

    /**
     * Returns the {@link DocumentPath} for the given {@link DeviceId} and {@link FlowId}.
     *
     * @param deviceId the device identifier for which to return the path
     * @param flowId the flow identifier for which to return the path
     * @return the path for the given device/flow
     */
    private DocumentPath getPathFor(DeviceId deviceId, FlowId flowId) {
        return DocumentPath.from("root", deviceId.toString(), flowId.toString());
    }

    @Override
    @SuppressWarnings("unchecked")
    public FlowEntry getFlowEntry(FlowRule rule) {
        DeviceId deviceId = rule.deviceId();
        if (mastershipService.getMasterFor(deviceId) != null) {
            DocumentPath path = getPathFor(deviceId, rule.id());
            Versioned<Map<StoredFlowEntry, StoredFlowEntry>> flowEntries = flows.get(path);
            return flowEntries != null ? flowEntries.value().get(rule) : null;
        } else {
            log.debug("Failed to getFlowEntries: No master for {}", deviceId);
            return null;
        }


    }

    @Override
    public Iterable<FlowEntry> getFlowEntries(DeviceId deviceId) {
        if (mastershipService.getMasterFor(deviceId) != null) {
            DocumentPath path = getPathFor(deviceId);
            try {
                return getFlowEntries(path);
            } catch (NoSuchDocumentPathException e) {
                return Collections.emptyList();
            }
        } else {
            log.debug("Failed to getFlowEntries: No master for {}", deviceId);
            return Collections.emptyList();
        }

    }

    @SuppressWarnings("unchecked")
    private Iterable<FlowEntry> getFlowEntries(DocumentPath path) {
        return flows.getChildren(path)
                .values()
                .stream()
                .flatMap(v -> v.value().values().stream())
                .collect(Collectors.toList());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void storeFlowRule(FlowRule rule) {
        storeBatch(new FlowRuleBatchOperation(
                Collections.singletonList(new FlowRuleBatchEntry(FlowRuleOperation.ADD, rule)),
                rule.deviceId(), idGenerator.getNewId()));
    }

    @Override
    public void storeBatch(FlowRuleBatchOperation operation) {
        if (operation.getOperations().isEmpty()) {
            notifyDelegate(FlowRuleBatchEvent.completed(
                    new FlowRuleBatchRequest(operation.id(), Collections.emptySet()),
                    new CompletedBatchOperation(true, Collections.emptySet(), operation.deviceId())));
            return;
        }

        DeviceId deviceId = operation.deviceId();
        NodeId master = mastershipService.getMasterFor(deviceId);

        if (master == null) {
            log.warn("No master for {} ", deviceId);

            updateStoreInternal(operation).whenComplete((result, error) -> {
                notifyDelegate(FlowRuleBatchEvent.completed(
                        new FlowRuleBatchRequest(operation.id(), Collections.emptySet()),
                        new CompletedBatchOperation(true, Collections.emptySet(), operation.deviceId())));
            });
            return;
        }

        pendingBatches.add(operation.id());

        // If the local node is the master, apply the flows. Otherwise, send them to the master.
        if (Objects.equals(local, master)) {
            applyBatchFlows(operation);
        } else {
            log.trace("Forwarding storeBatch to {}, which is the primary (master) for device {}", master, deviceId);
            clusterCommunicator.unicast(
                    operation,
                    APPLY_BATCH_FLOWS,
                    serializer::encode,
                    master);
        }
    }

    /**
     * Asynchronously applies a batch of flows to the store.
     * <p>
     * This operation is performed on the master node to ensure that events occur <em>after</em> flows have been stored
     * and are visible to the master node. If a non-master node stores flows and then triggers events on the master,
     * the flows may not yet be visible to the master node due to the nature of sequentially consistent reads on the
     * underlying {@code DocumentTree} primitive.
     */
    private void applyBatchFlows(FlowRuleBatchOperation operation) {
        updateStoreInternal(operation).whenComplete((operations, error) -> {
            if (error == null) {
                if (operations.isEmpty()) {
                    batchOperationComplete(FlowRuleBatchEvent.completed(
                            new FlowRuleBatchRequest(operation.id(), Collections.emptySet()),
                            new CompletedBatchOperation(true, Collections.emptySet(), operation.deviceId())));
                } else {
                    notifyDelegate(FlowRuleBatchEvent.requested(
                            new FlowRuleBatchRequest(operation.id(), operations),
                            operation.deviceId()));
                }
            }
        });
    }

    private CompletableFuture<Set<FlowRuleBatchEntry>> updateStoreInternal(FlowRuleBatchOperation operation) {
        return Tools.allOf(operation.getOperations().stream().map(op -> {
            switch (op.operator()) {
                case ADD:
                case MODIFY:
                    return addBatchEntry(op).thenApply(succeeded -> succeeded ? op : null);
                case REMOVE:
                    return removeBatchEntry(op).thenApply(succeeded -> succeeded ? op : null);
                default:
                    log.warn("Unknown flow operation operator: {}", op.operator());
                    return CompletableFuture.<FlowRuleBatchEntry>completedFuture(null);
            }
        }).collect(Collectors.toList()))
                .thenApply(results -> results.stream()
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()));
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Boolean> addBatchEntry(FlowRuleBatchEntry batchEntry) {
        StoredFlowEntry entry = new DefaultFlowEntry(batchEntry.target());
        DocumentPath path = getPathFor(entry.deviceId(), entry.id());
        return retryAsyncUntilSuccess(() -> {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            asyncFlows.get(path).whenComplete((value, getError) -> {
                if (getError == null) {
                    if (value != null) {
                        Map<StoredFlowEntry, StoredFlowEntry> entries = Maps.newHashMap(value.value());
                        entries.put(entry, entry);
                        asyncFlows.replace(path, entries, value.version()).whenComplete((succeeded, replaceError) -> {
                            if (replaceError == null) {
                                if (succeeded) {
                                    log.trace("Stored new flow rule: {}", entry);
                                    future.complete(true);
                                } else {
                                    log.trace("Failed to store new flow rule: {}", entry);
                                    future.completeExceptionally(RETRY);
                                }
                            } else {
                                future.completeExceptionally(replaceError);
                            }
                        });
                    } else {
                        // If there are no entries stored for the device, initialize the device's flows.
                        Map<StoredFlowEntry, StoredFlowEntry> map = Maps.newHashMap();
                        map.put(entry, entry);
                        asyncFlows.createRecursive(path, map).whenComplete((succeeded, createError) -> {
                            if (createError == null) {
                                if (succeeded) {
                                    log.trace("Stored new flow rule: {}", entry);
                                    future.complete(true);
                                } else {
                                    log.trace("Failed to store new flow rule: {}", entry);
                                    future.completeExceptionally(RETRY);
                                }
                            } else {
                                future.completeExceptionally(createError);
                            }
                        });
                    }
                } else {
                    future.completeExceptionally(getError);
                }
            });
            return future;
        });
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<Boolean> removeBatchEntry(FlowRuleBatchEntry batchEntry) {
        FlowRule rule = batchEntry.target();
        DocumentPath path = getPathFor(rule.deviceId(), rule.id());
        return retryAsyncUntilSuccess(() -> {
            CompletableFuture<Boolean> future = new CompletableFuture<>();
            asyncFlows.get(path).whenComplete((value, getError) -> {
                if (getError == null) {
                    if (value != null) {
                        Map<StoredFlowEntry, StoredFlowEntry> entries = Maps.newHashMap(value.value());
                        StoredFlowEntry entry = entries.get(rule);
                        if (entry != null) {
                            entry.setState(FlowEntryState.PENDING_REMOVE);
                            asyncFlows.replace(path, entries, value.version()).whenComplete((succeeded, error) -> {
                                if (error == null) {
                                    if (succeeded) {
                                        log.trace("Updated flow rule state to PENDING_REMOVE: {}", entry);
                                        future.complete(true);
                                    } else {
                                        log.trace("Failed to update flow rule state to PENDING_REMOVE: {}", entry);
                                        future.completeExceptionally(RETRY);
                                    }
                                } else {
                                    future.completeExceptionally(error);
                                }
                            });
                        } else {
                            future.complete(false);
                        }
                    } else {
                        future.complete(false);
                    }
                } else {
                    future.completeExceptionally(getError);
                }
            });
            return future;
        });
    }

    @Override
    public void batchOperationComplete(FlowRuleBatchEvent event) {
        if (pendingBatches.remove(event.subject().batchId())) {
            notifyDelegate(event);
        } else {
            clusterCommunicator.broadcast(event, COMPLETE_BATCH, serializer::encode);
        }
    }

    @Override
    public void deleteFlowRule(FlowRule rule) {
        storeBatch(
                new FlowRuleBatchOperation(
                        Collections.singletonList(
                                new FlowRuleBatchEntry(
                                        FlowRuleOperation.REMOVE,
                                        rule)), rule.deviceId(), idGenerator.getNewId()));
    }

    @Override
    public FlowRuleEvent pendingFlowRule(FlowEntry rule) {
        DocumentPath path = getPathFor(rule.deviceId(), rule.id());
        return retryUntilSuccess(() -> {
            Versioned<Map<StoredFlowEntry, StoredFlowEntry>> value = flows.get(path);
            if (value != null) {
                Map<StoredFlowEntry, StoredFlowEntry> entries = Maps.newHashMap(value.value());
                StoredFlowEntry entry = entries.get(rule);
                if (entry != null && entry.state() != FlowEntryState.PENDING_ADD) {
                    entry.setState(FlowEntryState.PENDING_ADD);
                    if (flows.replace(path, entries, value.version())) {
                        log.trace("Updated flow rule state to PENDING_ADD: {}", entry);
                        return new FlowRuleEvent(RULE_UPDATED, rule);
                    } else {
                        log.trace("Failed to update flow rule state to PENDING_ADD: {}", entry);
                        return retry();
                    }
                } else {
                    return null;
                }
            } else {
                return null;
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public FlowRuleEvent addOrUpdateFlowRule(FlowEntry rule) {
        DocumentPath path = getPathFor(rule.deviceId(), rule.id());
        return retryUntilSuccess(() -> {
            Versioned<Map<StoredFlowEntry, StoredFlowEntry>> value = flows.get(path);
            if (value != null) {
                Map<StoredFlowEntry, StoredFlowEntry> entries = Maps.newHashMap(value.value());
                StoredFlowEntry entry = entries.get(rule);
                if (entry != null) {
                    FlowRuleEvent event;
                    String message;

                    entry.setBytes(rule.bytes());
                    entry.setLife(rule.life(TimeUnit.NANOSECONDS), TimeUnit.NANOSECONDS);
                    entry.setLiveType(rule.liveType());
                    entry.setPackets(rule.packets());
                    entry.setLastSeen();

                    // If the entry state is PENDING_ADD, set it to ADDED. Otherwise, just update the rule.
                    if (entry.state() == FlowEntryState.PENDING_ADD) {
                        entry.setState(FlowEntryState.ADDED);
                        event = new FlowRuleEvent(Type.RULE_ADDED, rule);
                        message = "Updated flow rule state to ADDED: {}";
                    } else {
                        event = new FlowRuleEvent(Type.RULE_UPDATED, rule);
                        message = "Updated flow rule: {}";
                    }

                    if (flows.replace(path, entries, value.version())) {
                        log.trace(message, entry);
                        return event;
                    } else {
                        log.trace("Failed to update flow rule: {}", entry);
                        return retry();
                    }
                } else {
                    // If the rule does not exist, return null. Inserting the rule risks race conditions
                    // that can result in removed rules being retained.
                    return null;
                }
            } else {
                return null;
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public FlowRuleEvent removeFlowRule(FlowEntry rule) {
        DocumentPath path = getPathFor(rule.deviceId(), rule.id());
        return retryUntilSuccess(() -> {
            Versioned<Map<StoredFlowEntry, StoredFlowEntry>> value = flows.get(path);
            if (value != null) {
                Map<StoredFlowEntry, StoredFlowEntry> entries = Maps.newHashMap(value.value());
                StoredFlowEntry entry = entries.remove(rule);
                if (entry != null) {
                    if (flows.replace(path, entries, value.version())) {
                        log.trace("Removed flow rule: {}", entry);
                        return new FlowRuleEvent(RULE_REMOVED, entry);
                    } else {
                        log.trace("Failed to remove flow rule: {}", entry);
                        return retry();
                    }
                } else {
                    return null;
                }
            } else {
                return null;
            }
        });
    }

    @Override
    public void purgeFlowRule(DeviceId deviceId) {
        DocumentPath path = getPathFor(deviceId);
        retryUntilSuccess(() -> {
            try {
                for (String flowId : flows.getChildren(path).keySet()) {
                    flows.removeNode(DocumentPath.from("root", deviceId.toString(), flowId));
                }
            } catch (NoSuchDocumentPathException e) {
                // Do nothing. There are no flows for the device.
            }

            // New children may have been created since they were removed above. Catch
            // IllegalDocumentModificationException and retry if necessary.
            try {
                flows.removeNode(path);
            } catch (NoSuchDocumentPathException e) {
                return null;
            } catch (IllegalDocumentModificationException e) {
                return retry();
            }
            return null;
        });
    }

    @Override
    public void purgeFlowRules() {
        try {
            for (String deviceId : flows.getChildren(flows.root()).keySet()) {
                purgeFlowRule(DeviceId.deviceId(deviceId));
            }
        } catch (NoSuchDocumentPathException e) {
            // Do nothing if no children exist.
        }
    }

    @Override
    public FlowRuleEvent updateTableStatistics(DeviceId deviceId,
            List<TableStatisticsEntry> tableStats) {
        deviceTableStats.put(deviceId, tableStats);
        return null;
    }

    @Override
    public Iterable<TableStatisticsEntry> getTableStatistics(DeviceId deviceId) {
        if (mastershipService.getMasterFor(deviceId) != null) {
            List<TableStatisticsEntry> tableStats = deviceTableStats.get(deviceId);
            if (tableStats == null) {
                return Collections.emptyList();
            }
            return ImmutableList.copyOf(tableStats);
        } else {
            log.debug("Failed to getTableStatistics: No master for {}", deviceId);
            return Collections.emptyList();
        }

    }

    @Override
    public long getActiveFlowRuleCount(DeviceId deviceId) {
        if (mastershipService.getMasterFor(deviceId) != null) {
            return Streams.stream(getTableStatistics(deviceId))
                    .mapToLong(TableStatisticsEntry::activeFlowEntries)
                    .sum();
        } else {
            log.debug("Failed to getActiveFlowRuleCount: No master for {}", deviceId);
            return 0;
        }
    }

    private class InternalTableStatsListener
            implements EventuallyConsistentMapListener<DeviceId, List<TableStatisticsEntry>> {
        @Override
        public void event(EventuallyConsistentMapEvent<DeviceId,
                List<TableStatisticsEntry>> event) {
            //TODO: Generate an event to listeners (do we need?)
        }
    }
}