/*
 * Copyright 2017-present Open Networking Foundation
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

package org.onosproject.net.pi.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.junit.Before;
import org.junit.Test;
import org.onlab.util.ItemNotFoundException;
import org.onosproject.net.DeviceId;
import org.onosproject.net.config.Config;
import org.onosproject.net.config.ConfigApplyDelegate;
import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import org.onosproject.net.config.NetworkConfigRegistryAdapter;
import org.onosproject.net.config.basics.BasicDeviceConfig;
import org.onosproject.net.device.DeviceDescription;
import org.onosproject.net.device.DeviceDescriptionDiscovery;
import org.onosproject.net.device.PortDescription;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.driver.Behaviour;
import org.onosproject.net.driver.Driver;
import org.onosproject.net.driver.DriverAdapter;
import org.onosproject.net.driver.DriverAdminService;
import org.onosproject.net.driver.DriverAdminServiceAdapter;
import org.onosproject.net.driver.DriverProvider;
import org.onosproject.net.pi.model.PiPipeconf;
import org.onosproject.net.pi.model.PiPipeconfId;
import org.onosproject.net.pi.service.PiPipeconfConfig;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.onosproject.pipelines.basic.PipeconfLoader.BASIC_PIPECONF;


/**
 * Unit Test Class for PiPipeconfManager.
 */
public class PiPipeconfManagerTest {

    private static final DeviceId DEVICE_ID = DeviceId.deviceId("test:test");
    private static final String BASE_DRIVER = "baseDriver";

    //Mock util sets and classes
    private final NetworkConfigRegistry cfgService = new MockNetworkConfigRegistry();
    private final DriverAdminService driverAdminService = new MockDriverAdminService();
    private Driver baseDriver = new MockDriver();

    private final Set<ConfigFactory> cfgFactories = new HashSet<>();
    private final Set<NetworkConfigListener> netCfgListeners = new HashSet<>();
    private final Set<DriverProvider> providers = new HashSet<>();

    private final PiPipeconfConfig piPipeconfConfig = new PiPipeconfConfig();
    private final InputStream jsonStream = PiPipeconfManagerTest.class
            .getResourceAsStream("/org/onosproject/net/pi/impl/piPipeconfId.json");
    private final BasicDeviceConfig basicDeviceConfig = new BasicDeviceConfig();
    private final InputStream jsonStreamBasic = PiPipeconfManagerTest.class
            .getResourceAsStream("/org/onosproject/net/pi/impl/basic.json");


    //Services
    private PiPipeconfManager piPipeconfService;
    private PiPipeconf piPipeconf;

    @Before
    public void setUp() throws IOException {
        piPipeconfService = new PiPipeconfManager();
        piPipeconf = BASIC_PIPECONF;
        piPipeconfService.cfgService = cfgService;
        piPipeconfService.driverAdminService = driverAdminService;
        String key = "piPipeconf";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonNode = mapper.readTree(jsonStream);
        ConfigApplyDelegate delegate = new MockDelegate();
        piPipeconfConfig.init(DEVICE_ID, key, jsonNode, mapper, delegate);
        String keyBasic = "basic";
        JsonNode jsonNodeBasic = mapper.readTree(jsonStreamBasic);
        basicDeviceConfig.init(DEVICE_ID, keyBasic, jsonNodeBasic, mapper, delegate);
        piPipeconfService.activate();
    }

    @Test
    public void activate() {
        assertEquals("Incorrect driver admin service", driverAdminService, piPipeconfService.driverAdminService);
        assertEquals("Incorrect driverAdminService service", driverAdminService, piPipeconfService.driverAdminService);
        assertEquals("Incorrect configuration service", cfgService, piPipeconfService.cfgService);
        assertTrue("Incorrect config factory", cfgFactories.contains(piPipeconfService.configFactory));
    }

    @Test
    public void deactivate() {
        piPipeconfService.deactivate();
        assertEquals("Incorrect driver admin service", null, piPipeconfService.driverAdminService);
        assertEquals("Incorrect driverAdminService service", null, piPipeconfService.driverAdminService);
        assertEquals("Incorrect configuration service", null, piPipeconfService.cfgService);
        assertFalse("Config factory should be unregistered", cfgFactories.contains(piPipeconfService.configFactory));
    }

    @Test
    public void register() {
        piPipeconfService.register(piPipeconf);
        assertTrue("PiPipeconf should be registered", piPipeconfService.pipeconfs.containsValue(piPipeconf));
    }

    @Test
    public void getPipeconf() {
        piPipeconfService.register(piPipeconf);
        assertEquals("Returned PiPipeconf is not correct", piPipeconf,
                     piPipeconfService.getPipeconf(piPipeconf.id()).get());
    }


    @Test
    public void mergeDriver() {
        PiPipeconfId piPipeconfId = cfgService.getConfig(DEVICE_ID, PiPipeconfConfig.class).piPipeconfId();
        assertEquals(piPipeconf.id(), piPipeconfId);

        String baseDriverName = cfgService.getConfig(DEVICE_ID, BasicDeviceConfig.class).driver();
        assertEquals(BASE_DRIVER, baseDriverName);

        piPipeconfService.register(piPipeconf);
        assertEquals("Returned PiPipeconf is not correct", piPipeconf,
                     piPipeconfService.getPipeconf(piPipeconf.id()).get());

        String mergedDriverName = piPipeconfService.getMergedDriver(DEVICE_ID, piPipeconfId);

        String expectedName = BASE_DRIVER + ":" + piPipeconfId.id();
        assertEquals(expectedName, mergedDriverName);

        //we assume that the provider is 1 and that it contains 1 driver
        //we also assume that everything after driverAdminService.registerProvider(provider); has been tested.
        assertTrue("Provider should be registered", providers.size() == 1);

        assertTrue("Merged driver name should be valid",
                   mergedDriverName != null && !mergedDriverName.isEmpty());

        DriverProvider provider = providers.iterator().next();
        assertTrue("Provider should contain one driver", provider.getDrivers().size() == 1);

        Driver driver = provider.getDrivers().iterator().next();

        Set<Class<? extends Behaviour>> expectedBehaviours = Sets.newHashSet();
        expectedBehaviours.addAll(BASIC_PIPECONF.behaviours());
        expectedBehaviours.addAll(baseDriver.behaviours());
        assertEquals("The driver contains wrong behaviours", expectedBehaviours, driver.behaviours());
    }

    private class MockNetworkConfigRegistry extends NetworkConfigRegistryAdapter {
        @Override
        public void registerConfigFactory(ConfigFactory configFactory) {
            cfgFactories.add(configFactory);
        }

        @Override
        public void unregisterConfigFactory(ConfigFactory configFactory) {
            cfgFactories.remove(configFactory);
        }

        @Override
        public void addListener(NetworkConfigListener listener) {
            netCfgListeners.add(listener);
        }

        @Override
        public void removeListener(NetworkConfigListener listener) {
            netCfgListeners.remove(listener);
        }

        @Override
        public <S, C extends Config<S>> C getConfig(S subject, Class<C> configClass) {
            DeviceId did = (DeviceId) subject;
            if (configClass.equals(PiPipeconfConfig.class)
                    && did.equals(DEVICE_ID)) {
                return (C) piPipeconfConfig;
            } else if (configClass.equals(BasicDeviceConfig.class)
                    && did.equals(DEVICE_ID)) {
                return (C) basicDeviceConfig;
            }
            return null;
        }
    }

    private class MockDriverAdminService extends DriverAdminServiceAdapter {

        @Override
        public Driver getDriver(String driverName) {
            if (driverName.equals(BASE_DRIVER)) {
                return baseDriver;
            }
            throw new ItemNotFoundException("Driver not found");
        }

        @Override
        public void registerProvider(DriverProvider provider) {
            providers.add(provider);
        }

        @Override
        public Set<DriverProvider> getProviders() {
            return providers;
        }
    }

    private class MockDelegate implements ConfigApplyDelegate {
        @Override
        public void onApply(Config configFile) {
        }
    }

    private class MockDriver extends DriverAdapter {

        @Override
        public List<Driver> parents() {
            return ImmutableList.of();
        }

        @Override
        public String manufacturer() {
            return "Open Networking Foundation";
        }

        @Override
        public String hwVersion() {
            return "testHW";
        }

        @Override
        public Class<? extends Behaviour> implementation(Class<? extends Behaviour> behaviour) {
            return MockDeviceDescriptionDiscovery.class;
        }

        @Override
        public Map<String, String> properties() {
            return new HashMap<>();
        }

        @Override
        public String getProperty(String name) {
            return null;
        }

        @Override
        public Set<Class<? extends Behaviour>> behaviours() {
            return ImmutableSet.of(DeviceDescriptionDiscovery.class);
        }

        @Override
        public String swVersion() {
            return "testSW";
        }

        @Override
        public String name() {
            return BASE_DRIVER;
        }
    }

    private class MockDeviceDescriptionDiscovery extends AbstractHandlerBehaviour
            implements DeviceDescriptionDiscovery {
        @Override
        public DeviceDescription discoverDeviceDetails() {
            return null;
        }

        @Override
        public List<PortDescription> discoverPortDetails() {
            return null;
        }
    }
}
