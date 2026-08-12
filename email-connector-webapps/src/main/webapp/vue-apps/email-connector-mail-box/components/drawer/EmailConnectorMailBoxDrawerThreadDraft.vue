<!--
Copyright (C) 2025 eXo Platform SAS.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
-->
<template>
  <!-- A reply in progress, at the bottom of the conversation it answers.

       Deliberately NOT a thread message: no avatar, no expand chrome, no date on
       the right. Those all say "this happened"; a draft has not happened. What it
       gets instead is a marker, who it is addressed to, and the first line of what
       has been written so far — enough to recognise which unfinished reply this is
       without pretending it is mail. -->
  <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
  <div
    class="clickable d-flex align-center px-3 py-3 rounded ec-thread-draft"
    style="border: 1px dashed var(--v-borderColor, #e1e8ee);"
    tabindex="0"
    :aria-label="$t('emailConnector.mailBox.list.drawer.thread.draft.resume')"
    :title="$t('emailConnector.mailBox.list.drawer.thread.draft.resume')"
    @click="$emit('resume')"
    @keydown.enter="$emit('resume')"
    @keydown.space.prevent="$emit('resume')">
    <span class="error--text font-weight-bold flex-shrink-0 me-3">
      {{ $t('emailConnector.mailBox.list.drawer.thread.draft.label') }}
    </span>
    <div class="d-flex align-center no-min-width flex-grow-1">
      <span class="flex-shrink-0 text-truncate" style="max-width: 45%">{{ recipientsLabel }}</span>
      <span class="text-light-color ms-3 text-truncate">{{ snippet }}</span>
    </div>
    <v-btn
      icon
      small
      class="ms-2 flex-shrink-0"
      :aria-label="$t('emailConnector.mailBox.list.drawer.thread.draft.discard')"
      :title="$t('emailConnector.mailBox.list.drawer.thread.draft.discard')"
      @click.stop="$emit('discard')">
      <v-icon size="14" class="icon-default-color">fas fa-trash</v-icon>
    </v-btn>
  </div>
</template>

<script>
export default {
  props: {
    // The draft row, as the conversation query returned it.
    draft: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    /**
     * Who the draft is addressed to so far, or a placeholder while it is addressed
     * to nobody — which is a perfectly ordinary state for a draft to be in and must
     * not render as an empty gap.
     *
     * @returns {string} the recipients line
     */
    recipientsLabel() {
      const names = (this.draft?.to || [])
        .map(recipient => recipient.name || recipient.address)
        .filter(Boolean);
      return names.length
        ? this.$t('emailConnector.mailBox.list.drawer.thread.draft.to', {0: names.join(', ')})
        : this.$t('emailConnector.mailBox.list.drawer.thread.draft.noRecipient');
    },
    /**
     * The first line of what has been written, stripped of markup — the same
     * treatment a collapsed thread message gives its body.
     *
     * @returns {string} the one-line preview
     */
    snippet() {
      const body = this.draft?.content?.body || '';
      if (typeof DOMParser === 'undefined') {
        return '';
      }
      const text = new DOMParser().parseFromString(body, 'text/html').body?.textContent || '';
      return text.replace(/\s+/g, ' ').trim();
    },
  },
};
</script>
