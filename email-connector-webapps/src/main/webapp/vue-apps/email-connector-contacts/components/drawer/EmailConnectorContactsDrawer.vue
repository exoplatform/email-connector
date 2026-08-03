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
  <!-- The Contacts drawer: the mailbox's shell shape — right drawer, built-in
       filter, expandable to the two-pane layout (list left, detail right). -->
  <exo-drawer
    id="emailContactsDrawer"
    ref="emailContactsDrawer"
    v-model="contactsDrawer"
    right
    allow-expand
    @expand-updated="updateExpand"
    :loading="loading"
    use-filter
    :filter-placeholder="$t('emailConnector.contacts.filter.placeholder')"
    @filter-updated="onFilterUpdated"
    @closed="close"
    class="no-box-shadow">
    <template #title>
      <span v-if="!hasFullAppLeft">
        {{ $t('emailConnector.contacts.drawer.title') }}
      </span>
      <span v-else></span>
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftTitle>
      <div class="d-flex align-center justify-space-between width-full">
        <span>{{ $t('emailConnector.contacts.drawer.title') }}</span>
        <v-btn
          :title="$t('emailConnector.contacts.add')"
          icon
          @click="openForm()">
          <v-icon size="18">
            fas fa-user-plus
          </v-icon>
        </v-btn>
      </div>
    </template>
    <template #titleIcons>
      <v-btn
        v-if="!hasFullAppLeft"
        :title="$t('emailConnector.contacts.add')"
        icon
        @click="openForm()">
        <v-icon size="18">
          fas fa-user-plus
        </v-icon>
      </v-btn>
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftContent>
      <email-connector-contacts-source-chips
        :source="source"
        :has-address-book="hasAddressBook"
        class="full-width border-box-sizing py-3 px-3"
        @update:source="switchSource" />
      <email-connector-contacts-list
        :contacts="displayedContacts"
        :letter-index="letterIndex"
        :rail-visible="railVisible"
        :selected-id="selectedContact?.id"
        :empty-label="emptyLabel"
        @select="selectContact" />
    </template>
    <template v-if="contactsDrawer" #content>
      <template v-if="!expanded">
        <email-connector-contacts-source-chips
          :source="source"
          :has-address-book="hasAddressBook"
          class="full-width border-box-sizing py-3 px-3"
          @update:source="switchSource" />
        <email-connector-contacts-list
          :contacts="displayedContacts"
          :letter-index="letterIndex"
          :rail-visible="railVisible"
          :empty-label="emptyLabel"
          @select="selectContact" />
      </template>
      <template v-else>
        <email-connector-contacts-detail
          v-if="selectedContact"
          :contact="selectedContact"
          @edit="openForm(selectedContact)"
          @removed="onContactRemoved" />
        <div
          v-else
          class="d-flex flex-column align-center justify-center full-height text-sub-title">
          <v-icon
            size="48"
            class="text-sub-title mb-4">
            fas fa-address-book
          </v-icon>
          <span>{{ $t('emailConnector.contacts.detail.selectPlaceholder') }}</span>
        </div>
      </template>
    </template>
  </exo-drawer>
</template>

<script>
const PAGE_SIZE = 200;
const MAX_ROWS = 5000;
const RAIL_MIN_CONTACTS = 100;

export default {
  data() {
    return {
      contactsDrawer: false,
      expanded: false,
      loading: false,
      // null = All (the local store), 'collected', 'addressBook', or the
      // pseudo-source 'colleagues' (the live platform directory, never stored).
      source: null,
      term: null,
      contacts: [],
      letterIndex: {},
      size: 0,
      colleagues: [],
      hasAddressBook: false,
      selectedContact: null,
      searchTimeout: null,
      // Increments on every reload; late pages of an outdated load are dropped.
      loadToken: 0,
    };
  },
  created() {
    this.$root.$on('open-email-contacts-drawer', this.open);
    this.$root.$on('email-contacts-refresh', this.reload);
  },
  beforeDestroy() {
    this.$root.$off('open-email-contacts-drawer', this.open);
    this.$root.$off('email-contacts-refresh', this.reload);
  },
  computed: {
    /**
     * Whether the wide two-pane layout is on (list left, detail right).
     *
     * @returns {boolean} true when expanded
     */
    hasFullAppLeft() {
      return this.expanded;
    },
    /**
     * The rows the list shows: the store, or the live directory on the
     * Colleagues chip.
     *
     * @returns {Array} the contacts to render
     */
    displayedContacts() {
      return this.source === 'colleagues' ? this.colleagues : this.contacts;
    },
    /**
     * Whether the A–Z rail earns its pixels: enough contacts that sticky
     * headers alone stop being a way to navigate. Colleagues are served by the
     * directory's own ordering, without the letter index, so no rail there.
     *
     * @returns {boolean} true when the rail should render
     */
    railVisible() {
      return this.source !== 'colleagues' && this.size >= RAIL_MIN_CONTACTS;
    },
    /**
     * The empty-state label of the current view.
     *
     * @returns {string} the localized label
     */
    emptyLabel() {
      return this.term ? this.$t('emailConnector.contacts.empty.filtered') : this.$t('emailConnector.contacts.empty');
    },
  },
  methods: {
    /**
     * Opens the drawer and loads the store (and the Address book chip's
     * presence probe) on the way in.
     *
     * @returns {void}
     */
    open() {
      this.contactsDrawer = true;
      this.$refs.emailContactsDrawer.open();
      this.reload();
      this.probeAddressBook();
    },
    /**
     * Resets the drawer's transient state on close.
     *
     * @returns {void}
     */
    close() {
      this.selectedContact = null;
      this.term = null;
      this.source = null;
      this.contactsDrawer = false;
    },
    /**
     * Tracks the expand toggle, the mailbox way (delayed so the drawer's own
     * transition finishes first).
     *
     * @param {boolean} expanded - the new expand state
     * @returns {void}
     */
    updateExpand(expanded) {
      window.setTimeout(() => this.expanded = expanded, 200);
    },
    /**
     * The drawer's built-in filter: debounced, then queried server-side so the
     * letter index and the list can never disagree.
     *
     * @param {string} term - the typed text
     * @returns {void}
     */
    onFilterUpdated(term) {
      window.clearTimeout(this.searchTimeout);
      this.searchTimeout = window.setTimeout(() => {
        this.term = term?.trim() || null;
        this.reload();
      }, 300);
    },
    /**
     * Switches the source chip and reloads that view.
     *
     * @param {string} source - null, 'collected', 'addressBook' or 'colleagues'
     * @returns {void}
     */
    switchSource(source) {
      this.source = source;
      this.selectedContact = null;
      this.reload();
    },
    /**
     * Reloads the current view: the store eagerly page by page (which keeps
     * the A–Z rail's jumps exact without offset bookkeeping client-side), or
     * the live directory for Colleagues.
     *
     * @returns {void}
     */
    reload() {
      this.loadToken++;
      if (this.source === 'colleagues') {
        this.loadColleagues();
      } else {
        this.loadContacts();
      }
    },
    /**
     * Loads the whole filtered store, sequential pages of {@link PAGE_SIZE},
     * bounded by {@link MAX_ROWS}. The first page renders immediately; the
     * rest stream in behind it.
     *
     * @returns {Promise<void>} resolves when every page landed
     */
    async loadContacts() {
      const token = this.loadToken;
      this.loading = true;
      try {
        const firstPage = await this.$emailConnectorContactsService.getContacts(this.source, this.term, 0, PAGE_SIZE);
        if (token !== this.loadToken) {
          return;
        }
        this.contacts = firstPage.contacts || [];
        this.letterIndex = firstPage.letterIndex || {};
        this.size = firstPage.size || 0;
        await this.loadRemainingPages(token, PAGE_SIZE, Math.min(this.size, MAX_ROWS));
      } finally {
        if (token === this.loadToken) {
          this.loading = false;
        }
      }
    },
    /**
     * Streams the pages after the first one in, one after the other, stopping
     * the moment a newer reload superseded this one.
     *
     * @param {number} token - the load this chain belongs to
     * @param {number} offset - the next page's offset
     * @param {number} total - how many rows to load in all
     * @returns {Promise<void>} resolves when the last page landed
     */
    loadRemainingPages(token, offset, total) {
      if (offset >= total || token !== this.loadToken) {
        return Promise.resolve();
      }
      return this.$emailConnectorContactsService.getContacts(this.source, this.term, offset, PAGE_SIZE)
        .then(page => {
          if (token !== this.loadToken) {
            return;
          }
          this.contacts = this.contacts.concat(page.contacts || []);
          return this.loadRemainingPages(token, offset + PAGE_SIZE, total);
        });
    },
    /**
     * Loads the Colleagues view straight from the platform's people directory
     * — queried live with the platform's own ACLs, never copied into the
     * store — and maps each user onto the contact shape the list renders.
     *
     * @returns {Promise<void>} resolves when the directory answered
     */
    async loadColleagues() {
      const token = this.loadToken;
      this.loading = true;
      try {
        const data = await this.$emailConnectorContactsService.getColleagues(this.term, 0, PAGE_SIZE);
        if (token !== this.loadToken) {
          return;
        }
        this.colleagues = (data?.users || []).map(user => ({
          colleague: true,
          displayName: user.fullname,
          primaryEmail: user.email,
          avatarUrl: user.avatar,
          profileUrl: user.username && `/portal/dw/profile/${user.username}`,
          position: user.position,
        }));
      } finally {
        if (token === this.loadToken) {
          this.loading = false;
        }
      }
    },
    /**
     * Probes once whether curated rows exist, which is what makes the Address
     * book chip appear before phase 3 fills it (manual contacts count).
     *
     * @returns {void}
     */
    probeAddressBook() {
      this.$emailConnectorContactsService.getContacts('addressBook', null, 0, 1)
        .then(page => this.hasAddressBook = (page?.size || 0) > 0);
    },
    /**
     * Opens a contact: inline in the right pane when expanded, in the sibling
     * detail drawer otherwise. Stored rows are re-read so the detail carries
     * the read-time profile enrichment (avatar, profile link).
     *
     * @param {object} contact - the selected row
     * @returns {void}
     */
    selectContact(contact) {
      if (this.expanded) {
        this.selectedContact = contact;
        this.enrich(contact).then(full => this.selectedContact = full);
      } else {
        this.$root.$emit('open-email-contact-detail', contact);
      }
    },
    /**
     * Re-reads a stored contact for its profile enrichment; colleagues are
     * already what the directory said.
     *
     * @param {object} contact - the selected row
     * @returns {Promise<object>} the contact to show
     */
    enrich(contact) {
      return contact.id ? this.$emailConnectorContactsService.getContact(contact.id).catch(() => contact)
        : Promise.resolve(contact);
    },
    /**
     * Opens the add/edit form drawer.
     *
     * @param {object} contact - the contact to edit, or nothing to add
     * @returns {void}
     */
    openForm(contact) {
      this.$root.$emit('open-email-contact-form', contact);
    },
    /**
     * Clears the inline detail after its contact was deleted or suppressed,
     * then reloads the list.
     *
     * @returns {void}
     */
    onContactRemoved() {
      this.selectedContact = null;
      this.reload();
    },
  }
};
</script>
