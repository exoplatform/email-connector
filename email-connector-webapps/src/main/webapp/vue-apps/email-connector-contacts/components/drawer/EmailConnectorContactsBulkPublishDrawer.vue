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
  <!-- The review step, and the only checkpoint there is: publishing writes
       cards to a server we do not own, and deleting them again is deliberately
       not built. So nothing here is pre-ticked, "select all" is a deliberate
       act rather than the default, and the footer counts what is about to
       leave the platform.

       Collected and directory contacts are not offered at all. A collected row
       is a by-product of mail traffic that the user never chose to keep, and a
       directory colleague already lives in the platform; pushing either into
       somebody's personal address book is a privacy event, not a convenience.
       That exclusion is the server's rule too -- this list only avoids
       offering ticks the server would refuse. -->
  <exo-drawer
    id="emailContactsBulkPublishDrawer"
    ref="bulkPublishDrawer"
    v-model="drawer"
    right
    @closed="reset">
    <template #title>
      <span>{{ $t('emailConnector.contacts.bulkPublish.title') }}</span>
    </template>
    <template #content>
      <div class="pa-4">
        <div class="text-subtitle-color mb-3">
          {{ $t('emailConnector.contacts.bulkPublish.hint') }}
        </div>
        <div
          v-if="!loading && !candidates.length"
          class="text-center text-subtitle-color py-6">
          {{ $t('emailConnector.contacts.bulkPublish.empty') }}
        </div>
        <v-list v-else dense>
          <v-list-item
            v-if="candidates.length > 1"
            class="px-0">
            <v-checkbox
              v-model="allSelected"
              :label="$t('emailConnector.contacts.bulkPublish.selectAll')"
              class="mt-0 pt-0"
              hide-details
              dense
              @change="toggleAll" />
          </v-list-item>
          <v-divider v-if="candidates.length > 1" class="mb-2" />
          <v-list-item
            v-for="contact in candidates"
            :key="contact.id"
            class="px-0">
            <v-checkbox
              v-model="selected"
              :value="contact.id"
              class="mt-0 pt-0"
              hide-details
              dense>
              <template #label>
                <div class="d-flex flex-column">
                  <span>{{ contact.fullName || contact.primaryAddress }}</span>
                  <span class="caption text-subtitle-color">{{ contact.primaryAddress }}</span>
                </div>
              </template>
            </v-checkbox>
          </v-list-item>
        </v-list>
      </div>
    </template>
    <template #footer>
      <div class="d-flex align-center justify-end">
        <!-- The count is the point of the footer: "Publish" alone hides how
             many cards are about to be created on the server. -->
        <span
          v-if="selected.length"
          class="text-subtitle-color me-auto">
          {{ $t('emailConnector.contacts.bulkPublish.selectedCount', [selected.length]) }}
        </span>
        <v-btn
          class="btn me-2"
          @click="close">
          {{ $t('emailConnector.contacts.form.cancel') }}
        </v-btn>
        <v-btn
          :disabled="!selected.length || publishing"
          :loading="publishing"
          class="btn btn-primary"
          @click="publish">
          {{ $t('emailConnector.contacts.bulkPublish.confirm') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    drawer: false,
    loading: false,
    publishing: false,
    candidates: [],
    selected: [],
    allSelected: false,
  }),
  created() {
    this.$root.$on('email-contacts-bulk-publish', this.open);
  },
  beforeDestroy() {
    this.$root.$off('email-contacts-bulk-publish', this.open);
  },
  methods: {
    /**
     * Opens the checklist on a freshly read list of publishable contacts.
     *
     * @returns {void}
     */
    open() {
      this.selected = [];
      this.allSelected = false;
      this.loading = true;
      this.drawer = true;
      this.$nextTick(() => this.$refs.bulkPublishDrawer.open());
      this.$emailConnectorContactsService.getPublishCandidates()
        .then(candidates => this.candidates = candidates || [])
        .catch(() => document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: 'error',
          alertMessage: this.$t('emailConnector.contacts.bulkPublish.loadError'),
        }})))
        .finally(() => this.loading = false);
    },
    /**
     * Ticks or clears every row at once.
     *
     * @param {Boolean} value whether everything should be selected
     * @returns {void}
     */
    toggleAll(value) {
      this.selected = value ? this.candidates.map(contact => contact.id) : [];
    },
    /**
     * Queues the reviewed selection, then closes.
     * <p>
     * The queue is what answers, not the server: a publish that cannot go out
     * now is held rather than lost, so the message says how many were accepted
     * for publishing rather than claiming they are already there.
     *
     * @returns {void}
     */
    publish() {
      this.publishing = true;
      this.$emailConnectorContactsService.queuePublishes(this.selected)
        .then(() => {
          this.$root.$emit('email-contacts-refresh');
          document.dispatchEvent(new CustomEvent('alert-message', {detail: {
            alertType: 'success',
            alertMessage: this.$t('emailConnector.contacts.bulkPublish.queued', [this.selected.length]),
          }}));
          this.close();
        })
        .catch(() => document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: 'error',
          alertMessage: this.$t('emailConnector.contacts.bulkPublish.error'),
        }})))
        .finally(() => this.publishing = false);
    },
    /**
     * Closes the drawer.
     *
     * @returns {void}
     */
    close() {
      this.$refs.bulkPublishDrawer.close();
    },
    /**
     * Drops the reviewed selection when the drawer closes, so reopening never
     * inherits ticks the user cannot see.
     *
     * @returns {void}
     */
    reset() {
      this.drawer = false;
      this.candidates = [];
      this.selected = [];
      this.allSelected = false;
    },
  },
};
</script>
