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
  <!-- Storing the whole set belongs to the mail, not to each of its attachments: a
       mail carrying eight files must not mean eight trips through the row menu. -->
  <v-btn
    v-if="available"
    text
    small
    color="primary"
    class="px-2 text-none"
    :loading="saving"
    :title="label"
    :aria-label="label"
    @click.stop="saveAll">
    <v-icon size="16" class="pe-1">fa-hdd</v-icon>
    {{ label }}
  </v-btn>
</template>

<script>
export default {
  props: {
    emailAttachments: {
      type: Array,
      default: () => [],
    },
  },
  data() {
    return {
      saving: false,
    };
  },
  computed: {
    available() {
      return this.emailAttachments.length > 0 && this.$emailConnectorMailBoxService.isDocumentsDeployed();
    },
    label() {
      return this.$t('emailConnector.mailBox.attachments.saveAll.label');
    },
  },
  methods: {
    /**
     * Reads every attachment of the mail and hands the whole set to the Documents
     * drawer, where the user picks the folder they all go in.
     *
     * @returns {void}
     */
    saveAll() {
      if (this.saving) {
        return;
      }
      this.saving = true;
      this.$emailConnectorMailBoxService.saveAttachmentsInDocuments(this.emailAttachments)
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.attachment.action.error'), 'error'))
        .finally(() => this.saving = false);
    },
  },
};
</script>
