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
  <!-- The drawer header's overflow menu: the two whole-store actions, apart
       from the add button because neither is about one contact. Deliberately
       dumb — choosing a file and asking for the export are all it does; the
       drawer owns the upload, the poll and the report, so an import survives
       this menu unmounting when the drawer expands. -->
  <div class="d-inline-flex">
    <v-menu
      content-class="no-min-width border-radius z-index-modal overflow-hidden"
      close-on-content-click
      offset-y
      left
      bottom>
      <template #activator="{ on, attrs }">
        <v-btn
          v-bind="attrs"
          :title="$t('emailConnector.contacts.menu.tooltip')"
          icon
          v-on="on">
          <v-icon size="18">
            fas fa-ellipsis-v
          </v-icon>
        </v-btn>
      </template>
      <v-list dense>
        <v-list-item
          :disabled="importing"
          @click="chooseFile">
          <v-list-item-icon class="me-2 my-auto">
            <v-icon size="16">fas fa-file-import</v-icon>
          </v-list-item-icon>
          <v-list-item-title>{{ $t('emailConnector.contacts.menu.import') }}</v-list-item-title>
        </v-list-item>
        <v-list-item @click="$emit('export')">
          <v-list-item-icon class="me-2 my-auto">
            <v-icon size="16">fas fa-file-export</v-icon>
          </v-list-item-icon>
          <v-list-item-title>{{ $t('emailConnector.contacts.menu.export') }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-menu>
    <input
      ref="fileInput"
      type="file"
      accept=".vcf,text/vcard,text/x-vcard"
      :aria-label="$t('emailConnector.contacts.menu.import')"
      class="d-none"
      @change="onFileChosen">
  </div>
</template>

<script>
export default {
  props: {
    // Greys the import entry out while a run is going: the server would answer
    // 409 anyway, but a disabled item says so before the click.
    importing: {
      type: Boolean,
      default: false,
    },
  },
  methods: {
    /**
     * Opens the browser's file picker for a .vcf.
     *
     * @returns {void}
     */
    chooseFile() {
      this.$refs.fileInput.click();
    },
    /**
     * Hands the chosen file to the drawer and resets the input, so choosing
     * the same file twice — the natural way to retry — fires again.
     *
     * @param {Event} event - the input's change event
     * @returns {void}
     */
    onFileChosen(event) {
      const file = event.target.files && event.target.files[0];
      event.target.value = '';
      if (file) {
        this.$emit('import', file);
      }
    },
  },
};
</script>
