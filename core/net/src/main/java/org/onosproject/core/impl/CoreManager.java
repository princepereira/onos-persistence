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
package org.onosproject.core.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.Service;
import org.onlab.metrics.MetricsService;
import org.onlab.util.SharedExecutors;
import org.onlab.util.SharedScheduledExecutors;
import org.onlab.util.Tools;
import org.onosproject.app.ApplicationService;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.core.ApplicationId;
import org.onosproject.app.ApplicationIdStore;
import org.onosproject.core.CoreService;
import org.onosproject.core.IdBlockStore;
import org.onosproject.core.IdGenerator;
import org.onosproject.core.Version;
import org.onosproject.core.VersionService;
import org.onosproject.event.EventDeliveryService;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Set;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.onosproject.security.AppGuard.checkPermission;
import static org.onosproject.security.AppPermission.Type.APP_READ;
import static org.onosproject.security.AppPermission.Type.APP_WRITE;

/**
 * Core service implementation.
 */
@Component(immediate = true)
@Service
public class CoreManager implements CoreService {

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected VersionService versionService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ApplicationIdStore applicationIdStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected IdBlockStore idBlockStore;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ApplicationService appService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected EventDeliveryService eventDeliveryService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected MetricsService metricsService;

    private static final int DEFAULT_POOL_SIZE = 30;
    @Property(name = "sharedThreadPoolSize", intValue = DEFAULT_POOL_SIZE,
            label = "Configure shared pool maximum size ")
    private int sharedThreadPoolSize = DEFAULT_POOL_SIZE;

    private static final int DEFAULT_EVENT_TIME = 2000;
    @Property(name = "maxEventTimeLimit", intValue = DEFAULT_EVENT_TIME,
            label = "Maximum number of millis an event sink has to process an event")
    private int maxEventTimeLimit = DEFAULT_EVENT_TIME;

    private static final boolean DEFAULT_PERFORMANCE_CHECK = false;
    @Property(name = "sharedThreadPerformanceCheck", boolValue = DEFAULT_PERFORMANCE_CHECK,
            label = "Enable queue performance check on shared pool")
    private boolean calculatePoolPerformance = DEFAULT_PERFORMANCE_CHECK;


    @Activate
    protected void activate() {
        registerApplication(CORE_APP_NAME);
        cfgService.registerProperties(getClass());
    }

    @Deactivate
    protected void deactivate() {
        cfgService.unregisterProperties(getClass(), false);
        SharedExecutors.shutdown();
        SharedScheduledExecutors.shutdown();
    }

    @Override
    public Version version() {
        checkPermission(APP_READ);
        return versionService.version();
    }

    @Override
    public Set<ApplicationId> getAppIds() {
        checkPermission(APP_READ);
        return applicationIdStore.getAppIds();
    }

    @Override
    public ApplicationId getAppId(Short id) {
        checkPermission(APP_READ);
        return applicationIdStore.getAppId(id);
    }

    @Override
    public ApplicationId getAppId(String name) {
        checkPermission(APP_READ);
        return applicationIdStore.getAppId(name);
    }

    @Override
    public ApplicationId registerApplication(String name) {
        checkPermission(APP_WRITE);
        checkNotNull(name, "Application ID cannot be null");
        return applicationIdStore.registerApplication(name);
    }

    @Override
    public ApplicationId registerApplication(String name, Runnable preDeactivate) {
        checkPermission(APP_WRITE);
        ApplicationId id = registerApplication(name);
        appService.registerDeactivateHook(id, preDeactivate);
        return id;
    }

    @Override
    public IdGenerator getIdGenerator(String topic) {
        checkPermission(APP_READ);
        IdBlockAllocator allocator = new StoreBasedIdBlockAllocator(topic, idBlockStore);
        return new BlockAllocatorBasedIdGenerator(allocator);
    }

    @Modified
    protected void modified(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();
        Integer poolSize = Tools.getIntegerProperty(properties, "sharedThreadPoolSize");

        if (poolSize != null && poolSize > 1) {
            sharedThreadPoolSize = poolSize;
            SharedExecutors.setPoolSize(sharedThreadPoolSize);
        } else if (poolSize != null) {
            log.warn("sharedThreadPoolSize must be greater than 1");
        }

        Integer timeLimit = Tools.getIntegerProperty(properties, "maxEventTimeLimit");
        if (timeLimit != null && timeLimit >= 0) {
            maxEventTimeLimit = timeLimit;
            eventDeliveryService.setDispatchTimeLimit(maxEventTimeLimit);
        } else if (timeLimit != null) {
            log.warn("maxEventTimeLimit must be greater than or equal to 0");
        }

        Boolean performanceCheck = Tools.isPropertyEnabled(properties, "sharedThreadPerformanceCheck");
        if (performanceCheck != null) {
            calculatePoolPerformance = performanceCheck;
            SharedExecutors.setMetricsService(calculatePoolPerformance ? metricsService : null);
        }

        log.info("Settings: sharedThreadPoolSize={}, maxEventTimeLimit={}, calculatePoolPerformance={}",
                 sharedThreadPoolSize, maxEventTimeLimit, calculatePoolPerformance);
    }
}
