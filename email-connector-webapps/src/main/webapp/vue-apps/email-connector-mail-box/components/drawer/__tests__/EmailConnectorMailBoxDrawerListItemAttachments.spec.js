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

// EXO-90415 (PO) — a list row's counter of the attachments beyond the two it shows
// was a fixed 24 px circle reading "+ 62", clipped at the row's edge past one digit.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawerListItemAttachments from '../EmailConnectorMailBoxDrawerListItemAttachments.vue';
import EmailConnectorMailBoxDrawerListItemAttachmentsListItem from '../EmailConnectorMailBoxDrawerListItemAttachmentsListItem.vue';

/**
 * Mounts the row's attachments with a number of them.
 *
 * @param {Number} count how many attachments the mail has
 * @returns {Object} the wrapper
 */
function mountWith(count) {
  const emailAttachments = Array.from({ length: count }, (value, index) => ({ id: index + 1, name: `file-${index + 1}.pdf` }));
  return shallowMount(EmailConnectorMailBoxDrawerListItemAttachments, {
    propsData: { emailAttachments },
    mocks: { $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key) },
  });
}

describe('the attachment counter of a list row (EXO-90415)', () => {
  it.each([[4, '+2', '2'], [64, '+62', '62'], [152, '+99', '150']])(
    '%i attachments read %s, the exact count in its title and name', (count, label, exact) => {
      const counter = mountWith(count).find('v-chip');
      expect(counter.text()).toBe(label);
      expect(counter.attributes('title')).toBe(`emailConnector.mailBox.attachments.more|${exact}`);
      expect(counter.attributes('aria-label')).toBe(counter.attributes('title'));
    });

  it('is a pill that grows with its number and never shrinks, not a fixed circle', () => {
    const counter = mountWith(64).find('v-chip');
    expect(counter.classes()).toEqual(expect.arrayContaining(['rounded-pill', 'flex-shrink-0', 'px-2']));
    expect(counter.attributes('style')).toContain('min-width: 24px');
    expect(counter.attributes('style')).not.toMatch(/(^|[^-])width: 24px/);
  });

  it('lets the attachment chips give way to it inside the row', () => {
    const wrapper = mountWith(64);
    expect(wrapper.attributes('style')).toContain('min-width: 0');
    const chip = shallowMount(EmailConnectorMailBoxDrawerListItemAttachmentsListItem, {
      propsData: { attachment: { id: 1, name: 'a.pdf' } },
      mocks: { $t: key => key, $emailConnectorMailBoxService: {} },
    }).find('v-chip');
    expect(chip.attributes('style')).toContain('min-width: 0');
  });

  it('shows no counter for two attachments or fewer', () => {
    expect(mountWith(2).find('v-chip').exists()).toBe(false);
  });
});
