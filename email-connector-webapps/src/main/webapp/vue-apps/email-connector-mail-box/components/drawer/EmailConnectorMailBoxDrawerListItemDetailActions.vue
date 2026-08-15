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
  <v-layout>
    <!-- The toolbar seam, handed BOTH the opened message and the conversation it belongs
         to. `email` is what it has always carried and nothing that reads it today has to
         change; `thread` is added beside it, so a contributor that cares about the whole
         exchange can act on it and one that does not can carry on ignoring it.

         Why the conversation reaches the header at all: this bar speaks for what the
         drawer has open, and when a reader opens a conversation of several messages,
         what it has open is the conversation — not the one message that happened to be
         clicked in the list behind it. The header had no way to tell the two apart,
         because it is the drawer's title bar and the reader that knows is the drawer's
         content. See the `thread-context` event on
         EmailConnectorMailBoxDrawerThreadContent, which is where this comes from. -->
    <extension-registry-components
      :params="{
        email,
        thread: threadParams,
      }"
      name="EmailDetail"
      type="email-detail-toolbar"
      parent-element="div"
      element="div"
      class="my-auto" />
    <!-- All three write to the mail server by IMAP UID, which the backend resolves
         against the inbox — so a message opened out of a read-only folder offers
         none of them. The extension seam above stays: it is somebody else's toolbar
         and its actions are not this one's to withdraw. -->
    <template v-if="!readOnly">
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.unread.label')"
        @click="updateEmailReadStatus()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-mail-bulk</v-icon>
      </v-btn>
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.archive.label')"
        @click="archiveEmail()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-archive</v-icon>
      </v-btn>
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
        color="error"
        @click="deleteEmail()"
        icon>
        <v-icon size="20">fa-trash</v-icon>
      </v-btn>
    </template>
    <!-- What a Trash message offers instead. -->
    <template v-if="trashActions">
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.restore.label')"
        @click="restoreEmail()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-trash-restore</v-icon>
      </v-btn>
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.purge.label')"
        color="error"
        @click="purgeEmail()"
        icon>
        <v-icon size="20">fa-times-circle</v-icon>
      </v-btn>
    </template>
  </v-layout>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    // The conversation the opened message belongs to, as the reader assembled it:
    // {threadId, messages, subject}, drafts already excluded. Null while nothing is
    // open, and null in any drawer that does not render a reader — this toolbar is used
    // in both, and the message actions below never needed it.
    thread: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    /**
     * The conversation as the extension seam receives it: what the reader assembled,
     * plus the one thing a contributor cannot work out for itself without repeating
     * this add-on's definition of a thread.
     *
     * "More than one message" is the rule EXO-89373 already applies to the per-message
     * menu, and it is counted on the reader's `messages` — which holds the exchange
     * without its drafts. A mail with an unsent reply under it is one mail being read,
     * not a conversation, and the conversation's actions would have nothing to work on.
     *
     * Answers a normalized object rather than null when nothing is open, so a consumer
     * reads one shape and only ever has to test `isThread`.
     *
     * @returns {Object} {isThread, threadId, messages, subject}
     */
    threadParams() {
      const messages = this.thread?.messages || [];
      return {
        isThread: !!this.thread?.threadId && messages.length > 1,
        threadId: this.thread?.threadId || null,
        messages,
        subject: this.thread?.subject || this.email?.subject || null,
      };
    },
    /**
     * Whether the opened message sits in a folder the interface may only read
     * (Trash), in which case this toolbar offers nothing that writes.
     *
     * @returns {Boolean} true when the mail actions must stay hidden
     */
    readOnly() {
      return this.$emailConnectorMailBoxService.isReadOnlyFolder(this.email?.folder);
    },
    /**
     * Whether the opened message is a trashed one, in which case this toolbar offers
     * restore and delete-permanently in place of the three withheld above.
     *
     * @returns {Boolean} true when the Trash actions belong here
     */
    trashActions() {
      return this.$emailConnectorMailBoxService.hasTrashActions(this.email?.folder);
    },
  },
  methods: {
    updateEmailReadStatus() {
      this.$root.$emit('update-email-read-status', false, [this.email.mailRemoteId]);
      this.$root.$emit('close-email-detail-drawer');
    },
    deleteEmail() {
      this.$root.$emit('delete-email', [this.email.mailRemoteId]);
      this.$root.$emit('close-email-detail-drawer');
    },
    archiveEmail() {
      this.$root.$emit('archive-email', [this.email.mailRemoteId]);
      this.$root.$emit('close-email-detail-drawer');
    },
    /**
     * Puts the opened message back into the inbox and closes the reader — the message
     * is leaving the Trash listing behind it, so there is nothing left to read here.
     *
     * @returns {void}
     */
    restoreEmail() {
      this.$root.$emit('restore-email', [this.email.mailRemoteId]);
      this.$root.$emit('close-email-detail-drawer');
    },
    /**
     * Asks first, then destroys. The reader closes only once the user has confirmed,
     * so cancelling leaves them looking at the message they decided to keep.
     *
     * @returns {void}
     */
    purgeEmail() {
      this.$root.$emit('open-purge-email-confirm-popup', [this.email.mailRemoteId], () => this.$root.$emit('close-email-detail-drawer'));
    },
  }
};
</script>