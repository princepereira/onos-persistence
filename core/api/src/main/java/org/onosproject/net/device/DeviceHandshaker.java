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

package org.onosproject.net.device;

import com.google.common.annotations.Beta;
import org.onosproject.net.MastershipRole;
import org.onosproject.net.driver.DeviceConnect;
import org.onosproject.net.provider.ProviderId;

import java.util.concurrent.CompletableFuture;

/**
 * Behavior to test device's reachability and change the mastership role on that
 * device.
 */
@Beta
public interface DeviceHandshaker extends DeviceConnect {

    /**
     * Checks the reachability (connectivity) of a device. Reachability, unlike
     * availability, denotes whether THIS particular node can send messages and
     * receive replies from the specified device.
     *
     * @return CompletableFuture eventually true if reachable, false otherwise
     */
    CompletableFuture<Boolean> isReachable();

    /**
     * Notifies the device a mastership role change as decided by the core. The
     * implementation of this method should trigger a {@link DeviceAgentEvent}
     * signaling the mastership role accepted by the device.
     *
     * @param newRole new mastership role
     */
    void roleChanged(MastershipRole newRole);

    /**
     * Returns the last known mastership role agreed by the device for this
     * node.
     *
     * @return mastership role
     */
    MastershipRole getRole();

    /**
     * Adds a device agent listener for the given provider ID.
     *
     * @param providerId provider ID
     * @param listener   device agent listener
     */
    default void addDeviceAgentListener(
            ProviderId providerId, DeviceAgentListener listener) {
        throw new UnsupportedOperationException(
                "Device agent listener registration not supported");
    }

    /**
     * Removes a device agent listener previously registered for the given
     * provider ID.
     *
     * @param providerId provider ID
     */
    default void removeDeviceAgentListener(ProviderId providerId) {
        throw new UnsupportedOperationException(
                "Device agent listener removal not supported");
    }

}
