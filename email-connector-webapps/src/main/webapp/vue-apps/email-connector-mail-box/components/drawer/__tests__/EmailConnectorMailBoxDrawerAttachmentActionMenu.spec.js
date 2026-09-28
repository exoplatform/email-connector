/*
 * Copyright (C) 2026 eXo Platform SAS.
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

// The attachment menu's action context (EXO-90419): a contributed vueComponent gets
// the same context as a click() action, and storeInMailAttachments() stores the
// attachment through the very call opening it uses, so an attachment already stored
// is not stored a second time within the page. A contributed component rendering
// several rows says so, so the menu still opens upward when it lacks room below.

import Vue from 'vue';
import { shallowMount } from '@vue/test-utils';

const registered = [];
let documentsDeployed = true;
global.Vue = Vue;
global.extensionRegistry = {
  registerExtension: (type, name, extension) => registered.push({ type, name, extension }),
  loadExtensions: (type, name) => {
    if (type === 'RichEditor' && name === 'ckeditor-extensions') {
      return documentsDeployed ? [{ id: 'attachFile' }] : [];
    }
    return registered.filter(r => r.type === type && r.name === name).map(r => r.extension);
  },
};
global.eXo = { env: { portal: { context: '/portal', rest: 'rest', portalName: 'dw', language: 'en' } } };

const service = require('../../../js/EmailConnectorMailBoxService.js');
const EmailConnectorMailBoxDrawerAttachmentActionMenu = require('../EmailConnectorMailBoxDrawerAttachmentActionMenu.vue').default;

const CONTRIBUTED = { name: 'contributed-action', props: ['attachment', 'context'], render: h => h('div') };

/**
 * Mounts the attachment menu on an attachment, with the real mail box service.
 *
 * @param {Object} attachment the attachment the menu is opened on
 * @returns {Object} the test-utils wrapper
 */
function mountMenu(attachment) {
  return shallowMount(EmailConnectorMailBoxDrawerAttachmentActionMenu, {
    propsData: { attachment },
    mocks: {
      $t: key => key,
      $emailConnectorMailBoxService: service,
    },
    stubs: { 'v-menu': { render(h) { return h('div', this.$slots.default); } }, 'v-list': { render(h) { return h('div', this.$slots.default); } } },
  });
}

describe('EmailConnectorMailBoxDrawerAttachmentActionMenu context', () => {
  let uploads;

  beforeAll(() => {
    global.extensionRegistry.registerExtension('emailConnector', 'mail-attachment-action', {
      id: 'contributed',
      rank: 100,
      vueComponent: CONTRIBUTED,
      rows: () => 8,
    });
  });

  beforeEach(() => {
    documentsDeployed = true;
    uploads = 0;
    Vue.prototype.$uploadService = {
      generateRandomId: () => 'upload-id',
      upload: () => {
        uploads++;
        return Promise.resolve();
      },
    };
    global.fetch = jest.fn(url => {
      if (url.includes('/uploadFile/control')) {
        return Promise.resolve({ ok: true, text: () => Promise.resolve('<file UUID="doc-42"/>') });
      }
      return Promise.resolve({ ok: true, blob: () => Promise.resolve(new Blob(['x'])) });
    });
  });

  it('hands a contributed component the row context, which stores the attachment and resolves its document id', async () => {
    const attachment = { mailRemoteId: 7, attachmentRemoteId: 'a1', name: 'report.pdf' };
    const wrapper = mountMenu(attachment);
    const contributed = wrapper.findComponent(CONTRIBUTED);
    expect(contributed.exists()).toBe(true);
    expect(contributed.props('attachment')).toBe(attachment);
    const context = contributed.props('context');
    expect(typeof context.storeInMailAttachments).toBe('function');
    await expect(context.storeInMailAttachments()).resolves.toBe('doc-42');
    expect(uploads).toBe(1);
  });

  it('does not store an attachment a second time once it is stored', async () => {
    const attachment = { mailRemoteId: 8, attachmentRemoteId: 'a2', name: 'slides.pptx' };
    const context = mountMenu(attachment).vm.buildContext();
    await context.storeInMailAttachments();
    // the same copy opening the attachment made, not a second one
    await expect(service.materialiseAttachment(attachment)).resolves.toBe('doc-42');
    await context.storeInMailAttachments();
    expect(uploads).toBe(1);
  });

  it('offers no store capability when the Documents add-on is not installed', () => {
    documentsDeployed = false;
    const context = mountMenu({ mailRemoteId: 9, attachmentRemoteId: 'a3', name: 'x.pdf' }).vm.buildContext();
    expect(context.storeInMailAttachments).toBeNull();
  });

  it('counts every row a contributed component renders when choosing which way to open', () => {
    const wrapper = mountMenu({ mailRemoteId: 10, attachmentRemoteId: 'a4', name: 'y.pdf' });
    const rows = wrapper.vm.actions.reduce((count, action) => count + wrapper.vm.rowsOf(action), 0);
    // the built-in actions applicable to a pdf (download, save, forward) + the 8 contributed rows
    expect(rows).toBe(11);
    window.innerHeight = 800;
    // room for four one-row actions below the button, not for eleven rows
    const button = { getBoundingClientRect: () => ({ bottom: 800 - (40 * 4 + 16) - 1 }) };
    wrapper.vm.chooseDirection({ currentTarget: button });
    expect(wrapper.vm.openUpward).toBe(true);
  });

  it('counts one row for an action that declares none, or a nonsensical count', () => {
    const wrapper = mountMenu({ mailRemoteId: 11, attachmentRemoteId: 'a5', name: 'z.pdf' });
    expect(wrapper.vm.rowsOf({ id: 'plain' })).toBe(1);
    expect(wrapper.vm.rowsOf({ id: 'broken', rows: () => 'many' })).toBe(1);
  });
});
