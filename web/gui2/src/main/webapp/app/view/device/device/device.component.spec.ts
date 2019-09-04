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
import { DebugElement } from '@angular/core';
import { By } from '@angular/platform-browser';
import { LogService } from '../../../log.service';
import { DeviceComponent } from './device.component';
import { } from 'jasmine';

import { FnService } from '../../../fw/util/fn.service';
import { IconService } from '../../../fw/svg/icon.service';
import { GlyphService } from '../../../fw/svg/glyph.service';
import { IconComponent } from '../../../fw/svg/icon/icon.component';
import { KeyService } from '../../../fw/util/key.service';
import { LoadingService } from '../../../fw/layer/loading.service';
import { NavService } from '../../../fw/nav/nav.service';
import { MastService } from '../../../fw/mast/mast.service';
import { TableFilterPipe } from '../../../fw/widget/tablefilter.pipe';
import { ThemeService } from '../../../fw/util/theme.service';
import { WebSocketService } from '../../../fw/remote/websocket.service';
import { of } from 'rxjs';
import { BrowserAnimationsModule } from '@angular/platform-browser/animations';
import { FormsModule } from '@angular/forms';
import { DeviceDetailsComponent } from './../devicedetails/devicedetails.component';
import { RouterTestingModule } from '@angular/router/testing';

class MockActivatedRoute extends ActivatedRoute {
    constructor(params: Params) {
        super();
        this.queryParams = of(params);
    }
}

class MockIconService {
    loadIconDef() { }
}

class MockGlyphService { }

class MockKeyService { }

class MockLoadingService {
    startAnim() { }
    stop() { }
}

class MockNavService { }

class MockMastService { }

class MockThemeService { }

class MockWebSocketService {
    createWebSocket() { }
    isConnected() { return false; }
    unbindHandlers() { }
    bindHandlers() { }
}

/**
 * ONOS GUI -- Device View Module - Unit Tests
 */
describe('DeviceComponent', () => {
    let fs: FnService;
    let ar: MockActivatedRoute;
    let windowMock: Window;
    let logServiceSpy: jasmine.SpyObj<LogService>;
    let component: DeviceComponent;
    let fixture: ComponentFixture<DeviceComponent>;

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
            imports: [BrowserAnimationsModule, FormsModule, RouterTestingModule],
            declarations: [DeviceComponent, IconComponent, TableFilterPipe, DeviceDetailsComponent],
            providers: [
                { provide: FnService, useValue: fs },
                { provide: IconService, useClass: MockIconService },
                { provide: GlyphService, useClass: MockGlyphService },
                { provide: KeyService, useClass: MockKeyService },
                { provide: LoadingService, useClass: MockLoadingService },
                { provide: MastService, useClass: MockMastService },
                { provide: NavService, useClass: MockNavService },
                { provide: LogService, useValue: logSpy },
                { provide: ThemeService, useClass: MockThemeService },
                { provide: WebSocketService, useClass: MockWebSocketService },
                { provide: 'Window', useValue: windowMock },
            ]
        }).compileComponents();
        logServiceSpy = TestBed.get(LogService);
    }));

    beforeEach(() => {
        fixture = TestBed.createComponent(DeviceComponent);
        component = fixture.debugElement.componentInstance;
        fixture.detectChanges();
    });

    it('should create', () => {
        expect(component).toBeTruthy();
    });

    it('should have a div.tabular-header inside a div#ov-device', () => {
        const devDe: DebugElement = fixture.debugElement;
        const divDe = devDe.query(By.css('div#ov-device div.tabular-header'));
        expect(divDe).toBeTruthy();
    });

    it('should have .table-header with "Friendly Name..."', () => {
        const devDe: DebugElement = fixture.debugElement;
        const divDe = devDe.query(By.css('div#ov-device div.table-header'));
        const div: HTMLElement = divDe.nativeElement;
        expect(div.textContent).toEqual('Friendly Name Device ID Master Ports Vendor H/W Version S/W Version Protocol ');
    });

    it('should have a refresh button inside the div.tabular-header', () => {
        const devDe: DebugElement = fixture.debugElement;
        const divDe = devDe.query(By.css('div#ov-device div.tabular-header div.ctrl-btns div.refresh'));
        expect(divDe).toBeTruthy();
    });


    it('should have a div.table-body ', () => {
        const devDe: DebugElement = fixture.debugElement;
        const divDe = devDe.query(By.css('div#ov-device  div.table-body'));
        expect(divDe).toBeTruthy();
    });
});
