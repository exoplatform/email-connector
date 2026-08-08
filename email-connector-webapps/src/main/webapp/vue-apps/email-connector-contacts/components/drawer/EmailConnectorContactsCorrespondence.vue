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
  <!-- The recent mail exchanged with this person, folded shut by default: the
       searches behind it are IMAP round-trips, and a card must stay as cheap to
       open as it is today — clicking a sender now opens one on every mail. The
       section loads on the first unfolding, and degrades to silence when the
       mailbox cannot answer: a card without correspondence is still a useful
       card, so there is a quiet empty line and never an error banner.

       A contact without any address gets no section at all: there is nothing
       to search by, and an always-empty block would read as a defect. -->
  <div v-if="addresses.length">
    <v-divider class="mb-1" />
    <v-list dense class="pt-0">
      <v-list-item
        class="px-0"
        @click="toggle">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16">
            fas fa-envelope-open-text
          </v-icon>
        </v-list-item-icon>
        <v-list-item-content>
          <v-list-item-title>
            {{ $t('emailConnector.contacts.correspondence.title') }}
          </v-list-item-title>
        </v-list-item-content>
        <v-list-item-action class="my-auto">
          <v-icon size="14">
            {{ expanded ? 'fas fa-chevron-up' : 'fas fa-chevron-down' }}
          </v-icon>
        </v-list-item-action>
      </v-list-item>
      <template v-if="expanded">
        <v-list-item v-if="loading" class="px-0 justify-center">
          <v-progress-circular
            size="20"
            width="2"
            color="primary"
            indeterminate />
        </v-list-item>
        <v-list-item
          v-for="mail in results"
          :key="`${mail.folder}-${mail.mailRemoteId}`"
          class="px-0"
          @click="open(mail)">
          <!-- The direction at a glance: the paper plane the composer sends with,
               the inbox the mail arrived in. Said twice — icon and tooltip —
               because a glyph alone is a guess. -->
          <v-list-item-icon class="me-3 my-auto">
            <v-icon
              size="14"
              :title="directionLabel(mail)">
              {{ mail.direction === 'sent' ? 'fas fa-paper-plane' : 'fas fa-inbox' }}
            </v-icon>
          </v-list-item-icon>
          <v-list-item-content>
            <v-list-item-title :class="!mail.read && mail.direction === 'received' ? 'font-weight-bold' : ''">
              {{ mail.subject && mail.subject.trim() || $t('emailConnector.contacts.correspondence.noSubject') }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-sub-title">
              {{ directionLabel(mail) }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-auto text-sub-title">
            <date-format :value="mail.receivedDate" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item v-if="!loading && !results.length" class="px-0">
          <v-list-item-content>
            <v-list-item-subtitle class="text-sub-title">
              {{ $t('emailConnector.contacts.correspondence.empty') }}
            </v-list-item-subtitle>
          </v-list-item-content>
        </v-list-item>
        <v-list-item
          v-if="!loading && hasMore"
          class="px-0"
          @click="seeAll">
          <v-list-item-content>
            <v-list-item-title class="primary--text text-caption">
              {{ $t('emailConnector.contacts.correspondence.seeAll') }}
            </v-list-item-title>
          </v-list-item-content>
        </v-list-item>
      </template>
    </v-list>
  </div>
</template>

<script>
export default {
  props: {
    // The contact the card shows; only its addresses are read here.
    contact: {
      type: Object,
      required: true,
    },
  },
  data() {
    return {
      expanded: false,
      loading: false,
      results: [],
      hasMore: false,
      // Which contact the results belong to: the card is reused from one
      // contact to the next, and yesterday's mail under today's name would be
      // worse than no section at all.
      loadedForId: null,
    };
  },
  computed: {
    /**
     * Every address of the contact, primary first — the same list the card's
     * address rows render, so correspondence covers exactly the addresses the
     * user can see.
     *
     * @returns {Array} the addresses
     */
    addresses() {
      return [this.contact.primaryEmail].concat(this.contact.secondaryEmails || []).filter(address => address);
    },
  },
  watch: {
    /**
     * Folds the section shut when the card moves to another contact, dropping
     * the previous one's mail. The next unfolding reloads for the new person.
     *
     * @returns {void}
     */
    'contact.id': function() {
      this.expanded = false;
      this.results = [];
      this.hasMore = false;
      this.loadedForId = null;
    },
  },
  methods: {
    /**
     * Unfolds or folds the section, loading on the first unfolding only —
     * folding and unfolding again costs nothing.
     *
     * @returns {void}
     */
    toggle() {
      this.expanded = !this.expanded;
      if (this.expanded && this.loadedForId !== this.contact.id) {
        this.load();
      }
    },
    /**
     * Loads the correspondence: two searches per address (what they sent, what
     * they were sent), merged newest first by the service. A failure leaves the
     * quiet empty line — the mailbox being unreachable, or the user having no
     * mailbox connected at all, must not make the card look broken.
     *
     * @returns {void}
     */
    load() {
      this.loading = true;
      const forId = this.contact.id;
      this.$emailConnectorContactsService.getCorrespondence(this.addresses)
        .then(page => {
          // An answer for a contact the card no longer shows is dropped, not shown.
          if (forId === this.contact.id) {
            this.results = page.results;
            this.hasMore = page.hasMore;
            this.loadedForId = forId;
          }
        })
        .catch(() => {
          if (forId === this.contact.id) {
            this.results = [];
            this.hasMore = false;
            // Deliberately NOT marked loaded: a re-open retries, which is the
            // only retry affordance this quiet failure offers.
            this.loadedForId = null;
          }
        })
        .finally(() => this.loading = false);
    },
    /**
     * The localized direction of one mail, for the subtitle and the icon's
     * tooltip.
     *
     * @param {object} mail - a correspondence row
     * @returns {string} Sent or Received, localized
     */
    directionLabel(mail) {
      return mail.direction === 'sent'
        && this.$t('emailConnector.contacts.correspondence.sent')
        || this.$t('emailConnector.contacts.correspondence.received');
    },
    /**
     * Opens one mail in the mailbox's own reader, through the same cross-app
     * door the favorites drawer and the unified search use.
     *
     * @param {object} mail - the clicked row
     * @returns {void}
     */
    open(mail) {
      this.$emailConnectorContactsService.openMail(mail);
    },
    /**
     * Opens the mailbox searching for the contact's primary address — the
     * existing "see all" path (the mailbox's own search page). Its free text
     * matches subject or sender, so it carries the received direction onward;
     * no page exists yet for "everything I sent them" and this link does not
     * pretend one does.
     *
     * @returns {void}
     */
    seeAll() {
      this.$emailConnectorContactsService.openMailboxSearch(this.addresses[0]);
    },
  },
};
</script>
