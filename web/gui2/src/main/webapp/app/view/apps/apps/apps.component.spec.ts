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
import { async, ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Params } from '@angular/router';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { FormsModule } from '@angular/forms';
import { DebugElement } from '@angular/core';
import { By } from '@angular/platform-browser';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';

import { LogService } from '../../../log.service';
import { AppsComponent } from './apps.component';
import { AppsDetailsComponent } from '../appsdetails/appsdetails.component';
import { ConfirmComponent } from '../../../fw/layer/confirm/confirm.component';
import { DialogService } from '../../../fw/layer/dialog.service';
import { FlashComponent } from '../../../fw/layer/flash/flash.component';
import { FnService } from '../../../fw/util/fn.service';
import { IconComponent } from '../../../fw/svg/icon/icon.component';
import { IconService } from '../../../fw/svg/icon.service';
import { KeyService } from '../../../fw/util/key.service';
import { LionService } from '../../../fw/util/lion.service';
import { LoadingService } from '../../../fw/layer/loading.service';
import { ThemeService } from '../../../fw/util/theme.service';
import { TableFilterPipe } from '../../../fw/widget/tablefilter.pipe';
import { UrlFnService } from '../../../fw/remote/urlfn.service';
import { WebSocketService } from '../../../fw/remote/websocket.service';
import { of } from 'rxjs';
import { } from 'jasmine';

class MockActivatedRoute extends ActivatedRoute {
    constructor(params: Params) {
        super();
        this.queryParams = of(params);
    }
}

class MockDialogService { }

class MockFnService { }

class MockHttpClient {}

class MockIconService {
    loadIconDef() { }
}

class MockKeyService { }

class MockLoadingService {
    startAnim() { }
    stop() { }
    waiting() { }
}

class MockThemeService { }

class MockUrlFnService { }

class MockWebSocketService {
    createWebSocket() { }
    isConnected() { return false; }
    unbindHandlers() { }
    bindHandlers() { }
}

/**
 * ONOS GUI -- Apps View -- Unit Tests
 */
describe('AppsComponent', () => {
    let fs: FnService;
    let ar: MockActivatedRoute;
    let windowMock: Window;
    let logServiceSpy: jasmine.SpyObj<LogService>;
    let component: AppsComponent;
    let fixture: ComponentFixture<AppsComponent>;
    const bundleObj = {
        'core.view.App': {
            test: 'test1'
        }
    };
    const mockLion = (key) => {
        return bundleObj[key] || '%' + key + '%';
    };

    beforeEach(async(() => {
        const logSpy = jasmine.createSpyObj('LogService', ['info', 'debug', 'warn', 'error']);
        ar = new MockActivatedRoute({ 'debug': 'txrx' });

        windowMock = <any>{
            location: <any>{
                hostname: 'foo',
                host: 'foo',
                port: '80',
                protocol: 'http',
                search: { debug: 'true' },
                href: 'ws://foo:123/onos/ui/websock/path',
                absUrl: 'ws://foo:123/onos/ui/websock/path'
            }
        };
        fs = new FnService(ar, logSpy, windowMock);

        TestBed.configureTestingModule({
            imports: [ BrowserAnimationsModule, FormsModule ],
            declarations: [
                AppsComponent,
                ConfirmComponent,
                IconComponent,
                AppsDetailsComponent,
                TableFilterPipe,
                FlashComponent
            ],
            providers: [
                { provide: DialogService, useClass: MockDialogService },
                { provide: FnService, useValue: fs },
                { provide: HttpClient, useClass: MockHttpClient },
                { provide: IconService, useClass: MockIconService },
                { provide: KeyService, useClass: MockKeyService },
                {
                    provide: LionService, useFactory: (() => {
                        return {
                            bundle: ((bundleId) => mockLion),
                            ubercache: new Array(),
                            loadCbs: new Map<string, () => void>([])
                        };
                    })
                },
                { provide: LoadingService, useClass: MockLoadingService },
                { provide: LogService, useValue: logSpy },
                { provide: ThemeService, useClass: MockThemeService },
                { provide: UrlFnService, useClass: MockUrlFnService },
                { provide: WebSocketService, useClass: MockWebSocketService },
                { provide: 'Window', useValue: windowMock },
            ]
        })
            .compileComponents();
        logServiceSpy = TestBed.get(LogService);
    }));

    beforeEach(() => {
        fixture = TestBed.createComponent(AppsComponent);
        component = fixture.debugElement.componentInstance;
        fixture.detectChanges();
    });

    it('should create', () => {
        expect(component).toBeTruthy();
    });

    it('should have a div.tabular-header inside a div#ov-app', () => {
        const appDe: DebugElement = fixture.debugElement;
        const divDe = appDe.query(By.css('div#ov-app div.tabular-header'));
        expect(divDe).toBeTruthy();
    });

    it('should have a h2 inside the div.tabular-header', () => {
        const appDe: DebugElement = fixture.debugElement;
        const divDe = appDe.query(By.css('div#ov-app div.tabular-header h2'));
        const div: HTMLElement = divDe.nativeElement;
        expect(div.textContent).toEqual(' %title_apps% (0 %total%) ');
    });

    it('should have a refresh button inside the div.tabular-header', () => {
        const appDe: DebugElement = fixture.debugElement;
        const divDe = appDe.query(By.css('div#ov-app div.tabular-header div.ctrl-btns div.refresh'));
        expect(divDe).toBeTruthy();
    });

    it('should have an active button inside the div.tabular-header', () => {
        const appDe: DebugElement = fixture.debugElement;
        const divDe = appDe.query(By.css('div#ov-app div.tabular-header div.ctrl-btns div.active'));
        expect(divDe).toBeTruthy();
    });

    it('should have a div.summary-list inside a div#ov-app', () => {
        const appDe: DebugElement = fixture.debugElement;
        const divDe = appDe.query(By.css('div#ov-app div.summary-list'));
        expect(divDe).toBeTruthy();
    });
});
