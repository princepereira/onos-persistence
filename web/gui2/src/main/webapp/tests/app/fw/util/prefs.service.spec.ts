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
import { TestBed, inject } from '@angular/core/testing';

import { LogService } from '../../../../app/log.service';
import { ConsoleLoggerService } from '../../../../app/consolelogger.service';
import { PrefsService } from '../../../../app/fw/util/prefs.service';
import { FnService } from '../../../../app/fw/util/fn.service';
import { WebSocketService } from '../../../../app/fw/remote/websocket.service';

class MockFnService {}

class MockWebSocketService {}

/**
 * ONOS GUI -- Util -- User Preference Service - Unit Tests
 */
describe('PrefsService', () => {
    let log: LogService;

    beforeEach(() => {
        log = new ConsoleLoggerService();

        TestBed.configureTestingModule({
            providers: [PrefsService,
                { provide: LogService, useValue: log },
                { provide: FnService, useClass: MockFnService },
                { provide: WebSocketService, useClass: MockWebSocketService },
            ]
        });
    });

    it('should be created', inject([PrefsService], (service: PrefsService) => {
        expect(service).toBeTruthy();
    }));
});
