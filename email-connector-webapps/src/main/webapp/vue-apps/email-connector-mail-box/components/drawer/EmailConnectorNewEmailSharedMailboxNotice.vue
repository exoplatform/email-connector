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
  <!-- The composer opened from a mailbox somebody shared with the user (delegation
       plan 7.8): the mail goes out AS THE USER, through their own mail server, and
       lands in their own Sent -- and in the owner's Sent where that copy is filed
       (EXO-90551). The owner's copy is offered either way, ticked by default only when
       the owner would otherwise have no trace of the mail (PO decision Q-3). The band is the identity cue's (7.6),
       with the access level's icon as everywhere else: the drawer's title already says
       whether this is a reply. -->
  <email-connector-shared-mailbox-band :entry="entry">
    <!-- One block after the icon, so the sentence and the checkbox share its left edge. -->
    <div class="text-start" style="flex: 1 1 0; min-width: 0;">
      <div class="text--primary">
        <template v-for="(part, index) in noticeParts">
          <b v-if="part.bold" :key="index">{{ part.text }}</b>
          <template v-else>{{ part.text }}</template>
        </template>
      </div>
      <!-- A plain checkbox rather than v-checkbox: the latter draws its own white input
           slot, which sat as a white box inside the tinted band. Top-aligned with the
           label's first line; a wrapped label hangs under its own text. -->
      <label
        :title="copyOwnerTitle"
        for="emailSharedMailboxCopyOwner"
        class="d-flex align-start justify-start text-start mt-1 mb-0 text--primary"
        style="cursor: pointer;">
        <!-- In a box one text line high, so the checkbox centres on the label's first
             line, as the band's icon does. -->
        <span class="d-flex align-center flex-shrink-0 me-2" style="height: 1.25rem;">
          <input
            id="emailSharedMailboxCopyOwner"
            :checked="copyOwner"
            :disabled="ownerIsRecipient"
            :aria-label="copyOwnerTitle"
            style="margin: 0;"
            type="checkbox"
            @change="$emit('update:copy-owner', $event.target.checked)">
        </span>
        <span>{{ $t('emailConnector.mailBox.sharedMailbox.composer.copyOwner', { 0: entry.ownerFullName }) }}</span>
      </label>
    </div>
  </email-connector-shared-mailbox-band>
</template>

<script>
// A marker no translation contains, standing for the owner's name, which is in bold.
const OWNER_MARK = '\u0001';

export default {
  props: {
    // The switcher entry of the mailbox the composer was opened from.
    entry: { type: Object, required: true },
    // Whether this is a reply (a new mail or a forward otherwise).
    reply: { type: Boolean, default: false },
    // Whether a copy is also filed in the owner's Sent (EXO-90551).
    sentCopy: { type: Boolean, default: false },
    // Whether the owner is among the copied recipients.
    copyOwner: { type: Boolean, default: false },
    // Whether the owner is a direct (To) recipient: the box then stays ticked and
    // cannot be unticked, since unticking only removes the Cc copy and the owner would
    // get the mail anyway.
    ownerIsRecipient: { type: Boolean, default: false },
  },
  computed: {
    /**
     * The checkbox's full meaning, for its title and accessible name -- the label says
     * only whom it copies, the sentence above already says why.
     *
     * @returns {String} the explanation
     */
    copyOwnerTitle() {
      return this.$t('emailConnector.mailBox.sharedMailbox.composer.copyOwner.title', { 0: this.entry.ownerFullName });
    },
    /**
     * What the band says -- as whom, from whose mailbox, what the recipients see and
     * where the sent copy goes -- in pieces, the owner's name in bold. Worded for any
     * recipient, the owner or the user included.
     *
     * @returns {Array} [{text, bold}]
     */
    noticeParts() {
      // Where the sent copies go: the user's own Sent, and the owner's when it is filed
      // there (EXO-90551).
      const suffix = this.sentCopy ? '.filed' : '';
      const key = this.reply
        ? `emailConnector.mailBox.sharedMailbox.composer.reply${suffix}`
        : `emailConnector.mailBox.sharedMailbox.composer.new${suffix}`;
      return this.$t(key, { 0: OWNER_MARK })
        .split(new RegExp(`(${OWNER_MARK})`))
        .filter(part => part)
        .map(part => (part === OWNER_MARK ? { text: this.entry.ownerFullName, bold: true } : { text: part, bold: false }));
    },
  },
};
</script>
