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
import { CommonModule } from '@angular/common';

import { IntentRoutingModule } from './intent-routing.module';
import { IntentComponent } from './intent/intent.component';
import { SvgModule } from '../../fw/svg/svg.module';
import { WidgetModule } from '../../fw/widget/widget.module';
import { RouterModule } from '@angular/router';
import { LayerModule } from '../../fw/layer/layer.module';

@NgModule({
    imports: [
        CommonModule,
        SvgModule,
        WidgetModule,
        RouterModule,
        IntentRoutingModule,
        LayerModule
    ],
    declarations: [IntentComponent],
    exports: [IntentComponent]
})
export class IntentModule { }
