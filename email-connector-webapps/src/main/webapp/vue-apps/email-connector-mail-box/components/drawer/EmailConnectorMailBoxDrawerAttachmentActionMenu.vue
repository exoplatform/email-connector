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
  <!-- position-relative: the menu is attached to this wrapper, which then has to be
       the element its content is placed against. -->
  <div v-if="actions.length" class="flex-shrink-0 position-relative">
    <v-menu
      :nudge-top="-1"
      content-class="no-min-width border-radius z-index-modal overflow-hidden"
      close-on-content-click
      offset-y
      left
      bottom
      attach
      @input="refreshActions">
      <template #activator="{ on, attrs }">
        <v-btn
          v-bind="attrs"
          icon
          small
          :title="$t('emailConnector.mailBox.attachment.actions.tooltip')"
          :aria-label="$t('emailConnector.mailBox.attachment.actions.tooltip')"
          v-on="on"
          @click.stop.prevent
          @keydown.enter.stop>
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
            class="ps-2 pe-3 height-auto"
            @click.stop="execute(action)">
            <v-sheet
              class="d-flex"
              width="28"
              height="36">
              <v-icon class="icon-default-color mx-auto" size="16">
                {{ action.icon }}
              </v-icon>
            </v-sheet>
            <span>{{ $t(action.labelKey) }}</span>
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
