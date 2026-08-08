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
       filter, expandable to the two-pane layout (list left, detail right).
       Every row shown here is the user's own store; the platform directory is
       reached through the "Add from directory" picker, never browsed as a
       list, so the A–Z rail and letter index apply to every view. -->
  <exo-drawer
    id="emailContactsDrawer"
    ref="emailContactsDrawer"
    v-model="contactsDrawer"
    right
    :loading="loading"
    use-filter
    :filter-placeholder="$t('emailConnector.contacts.filter.placeholder')"
    @filter-updated="onFilterUpdated"
    @closed="close"
    class="no-box-shadow">
    <template #title>
      <!-- The count follows whatever is filtered, so it doubles as the answer to
           "did my search find anything". It is also the cheapest possible alarm:
           an address book sync that quietly removed everything shows up here the
           moment the drawer opens, instead of in a log line nobody reads. -->
      <span v-if="!hasFullAppLeft">
        {{ $t('emailConnector.contacts.drawer.title') }}
        <span
          v-if="size"
          class="text-sub-title ms-1">· {{ size }}</span>
      </span>
      <span v-else></span>
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftTitle>
      <div class="d-flex align-center justify-space-between width-full">
        <span>{{ $t('emailConnector.contacts.drawer.title') }}</span>
        <div>
          <v-btn
            :title="$t('emailConnector.contacts.add')"
            icon
            @click="openForm()">
            <v-icon size="18">
              fas fa-user-plus
            </v-icon>
          </v-btn>
        </div>
      </div>
    </template>
    <template #titleIcons>
      <div v-if="!hasFullAppLeft">
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
    <template v-if="hasFullAppLeft" #fullAppLeftContent>
      <email-connector-contacts-source-filter
        v-model="filterChips"
        :counts="sourceCounts" />
      <email-connector-contacts-list
        :contacts="contacts"
        :letter-index="letterIndex"
        :rail-visible="railVisible"
        :selected-id="selectedContact?.id"
        :empty-label="emptyLabel"
        @select="selectContact" />
    </template>
    <template v-if="contactsDrawer" #content>
      <template v-if="!expanded">
        <email-connector-contacts-source-filter
          v-model="filterChips"
          :counts="sourceCounts" />
        <email-connector-contacts-list
          :contacts="contacts"
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
const SOURCES = ['collected', 'manual', 'addressBook'];
const MAX_ROWS = 5000;
const RAIL_MIN_CONTACTS = 100;

export default {
  data() {
    return {
      contactsDrawer: false,
      expanded: false,
      loading: false,
      term: null,
      // The chip bar's raw value: the selected source values plus, possibly,
      // 'favorites' — which is a restriction, not a source, so the computeds
      // below split it back out before anything reaches the REST.
      filterChips: [],
      sourceCounts: {},
      contacts: [],
      letterIndex: {},
      size: 0,
      selectedContact: null,
      searchTimeout: null,
      // Increments on every reload; late pages of an outdated load are dropped.
      loadToken: 0,
    };
  },
  watch: {
    filterChips() {
      this.reload();
    },
  },
  created() {
    this.$root.$on('open-email-contacts-drawer', this.open);
    this.$root.$on('email-contacts-refresh', this.refresh);
    // The shared favorite button announces toggles on the document. Re-counting
    // is what makes the Favorites chip appear at the first star and disappear at
    // the last; reloading is what repaints the list's stars and, when the chip is
    // on, drops the row that just lost its own.
    document.addEventListener('favorite-added', this.onFavoriteToggled);
    document.addEventListener('favorite-removed', this.onFavoriteToggled);
  },
  beforeDestroy() {
    this.$root.$off('open-email-contacts-drawer', this.open);
    this.$root.$off('email-contacts-refresh', this.reload);
    document.removeEventListener('favorite-added', this.onFavoriteToggled);
    document.removeEventListener('favorite-removed', this.onFavoriteToggled);
  },
  computed: {
    /**
     * Whether the Favorites chip is on — the restriction half of the chip bar,
     * split from the sources so each travels as its own REST parameter.
     *
     * @returns {boolean} true when only starred contacts should show
     */
    favoritesOnly() {
      return this.filterChips.includes('favorites');
    },
    /**
     * The selected SOURCE chips, the Favorites one excluded: it is not a place
     * a contact came from, and sending it as one would 400.
     *
     * @returns {Array} the selected source values
     */
    sources() {
      return this.filterChips.filter(value => value !== 'favorites');
    },
    /**
     * The sources to ask the REST for, or null for every source.
     * <p>
     * Every chip selected means the same thing as none: the union is the whole
     * store. Sending nothing then costs the server one filter it would apply for
     * no reason.
     *
     * @returns {Array} the selected filter values, or null
     */
    sourceFilter() {
      // Every selected chip, not just a lone one. Written when there were two chips,
      // this answered null for any selection but a single one -- so with three chips
      // "Collected + Added manually" quietly showed the address book as well.
      return this.sources.length && this.sources.length < SOURCES.length ? this.sources : null;
    },
    /**
     * Whether the wide two-pane layout is on (list left, detail right).
     *
     * @returns {boolean} true when expanded
     */
    hasFullAppLeft() {
      return this.expanded;
    },
    /**
     * Whether the A–Z rail earns its pixels: enough contacts that sticky
     * headers alone stop being a way to navigate.
     *
     * @returns {boolean} true when the rail should render
     */
    railVisible() {
      return this.size >= RAIL_MIN_CONTACTS;
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
     * Opens the drawer and loads the store on the way in. A caller may name a
     * contact to land on — the global Favorites drawer does — and it opens in
     * the detail view once read.
     *
     * @param {object} opening - optionally {contactId} to open the drawer on
     * @returns {void}
     */
    open(opening) {
      this.contactsDrawer = true;
      this.$refs.emailContactsDrawer.open();
      this.readSourceCounts();
      this.reload();
      if (opening?.contactId) {
        this.$emailConnectorContactsService.getContact(opening.contactId)
          .then(contact => this.selectContact(contact))
          .catch(() => null);
      } else if (opening?.address) {
        this.openOnAddress(opening.address, opening.name);
      }
    },
    /**
     * Counts what each chip selects, which is what decides which chips are worth
     * offering and whether the bar is worth showing at all — the three sources,
     * plus the starred set behind the Favorites chip.
     * <p>
     * One row per chip answers it, so this costs four pages of size one
     * rather than a count endpoint that would exist for nothing else. A failure
     * leaves that chip out: a filter nobody can explain is worse than none.
     *
     * @returns {void}
     */
    readSourceCounts() {
      const counts = {};
      Promise.all(SOURCES.map(source =>
        this.$emailConnectorContactsService.getContacts(null, 0, 1, source)
          .then(page => counts[source] = page?.size || 0)
          .catch(() => counts[source] = 0))
        .concat(this.$emailConnectorContactsService.getContacts(null, 0, 1, null, true)
          .then(page => counts.favorites = page?.size || 0)
          .catch(() => counts.favorites = 0)))
        .then(() => {
          this.sourceCounts = counts;
          if (!counts.favorites && this.favoritesOnly) {
            // The last star just went out while its chip was on. Left selected,
            // an invisible chip would keep filtering the list to nothing with no
            // way to see why, so the selection dies with the chip.
            this.filterChips = this.filterChips.filter(value => value !== 'favorites');
          }
        });
    },
    /**
     * Resets the drawer's transient state on close.
     *
     * @returns {void}
     */
    close() {
      this.selectedContact = null;
      this.term = null;
      this.filterChips = [];
      this.contactsDrawer = false;
    },
    /**
     * A favorite was toggled somewhere on the page. Only contact favorites are
     * this drawer's business, and only while it is open — the next open re-reads
     * everything anyway.
     *
     * @param {CustomEvent} event - the shared favorite button's announcement
     * @returns {void}
     */
    onFavoriteToggled(event) {
      if (event?.detail?.type === 'contact' && this.contactsDrawer) {
        this.refresh();
      }
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
     * Reloads the current view eagerly, sequential pages of {@link PAGE_SIZE}
     * bounded by {@link MAX_ROWS} — which keeps the A–Z rail's jumps exact
     * without offset bookkeeping client-side. The first page renders
     * immediately; the rest stream in behind it.
     *
     * @returns {Promise<void>} resolves when every page landed
     */
    async reload() {
      this.loadToken++;
      const token = this.loadToken;
      this.loading = true;
      try {
        const firstPage = await this.$emailConnectorContactsService.getContacts(this.term,
          0,
          PAGE_SIZE,
          this.sourceFilter,
          this.favoritesOnly);
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
      return this.$emailConnectorContactsService.getContacts(this.term, offset, PAGE_SIZE, this.sourceFilter, this.favoritesOnly)
        .then(page => {
          if (token !== this.loadToken) {
            return;
          }
          this.contacts = this.contacts.concat(page.contacts || []);
          return this.loadRemainingPages(token, offset + PAGE_SIZE, total);
        });
    },
    /**
     * Opens a contact: inline in the right pane when expanded, in the sibling
     * detail drawer otherwise. Rows are re-read so the detail carries the
     * read-time enrichment (profile avatar and link, live directory data for
     * imported colleagues).
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
     * Re-reads a stored contact for its read-time enrichment.
     *
     * @param {object} contact - the selected row
     * @returns {Promise<object>} the contact to show
     */
    enrich(contact) {
      return contact.id ? this.$emailConnectorContactsService.getContact(contact.id).catch(() => contact)
        : Promise.resolve(contact);
    },
    /**
     * Opens whoever is reachable at an address — the mailbox's way in, where all
     * it has is what the mail header said.
     * <p>
     * An address nobody is stored at opens the form instead, carrying the name
     * and address the mail wrote, so adding a correspondent is a confirmation
     * rather than retyping what is already on screen.
     *
     * @param {string} address - the address from the mail
     * @param {string} name - the display name the mail carried, may be empty
     * @returns {void}
     */
    openOnAddress(address, name) {
      this.$emailConnectorContactsService.getContactByAddress(address)
        .then(contact => {
          if (contact) {
            this.selectContact(contact);
          } else {
            this.openForm(this.prefillFrom(address, name));
          }
        })
        .catch(() => this.openForm(this.prefillFrom(address, name)));
    },

    /**
     * The form fields a mail header can fill in on its own.
     * <p>
     * Splitting a display name into given and family is a guess — plenty of
     * senders write "DOE Jane" — which is exactly why an unknown address opens a
     * form to confirm rather than creating the contact silently.
     *
     * @param {string} address - the address from the mail
     * @param {string} name - the display name the mail carried, may be empty
     * @returns {object} what to prefill
     */
    prefillFrom(address, name) {
      const parts = (name || '').trim().split(/\s+/).filter(Boolean);
      return {
        primaryEmail: address,
        givenName: parts[0] || '',
        familyName: parts.slice(1).join(' '),
      };
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
      this.refresh();
    },
    /**
     * Reloads the list AND re-counts the sources, for the changes that can move
     * a source in or out of existence -- the first contact created by hand, the
     * last one deleted. A plain reload would leave a chip promising rows that
     * are gone, or hide one the user just created something for.
     *
     * @returns {void}
     */
    refresh() {
      this.readSourceCounts();
      this.reload();
    },
  }
};
</script>
