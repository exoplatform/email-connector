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
    :use-filter="canSearch"
    :filter-placeholder="$t('emailConnector.contacts.filter.placeholder')"
    :go-back-button="canGoBack"
    @filter-updated="onFilterUpdated"
    @go-back="cancelSelectMode"
    @closed="close"
    class="no-box-shadow">
    <template #title>
      <!-- The count follows whatever is filtered, so it doubles as the answer to
           "did my search find anything". It is also the cheapest possible alarm:
           an address book sync that quietly removed everything shows up here the
           moment the drawer opens, instead of in a log line nobody reads.
           While picking, the same line counts what is ticked instead: one place
           says what the drawer is doing, and it is the place already read. -->
      <span v-if="!hasFullAppLeft" :class="{ 'text-body': selectMode }">
        {{ title }}
        <span
          v-if="size && !selectMode"
          class="text-sub-title ms-1">· {{ size }}</span>
      </span>
      <span v-else></span>
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftTitle>
      <!-- The way out of picking, in the wide layout: the drawer's own go-back
           button belongs to the narrow header, which this slot replaces. -->
      <v-btn
        v-if="selectMode"
        icon
        @click="cancelSelectMode">
        <v-icon size="20">
          {{ $vuetify.rtl && 'fa fa-arrow-right' || 'fa fa-arrow-left' }}
        </v-icon>
      </v-btn>
      <!-- A flex line, so the title and the actions are centred against each
           other. Left to inline layout they align on baselines a button and a
           menu compute differently, and the menu sat low. -->
      <div class="d-flex align-center justify-space-between width-full">
        <span :class="{ 'text-body': selectMode }">{{ title }}</span>
        <email-connector-contacts-drawer-actions
          :select-mode="selectMode"
          :ticked-count="tickedIds.length"
        :selection-publishable="selectionPublishable"
          :publishing="publishing"
          :importing="importing"
          :publishable="publishable"
          @add="openForm()"
          @import="onImportFile"
          @export="onExport"
          @bulk-publish="startSelectMode"
          @publish="publishTicked" />
      </div>
    </template>
    <template #titleIcons>
      <email-connector-contacts-drawer-actions
        v-if="!hasFullAppLeft"
        :select-mode="selectMode"
        :ticked-count="tickedIds.length"
        :selection-publishable="selectionPublishable"
        :publishing="publishing"
        :importing="importing"
        :publishable="publishable"
        @add="openForm()"
        @import="onImportFile"
        @export="onExport"
        @bulk-publish="startSelectMode"
        @publish="publishTicked" />
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftContent>
      <email-connector-contacts-source-filter
        v-model="filterChips"
        :counts="sourceCounts" />
      <email-connector-contacts-drawer-content
        :contacts="contacts"
        :letter-index="letterIndex"
        :rail-visible="railVisible"
        :selected-id="selectedContact?.id"
        :empty-label="emptyLabel"
        :select-mode="selectMode"
        :ticked-ids="tickedIds"
        :indeterminate="someTicked"
        @update:ticked-ids="tickedIds = $event"
        @select="selectContact"
        @tick="toggleTick" />
    </template>
    <template v-if="contactsDrawer" #content>
      <template v-if="!expanded">
        <email-connector-contacts-source-filter
          v-model="filterChips"
          :counts="sourceCounts" />
        <email-connector-contacts-drawer-content
          :contacts="contacts"
          :letter-index="letterIndex"
          :rail-visible="railVisible"
          :empty-label="emptyLabel"
          :select-mode="selectMode"
          :ticked-ids="tickedIds"
          :indeterminate="someTicked"
          @update:ticked-ids="tickedIds = $event"
          @select="selectContact"
          @tick="toggleTick" />
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
    <!-- The last checkpoint, and named as one: these cards are created on the
         mail provider's server, and removing them again is deliberately not
         built. The count is in the message because that is the number the user
         is actually agreeing to. -->
    <exo-confirm-dialog
      ref="publishConfirmDialog"
      :title="$t('emailConnector.contacts.select.confirm.title')"
      :message="$t('emailConnector.contacts.select.confirm.message', [tickedIds.length])"
      :ok-label="$t('emailConnector.contacts.select.publish')"
      :cancel-label="$t('emailConnector.contacts.form.cancel')"
      @ok="doPublishTicked" />
  </exo-drawer>
</template>

<script>
const PAGE_SIZE = 200;
const SOURCES = ['collected', 'manual', 'directory', 'addressBook'];
const MAX_ROWS = 5000;
const RAIL_MIN_CONTACTS = 100;
// The server refuses a bigger file anyway (its own pre-parse check); saying so
// here spares the user uploading twenty megabytes to hear it.
const IMPORT_MAX_FILE_BYTES = 20 * 1024 * 1024;
const IMPORT_POLL_MS = 1500;

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
      // The polled vCard import state; null shows no banner. Kept here rather
      // than in the menu because the menu unmounts when the drawer expands,
      // and an import must survive that.
      importState: null,
      // Whether this user has an address book at all, which is what decides
      // that the bulk-publish entry is worth offering. Answered once and
      // cached by the service, so both menu instances cost one call.
      publishable: false,
      // Picking contacts rather than browsing them. Selecting happens in the
      // list itself, the way the mailbox already does it, so what is offered
      // is filtered and searched exactly like what is being looked at.
      selectMode: false,
      tickedIds: [],
      publishing: false,
      importPollTimeout: null,
      // True from the moment a file is chosen until the server's answer or the
      // poll says the run ended — the window the menu greys Import out for.
      importUploading: false,
    };
  },
  watch: {
    filterChips() {
      // A chip narrows the list, and a ticked row it hides would still be
      // published — invisibly, on somebody else's server, with no undo. The
      // selection dies with the view it was made in.
      this.cancelSelectMode();
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
    this.$emailConnectorContactsService.isAddressBookPublishable()
      .then(available => this.publishable = available)
      .catch(() => this.publishable = false);
  },
  beforeDestroy() {
    this.$root.$off('open-email-contacts-drawer', this.open);
    this.$root.$off('email-contacts-refresh', this.reload);
    document.removeEventListener('favorite-added', this.onFavoriteToggled);
    document.removeEventListener('favorite-removed', this.onFavoriteToggled);
    window.clearTimeout(this.importPollTimeout);
  },
  computed: {
    /**
     * The loaded contacts this user could publish, which is what "all" means
     * here: the list is filtered and searched, so all means all of what is on
     * screen, never a hidden thousand.
     *
     * @returns {Array} the selectable contacts
     */
    /**
     * Whether every ticked contact can be published.
     * <p>
     * All of them, not some: the server refuses a selection containing a
     * collected or directory contact rather than publishing the rest, so the
     * header must offer the action on exactly the same terms.
     *
     * @returns {boolean} true when the whole selection is publishable
     */
    selectionPublishable() {
      if (!this.tickedIds.length) {
        return false;
      }
      const publishableIds = this.selectableContacts.map(contact => contact.id);
      return this.tickedIds.every(id => publishableIds.includes(id));
    },
    selectableContacts() {
      return (this.contacts || []).filter(contact => contact.source === 'MANUAL');
    },
    /**
     * What the header says the drawer is doing: its name while browsing, the
     * count of what is ticked while picking.
     *
     * @returns {string} the localized title
     */
    title() {
      return this.selectMode ? this.$t('emailConnector.contacts.select.count', [this.tickedIds.length])
        : this.$t('emailConnector.contacts.drawer.title');
    },
    /**
     * Whether the header's filter field is offered. It is the platform's own
     * (exo-drawer), and it hides the go-back button, so it steps aside while
     * picking needs that button — the same trade the mailbox makes.
     *
     * @returns {boolean} true when the field should show
     */
    canSearch() {
      return !this.selectMode;
    },
    /**
     * Whether the drawer's own back arrow shows: the way out of picking in the
     * narrow layout. The wide one replaces this header, and carries its own.
     *
     * @returns {boolean} true when the back arrow should show
     */
    canGoBack() {
      return this.selectMode && !this.expanded;
    },
    /**
     * Whether some but not all are ticked, which is the box's third state --
     * without it, a partial selection reads as "none selected".
     *
     * @returns {boolean} true when the selection is partial
     */
    someTicked() {
      return this.tickedIds.length > 0 && this.tickedIds.length < this.selectableContacts.length;
    },
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
    /**
     * Whether a vCard import is under way — uploading, or running server-side.
     *
     * @returns {boolean} true while an import is going
     */
    importing() {
      return this.importUploading || this.importState?.status === 'IN_PROGRESS';
    },
  },
  methods: {
    /**
     * Turns picking on, from the menu.
     *
     * @returns {void}
     */
    startSelectMode() {
      this.tickedIds = [];
      this.selectMode = true;
    },
    /**
     * Leaves picking, dropping whatever was ticked: a selection the user
     * cannot see is not a selection worth keeping.
     *
     * @returns {void}
     */
    cancelSelectMode() {
      this.selectMode = false;
      this.tickedIds = [];
    },
    /**
     * Ticks or unticks one contact.
     *
     * @param {Object} contact the row that was ticked
     * @returns {void}
     */
    toggleTick(contact) {
      if (!contact?.id) {
        return;
      }
      const index = this.tickedIds.indexOf(contact.id);
      if (index >= 0) {
        this.tickedIds.splice(index, 1);
      } else {
        this.tickedIds.push(contact.id);
      }
    },
    /**
     * Publishes what was ticked, after saying how many.
     * <p>
     * The confirmation is not ceremony: these cards are created on somebody
     * else's server and deleting them again is deliberately not built, so this
     * is the last point at which the number can still be wrong and the user
     * can still say no.
     *
     * @returns {void}
     */
    publishTicked() {
      if (this.tickedIds.length) {
        this.$refs.publishConfirmDialog.open();
      }
    },
    /**
     * Publishes what was ticked, once the count has been confirmed.
     *
     * @returns {void}
     */
    doPublishTicked() {
      const count = this.tickedIds.length;
      this.publishing = true;
      this.$emailConnectorContactsService.queuePublishes(this.tickedIds)
        .then(() => {
          document.dispatchEvent(new CustomEvent('alert-message', {detail: {
            alertType: 'success',
            alertMessage: this.$t('emailConnector.contacts.select.done', [count]),
          }}));
          this.cancelSelectMode();
          this.refresh();
        })
        .catch(() => document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: 'error',
          alertMessage: this.$t('emailConnector.contacts.select.error'),
        }})))
        .finally(() => this.publishing = false);
    },
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
      this.resumeRunningImport();
      if (opening?.contactId) {
        this.$emailConnectorContactsService.getContact(opening.contactId)
          .then(contact => this.selectContact(contact))
          .catch(() => null);
      }
    },
    /**
     * Picks a still-running import back up on open — an import started, the
     * drawer closed, the drawer reopened. Only an IN_PROGRESS state is shown:
     * the stored report of a run finished last week is history, not news.
     *
     * @returns {void}
     */
    resumeRunningImport() {
      this.$emailConnectorContactsService.getImportStatus()
        .then(state => {
          if (state?.status === 'IN_PROGRESS') {
            this.importState = state;
            this.scheduleImportPoll();
          }
        })
        .catch(() => null);
    },
    /**
     * Imports a chosen .vcf: pushes it to the upload service, starts the
     * server-side run, and polls its state until it ends. The size cap is
     * checked here first so an oversized file costs an alert, not an upload.
     *
     * @param {File} file - the .vcf the user picked
     * @returns {Promise<void>} resolves when the run is started (not finished)
     */
    async onImportFile(file) {
      if (file.size > IMPORT_MAX_FILE_BYTES) {
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: 'error', alertMessage: this.$t('emailConnector.contacts.import.fileTooLarge')}}));
        return;
      }
      this.importUploading = true;
      try {
        const uploadId = this.$uploadService.generateRandomId();
        const resolvedId = await this.$uploadService.upload(file, uploadId);
        if (!resolvedId) {
          throw new Error('Upload failed');
        }
        this.importState = await this.$emailConnectorContactsService.importContacts(resolvedId);
        this.scheduleImportPoll();
      } catch (error) {
        // The server's message codes are this bundle's keys, so a 400/409 body
        // translates directly; anything else gets the generic sentence.
        let code = 'emailConnector.contacts.import.error';
        if (error?.status === 409) {
          code = 'emailConnector.contacts.import.alreadyRunning';
        } else if (error?.message === 'emailConnector.contacts.import.fileTooLarge') {
          code = 'emailConnector.contacts.import.fileTooLarge';
        }
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: 'error', alertMessage: this.$t(code)}}));
      } finally {
        this.importUploading = false;
      }
    },
    /**
     * Schedules the next look at the running import.
     *
     * @returns {void}
     */
    scheduleImportPoll() {
      window.clearTimeout(this.importPollTimeout);
      this.importPollTimeout = window.setTimeout(() => this.pollImportStatus(), IMPORT_POLL_MS);
    },
    /**
     * One look at the running import: progress feeds the banner, and the end
     * of the run swaps it for the report and repaints the list the run just
     * changed. A failed poll only tries again — the run does not need us
     * watching to finish.
     *
     * @returns {void}
     */
    pollImportStatus() {
      this.$emailConnectorContactsService.getImportStatus()
        .then(state => {
          this.importState = state;
          if (state?.status === 'IN_PROGRESS') {
            this.scheduleImportPoll();
            return;
          }
          this.reportImport(state);
          this.$root.$emit('email-contacts-refresh');
        })
        .catch(() => this.scheduleImportPoll());
    },
    /**
     * Says how the import went, in the toast every other outcome in this app
     * uses. A banner of its own read as a stranger's UI here, and the numbers
     * are one sentence -- worth reading once, not worth a surface to dismiss.
     *
     * @param {object} state - the finished import state
     * @returns {void}
     */
    reportImport(state) {
      this.importState = null;
      if (!state || state.status === 'FAILED') {
        const failure = this.$t(state?.messageCode || 'emailConnector.contacts.import.failed');
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: 'error', alertMessage: failure}}));
        return;
      }
      const report = this.$t('emailConnector.contacts.import.report', {
        0: state.imported || 0,
        1: state.alreadyKnown || 0,
        2: state.noAddress || 0,
        3: state.unreadable || 0,
      });
      // A run that hit the card cap succeeded for everything it read, so it is
      // still a success -- with the cap said in the same breath, or the numbers
      // look like a file that lost contacts for no reason.
      const capped = state.messageCode && this.$t(state.messageCode);
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: 'success', alertMessage: capped && `${report} ${capped}` || report}}));
    },
    /**
     * Downloads the whole store as one .vcf — always the whole store, which
     * is what the menu entry's label promises.
     *
     * @returns {void}
     */
    onExport() {
      this.$emailConnectorContactsService.downloadExport();
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
      // Before the chips, whose watcher would otherwise cancel picking on a
      // drawer already closed — harmless, but it hides where this belongs.
      this.cancelSelectMode();
      this.filterChips = [];
      this.contactsDrawer = false;
      // The poll dies with the drawer; the run itself does not need it, and
      // reopening resumes it. The banner state dies too — a stale report
      // reappearing days later would read as a new import.
      window.clearTimeout(this.importPollTimeout);
      this.importState = null;
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
