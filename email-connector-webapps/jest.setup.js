/*
 * Copyright (C) 2025 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

// Vuetify and the platform's own global components (extensionRegistry, exo-drawer…)
// are registered at runtime by the portal, never by these component tests — a
// shallowMount renders their tags as plain unknown elements, which is fine, but Vue
// logs one warning per tag per render. Silenced here so a real assertion failure is
// not lost in that noise.
const Vue = require('vue');
Vue.config.productionTip = false;
Vue.config.ignoredElements = [/^v-/, 'extension-registry-components'];
