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
  <!-- position-relative because the menu is attached to this wrapper (attach) — it
       has to render inside the teleported drawer or it comes out empty. Which way it
       opens is decided per click by openUpward, so a row near the bottom opens above
       and a row near the top opens below. -->
  <div v-if="actions.length" class="flex-shrink-0 position-relative">
    <v-menu
      min-width="220"
      content-class="border-radius z-index-modal overflow-hidden"
      close-on-content-click
      offset-y
      left
      attach
      :top="openUpward"
      :bottom="!openUpward"
      @input="refreshActions">
      <template #activator="{ on, attrs }">
        <v-btn
          v-bind="attrs"
          icon
          small
          :title="$t('emailConnector.mailBox.attachment.actions.tooltip')"
          :aria-label="$t('emailConnector.mailBox.attachment.actions.tooltip')"
          v-on="on"
          @click.stop.prevent="chooseDirection"
          @keydown.enter.stop="chooseDirection">
          <v-icon size="18" class="text-light-color">fa-ellipsis-v</v-icon>
        </v-btn>
      </template>
      <!-- The whole row opens the attachment on click, so nothing happening in the
           menu may reach it — including from a contributed component. -->
      <v-list class="pa-0" @click.native.stop>
        <template v-for="action in actions">
          <component
            :is="action.vueComponent"
            v-if="action.vueComponent"
            :key="action.id"
            :attachment="attachment" />
          <v-list-item
            v-else
            :key="action.id"
            class="ps-2 pe-4"
            dense
            @click.stop="execute(action)">
            <v-sheet
              class="d-flex flex-shrink-0"
              width="28"
              height="32">
              <v-icon class="icon-default-color mx-auto" size="16">
                {{ action.icon }}
              </v-icon>
            </v-sheet>
            <span class="text-no-wrap">{{ $t(action.labelKey) }}</span>
          </v-list-item>
        </template>
      </v-list>
    </v-menu>
  </div>
</template>

<script>
import { getAttachmentActions } from '../../js/EmailConnectorAttachmentActions.js';

export default {
  props: {
    attachment: {
      type: Object,
      default: () => null,
    },
  },
  data() {
    return {
      actions: [],
      openUpward: false,
    };
  },
  created() {
    this.refreshActions();
    document.addEventListener('extension-emailConnector-mail-attachment-action-updated', this.refreshActions);
  },
  beforeDestroy() {
    document.removeEventListener('extension-emailConnector-mail-attachment-action-updated', this.refreshActions);
  },
  methods: {
    /**
     * Decides which way the menu opens before it opens, run on the activator click.
     * An attached menu does not flip itself, so a row near the bottom of the viewport
     * (a mail whose attachments sit under a long body) opens above the button and a
     * row with room beneath (the attachments list drawer) opens below it.
     *
     * @param {Event} event the activator click/keydown event
     * @returns {void}
     */
    chooseDirection(event) {
      const button = event && event.currentTarget;
      const rect = button && button.getBoundingClientRect();
      // a rough menu height (per row) is enough to know whether it would overflow
      const estimatedHeight = 40 * this.actions.length + 16;
      this.openUpward = !!rect && (rect.bottom + estimatedHeight) > window.innerHeight;
    },
    /**
     * Recomputes what this attachment can be done with. Also done when the menu is
     * opened: an action can become applicable while the mail stays on screen, the
     * Documents add-on registering itself being the usual case.
     *
     * @returns {void}
     */
    refreshActions() {
      this.actions = getAttachmentActions(this.attachment);
    },
    /**
     * Runs an action, handing it what only the attachment row can do. Anything the
     * action throws or rejects is reported rather than left silent, since the menu
     * closes on click and would otherwise look like it worked.
     *
     * @param {Object} action the action descriptor to run
     * @returns {void}
     */
    execute(action) {
      const context = {
        download: () => this.$emit('download'),
        openInEditor: mode => this.$emit('open-in-editor', mode),
        saveInDocuments: attachments => this.$emailConnectorMailBoxService.saveAttachmentsInDocuments(attachments, {
          success: this.$t('emailConnector.mailBox.attachment.saveInDocuments.success'),
          error: this.$t('emailConnector.mailBox.attachment.action.error'),
          see: this.$t('emailConnector.mailBox.attachment.saveInDocuments.see'),
        }),
        addToContacts: () => this.$emailConnectorMailBoxService.addAttachmentToContacts(this.attachment, {
          notVCard: this.$t('emailConnector.mailBox.attachment.addToContacts.notVCard'),
          error: this.$t('emailConnector.mailBox.attachment.action.error'),
        }),
      };
      try {
        Promise.resolve(action.click(this.attachment, context)).catch(() => this.reportFailure());
      } catch (e) {
        this.reportFailure();
      }
    },
    /**
     * Tells the user the action did not go through.
     *
     * @returns {void}
     */
    reportFailure() {
      this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.attachment.action.error'), 'error');
    },
  },
};
</script>
