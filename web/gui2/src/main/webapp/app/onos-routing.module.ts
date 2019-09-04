/*
 * Copyright 2018-present Open Networking Foundation
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
import { NgModule } from '@angular/core';
import { Routes, RouterModule } from '@angular/router';

/**
 * The set of Routes in the application - can be chosen from nav menu or
 * elsewhere like tabular icon for flows etc
 */
const onosRoutes: Routes = [
    {
        path: 'app',
        loadChildren: 'app/view/apps/apps.module#AppsModule'
    },
    {
        path: 'processor',
        loadChildren: 'app/view/processor/processor.module#ProcessorModule'
    },
    {
        path: 'settings',
        loadChildren: 'app/view/settings/settings.module#SettingsModule'
    },
    {
        path: 'partition',
        loadChildren: 'app/view/partition/partition.module#PartitionModule'
    },
    {
        path: 'cluster',
        loadChildren: 'app/view/cluster/cluster.module#ClusterModule'
    },
    {
        path: 'device',
        loadChildren: 'app/view/device/device.module#DeviceModule'
    },
    {
        path: 'link',
        loadChildren: 'app/view/link/link.module#LinkModule'
    },
    {
        path: 'host',
        loadChildren: 'app/view/host/host.module#HostModule'
    },
    {
        path: 'intent',
        loadChildren: 'app/view/intent/intent.module#IntentModule'
    },
    {
        path: 'tunnel',
        loadChildren: 'app/view/tunnel/tunnel.module#TunnelModule'
    },
    {
        path: 'flow',
        loadChildren: 'app/view/flow/flow.module#FlowModule'
    },
    {
        path: 'port',
        loadChildren: 'app/view/port/port.module#PortModule'
    },
    {
        path: 'group',
        loadChildren: 'app/view/group/group.module#GroupModule'
    },
    {
        path: 'meter',
        loadChildren: 'app/view/meter/meter.module#MeterModule'
    },
    {
        path: '',
        redirectTo: 'device', // Default to devices view - change to topo in future
        pathMatch: 'full'
    }
];

/**
 * ONOS GUI -- Main Routing Module - allows modules to be lazy loaded
 *
 * See https://angular.io/guide/lazy-loading-ngmodules
 * for the theory of operation
 */
@NgModule({
    imports: [
        RouterModule.forRoot(onosRoutes)
    ],
    exports: [RouterModule],
    providers: []
})
export class OnosRoutingModule { }
