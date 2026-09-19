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
  <!-- The two first attachments, then a counter of the others: a pill that grows with
       its number and never shrinks, the attachment chips giving way to it instead
       (min-width 0 on the row, see the list item) so a two-digit count is never clipped
       by the row's edge. Past 99 it says +99; its title and accessible name keep the
       exact count. -->
  <div
    class="mt-2 d-flex align-center"
    style="min-width: 0;">
    <email-connector-mail-box-drawer-list-item-attachments-list-item
      v-for="attachment in emailAttachmentsList"
      :key="attachment.id"
      :attachment="attachment" />
    <v-chip
      v-if="hasMoreAttachments"
      :title="moreAttachmentsTitle"
      :aria-label="moreAttachmentsTitle"
      style="min-width: 24px; height: 24px;"
      class="px-2 flex-shrink-0 rounded-pill d-flex align-center justify-center text-subtitle font-weight-bold"
      text-color="white"
      color="#707070"
      @click="openAttachmentsDrawer">
      {{ attachmentsLabel }}
    </v-chip>
  </div>
</template>

<script>
// Past this the counter says +99, as the folder column's counts say 99+.
const MORE_ATTACHMENTS_DISPLAY_MAX = 99;

export default {
  props: {
    emailAttachments: {
      type: Array,
      default: () => [],
    },
  },
  computed: {
    hasMoreAttachments() {
      return this.emailAttachments.length > 2;
    },
    /**
     * How many attachments the counter stands for.
     *
     * @returns {Number} the attachments beyond the two shown
     */
    moreAttachments() {
      return this.emailAttachments.length - 2;
    },
    /**
     * The counter: "+N", capped at "+99" like the folder column's counts ("99+").
     *
     * @returns {String} the label
     */
    attachmentsLabel() {
      return `+${Math.min(this.moreAttachments, MORE_ATTACHMENTS_DISPLAY_MAX)}`;
    },
    /**
     * The counter's title and accessible name, with the exact count.
     *
     * @returns {String} the label
     */
    moreAttachmentsTitle() {
      return this.$t('emailConnector.mailBox.attachments.more', { 0: this.moreAttachments });
    },
    emailAttachmentsList() {
      return this.emailAttachments.slice(0, 2);
    },
  },
  methods: {
    openAttachmentsDrawer() {
      this.$root.$emit('open-email-attachments-drawer', this.emailAttachments);
    },
  }
};
</script>