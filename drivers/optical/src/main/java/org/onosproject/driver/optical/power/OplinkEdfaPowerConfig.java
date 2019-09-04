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

package org.onosproject.driver.optical.power;

import java.util.Optional;

import com.google.common.collect.Range;
import org.onosproject.net.driver.AbstractHandlerBehaviour;
import org.onosproject.net.PortNumber;
import org.onosproject.net.behaviour.PowerConfig;

/**
 * Port Power (Gain and attenuation) implementation for Oplink EDFA device.
 *
 * An Oplink EDFA port exposes Direction resources.
 * Set gain(attenuation) at AGC mode or set output level at APC mode.
 *
 */

public class OplinkEdfaPowerConfig extends AbstractHandlerBehaviour
                                    implements PowerConfig<Object> {

    // oplink power config utility
    private OplinkPowerConfigUtil oplinkUtil = new OplinkPowerConfigUtil(this);

    @Override
    public Optional<Long> getTargetPower(PortNumber port, Object component) {
        return Optional.ofNullable(oplinkUtil.getTargetPower(port, component));
    }

    @Override
    public Optional<Long> currentPower(PortNumber port, Object component) {
        return Optional.ofNullable(oplinkUtil.getCurrentPower(port, component));
    }

    @Override
    public void setTargetPower(PortNumber port, Object component, long power) {
        oplinkUtil.setTargetPower(port, component, power);
    }

    @Override
    public Optional<Range<Long>> getTargetPowerRange(PortNumber port, Object component) {
        return Optional.ofNullable(oplinkUtil.getTargetPowerRange(port, component));
    }

    @Override
    public Optional<Range<Long>> getInputPowerRange(PortNumber port, Object component) {
        return Optional.ofNullable(oplinkUtil.getInputPowerRange(port, component));
    }
}
