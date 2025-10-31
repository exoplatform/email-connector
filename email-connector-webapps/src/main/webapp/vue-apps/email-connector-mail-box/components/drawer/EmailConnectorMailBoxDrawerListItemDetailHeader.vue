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
  <v-list class="pt-0 pb-8">
    <v-divider class="pb-3" />
    <email-connector-mail-box-drawer-list-item-detail-header-item
      v-for="(item, index) in labelItems"
      :key="index"
      class="pb-3"
      :label="item.label"
      :values="item.values"
      :label-width="maxLabelWidth" />
    <v-divider />
  </v-list>
</template>
<script>
export default {
  data() {
    return {
      labelItems: [],
      maxLabelWidth: 0
    };
  },
  props: {
    email: {
      type: Object,
      default: () => null,
    },
  },
  created() {
    this.labelItems = [
      this.email.sender
        ? { label: this.$t('emailConnector.mailBox.list.drawer.detail.from'), values: [this.email.sender] }
        : null,
      this.email.to && this.email.to.length
        ? { label: this.$t('emailConnector.mailBox.list.drawer.detail.to'), values: this.email.to }
        : null,
      this.email.cc && this.email.cc.length
        ? { label: this.$t('emailConnector.mailBox.list.drawer.detail.cc'), values: this.email.cc }
        : null,
      this.email.bcc && this.email.bcc.length
        ? { label: this.$t('emailConnector.mailBox.list.drawer.detail.bcc'), values: this.email.bcc }
        : null,
    ].filter(Boolean);
    this.maxLabelWidth = Math.ceil(Math.max(...this.labelItems.map(i => this.getTextWidth(i.label))));
  },
  methods: {
    getTextWidth(text, font = '14px Roboto') {
      if (typeof document !== 'undefined') {
        const ctx = document.createElement('canvas').getContext('2d');
        ctx.font = font;
        return ctx.measureText(text).width;
      }
      return text.length * 8;
    }
  }
};
</script>