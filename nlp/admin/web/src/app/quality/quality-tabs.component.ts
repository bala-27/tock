/*
 * Copyright (C) 2017 VSCT
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {Component, OnInit} from "@angular/core";

class TabLink {
  constructor(public link: string, public label: string) {

  }
}

const tabs = [
  new TabLink("log-stats", "Stats"),
  new TabLink("test-builds", "Tests Trend"),
  new TabLink("test-intent-errors", "Intent Errors"),
  new TabLink("test-entity-errors", "Entity Errors"),
  new TabLink("model-builds", "Model Builds"),
];

@Component({
  selector: 'tock-quality-tabs',
  templateUrl: './quality-tabs.component.html'
})
export class QualityTabsComponent implements OnInit {

  tabLinks = tabs;

  constructor() {
  }

  ngOnInit() {
  }

}
