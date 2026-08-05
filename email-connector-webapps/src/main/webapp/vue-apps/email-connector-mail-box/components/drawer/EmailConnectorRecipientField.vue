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
  <!-- One recipient field, used three times (To / Cc / Bcc). Chips for what is
       already addressed, a plain input for what is being typed, and the
       suggestion list IN NORMAL FLOW under it.

       That last part is deliberate and is why this is not a v-autocomplete or a
       v-combobox: both render their panel in a floating v-menu, and a v-menu
       inside an exo-drawer fights the drawer's stacking context - the same
       collision that already forced the category notification preferences to
       become chips. A list that simply follows the input in the document cannot
       lose that fight, and it pushes the rest of the form down rather than
       covering it, which in a drawer reads better anyway.

       A raw address is always accepted: this is mail, not a closed directory. -->
  <div>
    <!-- The label sits in a fixed column so To, Cc and Bcc share one left edge,
         and the row centres rather than top-aligns: the field carries Vuetify's
         own vertical padding, so aligning to the top dropped the input a line
         below its label and gave each row a different indent. The chips wrap
         inside their own box, which is what needs min-width 0 -- a flex item
         will not shrink below its content otherwise, and the box pushed the
         label out of the row. -->
    <div class="d-flex align-center">
      <v-label :for="fieldId" class="flex-grow-0 flex-shrink-0" style="width: 34px;">
        <span class="text-subtitle-color">{{ label }}</span>
      </v-label>
      <div class="d-flex flex-wrap align-center flex-grow-1" style="min-width: 0;">
        <v-chip
          v-for="(recipient, index) in value"
          :key="recipient.address"
          :title="recipient.address"
          class="ma-1"
          small
          close
          @click:close="removeAt(index)">
          <v-avatar
            v-if="recipient.avatarUrl"
            left>
            <v-img :src="recipient.avatarUrl" />
          </v-avatar>
          <span class="text-truncate">{{ chipLabel(recipient) }}</span>
        </v-chip>
        <v-text-field
          :id="fieldId"
          ref="recipientInput"
          :value="term"
          :placeholder="inputPlaceholder"
          :aria-label="label"
          class="pa-0 ma-0 flex-grow-1"
          type="text"
          autocomplete="off"
          solo
          flat
          dense
          single-line
          hide-details
          @input="onInput"
          @keydown.enter.prevent="onEnter"
          @keydown.tab="commitTypedAddress"
          @keydown.down.prevent="moveHighlight(1)"
          @keydown.up.prevent="moveHighlight(-1)"
          @keydown.esc="onEscape"
          @keydown.delete="onBackspace"
          @blur="onBlur" />
      </div>
    </div>
    <div
      v-if="errorMessage"
      class="error--text caption ms-2 pb-1">
      {{ errorMessage }}
    </div>
    <v-list
      v-if="suggestions.length"
      dense
      class="py-0">
      <v-list-item
        v-for="(suggestion, index) in suggestions"
        :key="suggestion.address"
        :input-value="index === highlightedIndex"
        class="px-2"
        @mousedown.prevent="pick(suggestion)">
        <v-list-item-avatar
          size="28"
          class="my-1 me-2">
          <v-img
            v-if="suggestion.avatarUrl"
            :src="suggestion.avatarUrl" />
          <v-avatar
            v-else
            color="primary"
            size="28">
            <span class="white--text text-caption">{{ initialsOf(suggestion) }}</span>
          </v-avatar>
        </v-list-item-avatar>
        <v-list-item-content class="py-1">
          <v-list-item-title class="text-color">
            {{ suggestion.displayName || suggestion.address }}
          </v-list-item-title>
          <v-list-item-subtitle
            v-if="suggestion.displayName"
            class="text-sub-title">
            {{ suggestion.address }}
          </v-list-item-subtitle>
        </v-list-item-content>
        <v-list-item-icon
          v-if="suggestion.platformUser"
          class="my-auto ms-2">
          <v-icon
            :title="$t('emailConnector.mailBox.newEmail.drawer.recipients.platformUser')"
            size="12"
            class="text-sub-title">
            fas fa-users
          </v-icon>
        </v-list-item-icon>
      </v-list-item>
    </v-list>
  </div>
</template>

<script>
// Long enough that typing a name does not fire a request per letter, short
// enough that the list feels attached to the keyboard. Same value as the
// Contacts drawer's own filter, on purpose.
const SEARCH_DEBOUNCE_MS = 300;

// How many suggestions to ask for. The server caps it anyway; this is what the
// list can show without turning the drawer into a scrolling directory.
const SUGGEST_LIMIT = 10;

// Deliberately permissive: the field's job is to catch a typo like a missing
// "@" or a stray space, not to adjudicate RFC 5322. Anything shaped like an
// address goes through, and the mail server has the last word.
const ADDRESS_PATTERN = /^[^\s@,;]+@[^\s@,;]+\.[^\s@,;]+$/;

// What a user separates addresses with when pasting or typing a list.
const SEPARATORS = /[,;]/;

export default {
  props: {
    // The addressed recipients: [{address, name, avatarUrl}], v-model.
    value: {
      type: Array,
      default: () => [],
    },
    // The field's label ("To:", "Cc:", "Bcc:").
    label: {
      type: String,
      default: '',
    },
    // The placeholder shown while the field holds no recipient.
    placeholder: {
      type: String,
      default: '',
    },
    // The input's DOM id, which the label points at.
    fieldId: {
      type: String,
      required: true,
    },
  },
  data() {
    return {
      term: '',
      suggestions: [],
      highlightedIndex: -1,
      errorMessage: null,
      searchTimeout: null,
      // Increments on every search; a slower earlier response is dropped rather
      // than allowed to overwrite the list a later keystroke already produced.
      searchToken: 0,
    };
  },
  computed: {
    /**
     * The placeholder, shown only while the field is empty: repeating "Add
     * recipient" after three chips is noise.
     *
     * @returns {string} the placeholder text
     */
    inputPlaceholder() {
      return this.value.length ? '' : this.placeholder;
    },
  },
  beforeDestroy() {
    window.clearTimeout(this.searchTimeout);
  },
  methods: {
    /**
     * What a chip reads: the name when one is known, the address otherwise.
     *
     * @param {object} recipient - the addressed recipient
     * @returns {string} the chip label
     */
    chipLabel(recipient) {
      return recipient.name || recipient.address;
    },
    /**
     * The initials standing in for a suggestion with no avatar.
     *
     * @param {object} suggestion - the suggestion row
     * @returns {string} up to two uppercased initials
     */
    initialsOf(suggestion) {
      return (suggestion.displayName || suggestion.address || '').split(/[\s.@_-]+/)
        .filter(word => word)
        .map(word => word.charAt(0).toUpperCase())
        .slice(0, 2)
        .join('') || '?';
    },
    /**
     * Puts the caret back in the input after a suggestion was picked, so the
     * next recipient can be typed without reaching for the mouse. The input
     * grows to fill whatever the chips leave of the row, so it is also the
     * click target — no handler on the row itself, which would be an
     * interactive listener on a non-interactive element.
     *
     * @returns {void}
     */
    focusInput() {
      this.$refs.recipientInput?.focus();
    },
    /**
     * Reacts to typing. A separator anywhere in the text means the user (or a
     * paste) finished one or more addresses, so those are turned into chips
     * immediately and only the tail keeps being searched.
     *
     * @param {string} typed - the input's new value
     * @returns {void}
     */
    onInput(typed) {
      this.term = typed || '';
      this.errorMessage = null;
      if (SEPARATORS.test(this.term)) {
        this.splitOnSeparators();
      }
      this.scheduleSearch();
    },
    /**
     * Turns every complete part of a separated list into a chip and leaves the
     * last, still-unterminated part in the input.
     *
     * @returns {void}
     */
    splitOnSeparators() {
      const parts = this.term.split(SEPARATORS);
      this.term = parts.pop();
      parts.map(part => part.trim())
        .filter(part => part)
        .forEach(part => this.addAddress(part));
    },
    /**
     * Debounces the suggestion query, dropping the list as soon as the field is
     * emptied so a stale suggestion never survives a cleared input.
     *
     * @returns {void}
     */
    scheduleSearch() {
      window.clearTimeout(this.searchTimeout);
      const term = this.term?.trim();
      if (!term) {
        this.closeSuggestions();
        return;
      }
      this.searchTimeout = window.setTimeout(() => this.search(term), SEARCH_DEBOUNCE_MS);
    },
    /**
     * Queries the server, guarded by a token: responses can come back out of
     * order, and the answer to an older term must never replace the answer to
     * the term now in the field.
     *
     * @param {string} term - the text to search
     * @returns {void}
     */
    search(term) {
      this.searchToken++;
      const token = this.searchToken;
      this.$emailConnectorMailBoxService.suggestRecipients(term, SUGGEST_LIMIT)
        .then(found => {
          if (token !== this.searchToken) {
            return;
          }
          this.suggestions = (found || []).filter(suggestion => !this.alreadyAdded(suggestion.address));
          this.highlightedIndex = this.suggestions.length ? 0 : -1;
        })
        .catch(() => {
          if (token === this.searchToken) {
            // A failing lookup leaves the field a plain address input rather
            // than an error: the user can still type who they meant.
            this.closeSuggestions();
          }
        });
    },
    /**
     * Enter takes the highlighted suggestion when there is one, and otherwise
     * commits whatever was typed — so a raw address never needs the list.
     *
     * @returns {void}
     */
    onEnter() {
      if (this.highlightedIndex >= 0 && this.suggestions[this.highlightedIndex]) {
        this.pick(this.suggestions[this.highlightedIndex]);
      } else {
        this.commitTypedAddress();
      }
    },
    /**
     * Backspace on an empty input removes the last chip, which is what every
     * mail client does and what the fingers expect.
     *
     * @returns {void}
     */
    onBackspace() {
      if (!this.term && this.value.length) {
        this.removeAt(this.value.length - 1);
      }
    },
    /**
     * Leaving the field commits what is in it: a half-typed address that
     * silently disappears on blur is the classic way to send a mail to the
     * wrong people.
     *
     * @returns {void}
     */
    onBlur() {
      window.clearTimeout(this.searchTimeout);
      this.commitTypedAddress();
      this.closeSuggestions();
    },
    /**
     * Escape dismisses the suggestion list — and only then does it stop the
     * event, so that an Escape with no list open still closes the drawer the
     * way it always has.
     *
     * @param {object} event - the keydown event
     * @returns {void}
     */
    onEscape(event) {
      if (this.suggestions.length) {
        event.stopPropagation();
        this.closeSuggestions();
      }
    },
    /**
     * Moves the highlight with the arrow keys, wrapping at both ends.
     *
     * @param {number} step - +1 for down, -1 for up
     * @returns {void}
     */
    moveHighlight(step) {
      if (!this.suggestions.length) {
        return;
      }
      const count = this.suggestions.length;
      this.highlightedIndex = (this.highlightedIndex + step + count) % count;
    },
    /**
     * Turns a suggestion into a chip, carrying its name and avatar so the chip
     * shows the person rather than the address.
     *
     * @param {object} suggestion - the picked suggestion
     * @returns {void}
     */
    pick(suggestion) {
      this.term = '';
      this.errorMessage = null;
      this.closeSuggestions();
      this.emitRecipients(this.value.concat([{
        name: suggestion.displayName,
        address: suggestion.address,
        avatarUrl: suggestion.avatarUrl,
      }]));
      this.$nextTick(this.focusInput);
    },
    /**
     * Commits what is currently typed, if anything. An unusable address is
     * REPORTED and kept in the input rather than dropped: swallowing it would
     * let a mail leave without a recipient the user believed they had added.
     *
     * @returns {void}
     */
    commitTypedAddress() {
      const typed = this.term?.trim();
      if (!typed) {
        return;
      }
      if (this.addAddress(typed)) {
        this.term = '';
        this.closeSuggestions();
      }
    },
    /**
     * Adds one raw address as a chip, refusing what is not shaped like an
     * address and ignoring what the field already holds.
     *
     * @param {string} address - the typed address
     * @returns {boolean} true when a chip was added or the address was a
     *          harmless duplicate, false when it was rejected
     */
    addAddress(address) {
      if (!ADDRESS_PATTERN.test(address)) {
        this.errorMessage = this.$t('emailConnector.mailBox.newEmail.drawer.recipients.invalid', {0: address});
        return false;
      }
      if (!this.alreadyAdded(address)) {
        this.emitRecipients(this.value.concat([{address: address}]));
      }
      return true;
    },
    /**
     * Whether an address is already a chip, compared the way the server keys
     * addresses (case-insensitively).
     *
     * @param {string} address - the address to look for
     * @returns {boolean} true when it is already addressed
     */
    alreadyAdded(address) {
      const normalized = (address || '').toLowerCase();
      return this.value.some(recipient => (recipient.address || '').toLowerCase() === normalized);
    },
    /**
     * Removes one chip.
     *
     * @param {number} index - the chip position
     * @returns {void}
     */
    removeAt(index) {
      const recipients = this.value.slice();
      recipients.splice(index, 1);
      this.emitRecipients(recipients);
    },
    /**
     * Hides the suggestion list.
     *
     * @returns {void}
     */
    closeSuggestions() {
      this.suggestions = [];
      this.highlightedIndex = -1;
    },
    /**
     * Publishes the new recipient list to the parent's v-model.
     *
     * @param {Array} recipients - the recipients now addressed
     * @returns {void}
     */
    emitRecipients(recipients) {
      this.$emit('input', recipients);
    },
  },
};
</script>
