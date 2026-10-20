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
  <!-- Archive and Delete offered on a row the pointer rests on (EXO-91677), in place
       of its date and before its menu. At the star's size, as quiet as the date they
       stand in for, not the reader toolbar's weight: Archive light grey, Delete red as
       in the row's menu. A button whose action the row does not offer keeps its box,
       hidden, so the pair always takes the same width; the row decides whether the
       pair shows at all. Enter and Space stop here, as on the menu's button, so the key
       acts on the button and does not also open the mail. -->
  <div class="d-flex align-center flex-shrink-0">
    <v-btn
      :style="slotStyle(canArchive)"
      :title="$t('emailConnector.mailBox.list.drawer.detail.archive.label')"
      :aria-label="$t('emailConnector.mailBox.list.drawer.detail.archive.label')"
      :tabindex="canArchive ? 0 : -1"
      class="pa-0"
      width="26"
      min-width="26"
      height="26"
      icon
      @click.stop.prevent="$emit('archive')"
      @keydown.enter.stop
      @keydown.space.stop>
      <v-icon size="16" class="text-light-color">
        fa-archive
      </v-icon>
    </v-btn>
    <v-btn
      :style="slotStyle(canDelete)"
      :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
      :aria-label="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
      :tabindex="canDelete ? 0 : -1"
      class="pa-0"
      width="26"
      min-width="26"
      height="26"
      icon
      @click.stop.prevent="$emit('delete')"
      @keydown.enter.stop
      @keydown.space.stop>
      <v-icon size="16" class="error--text">
        fa-trash
      </v-icon>
    </v-btn>
  </div>
</template>

<script>
export default {
  props: {
    // Whether Archive may be offered on the row: its folder's answer, the one the row's
    // menu and its swipe read (canArchive).
    canArchive: {
      type: Boolean,
      default: false,
    },
    // Whether Delete may be offered on the row (canDelete), same source as canArchive.
    canDelete: {
      type: Boolean,
      default: false,
    },
  },
  methods: {
    /**
     * The style of one button's slot: hidden but kept in the layout when the action is
     * not offered on the row.
     *
     * @param {Boolean} offered whether the button's action is offered
     * @returns {Object} the style
     */
    slotStyle(offered) {
      return offered ? {} : { visibility: 'hidden', pointerEvents: 'none' };
    },
  },
};
</script>
