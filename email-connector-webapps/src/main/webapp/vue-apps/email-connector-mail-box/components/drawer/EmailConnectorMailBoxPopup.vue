<!--
Copyright (C) 2026 eXo Platform SAS.

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
  <!-- The platform's standard popup, as exo-confirm-dialog draws it -- a 1:1 copy of
       Meeds-io/social webapp/src/main/webapp/vue-apps/common/components/ConfirmDialog.vue
       (dialog, content-class, transparent card, header, centred primary and plain
       buttons, the branding layout switch, the modalOpened / modalClosed events the
       drawers' overlay steps aside for) -- with two differences, and only those:
       - a default slot in place of the v-html message, which is what a form such as
         the schedule picker needs (EXO-90434: Reschedule) and exo-confirm-dialog cannot
         hold;
       - OK does not close it: the caller closes it once the work is done, so a refusal
         leaves it open, saying why, on what was picked. -->
  <v-dialog
    ref="dialog"
    v-model="dialog"
    :persistent="persistent"
    :width="width"
    :content-class="`uiPopup ${isBrandingLayout && 'layout-drawer' || ''}`"
    max-width="100vw">
    <v-card class="elevation-12 transparent">
      <div class="ignore-vuetify-classes popupHeader ClearFix" :class="isBrandingLayout && 'layout-drawer' || ''">
        <a
          class="uiIconClose pull-right"
          aria-hidden="true"
          @click="close"></a>
        <span class="ignore-vuetify-classes text-title">{{ title }}</span>
      </div>
      <v-card-text>
        <slot></slot>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <button
          v-if="okLabel"
          :disabled="loading || okDisabled"
          class="ignore-vuetify-classes btn btn-primary me-2 popup-ok"
          @click="ok">
          {{ okLabel }}
        </button>
        <button
          v-if="cancelLabel"
          :disabled="loading"
          class="ignore-vuetify-classes btn ms-2 popup-cancel"
          @click="close">
          {{ cancelLabel }}
        </button>
        <v-spacer />
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script>
export default {
  props: {
    loading: {
      type: Boolean,
      default: false,
    },
    persistent: {
      type: Boolean,
      default: false,
    },
    title: {
      type: String,
      default: null,
    },
    okLabel: {
      type: String,
      default: null,
    },
    // Whether OK may not be clicked yet: what the slot holds is not valid.
    okDisabled: {
      type: Boolean,
      default: false,
    },
    cancelLabel: {
      type: String,
      default: null,
    },
    width: {
      type: String,
      default: '400px',
    },
    isBrandingLayout: {
      type: Boolean,
      default: true,
    },
  },
  data: () => ({
    dialog: false,
    closed: false,
  }),
  watch: {
    dialog() {
      if (this.dialog) {
        this.closed = false;
        this.$emit('dialog-opened');
        document.dispatchEvent(new CustomEvent('modalOpened'));
      } else {
        this.emitClosedEvent();
      }
    },
  },
  methods: {
    /**
     * Opens the popup.
     *
     * @returns {void}
     */
    open() {
      this.dialog = true;
      this.$emit('opened');
    },
    /**
     * Says OK was clicked. The popup stays open: the caller closes it once done.
     *
     * @param {Event} event the click
     * @returns {void}
     */
    ok(event) {
      if (event) {
        event.preventDefault();
        event.stopPropagation();
      }
      this.$emit('ok');
    },
    /**
     * Closes the popup, from its close icon, its Cancel, or the caller.
     *
     * @param {Event} event the click, if any
     * @returns {void}
     */
    close(event) {
      if (event) {
        event.preventDefault();
        event.stopPropagation();
      }
      this.$emit('closed');
      this.$nextTick(() => {
        this.dialog = false;
        this.emitClosedEvent();
      });
    },
    /**
     * Tells the caller and the platform (the drawers' overlay) that the popup closed,
     * once.
     *
     * @returns {void}
     */
    emitClosedEvent() {
      if (!this.closed && !this.dialog) {
        this.closed = true;
        this.$emit('dialog-closed');
        document.dispatchEvent(new CustomEvent('modalClosed'));
      }
    },
  },
};
</script>
