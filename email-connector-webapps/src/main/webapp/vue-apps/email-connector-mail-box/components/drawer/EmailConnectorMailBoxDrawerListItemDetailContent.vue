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
  <!-- In a thread the outer ThreadContent already provides the horizontal margin, so
       the message must not add its own or its avatar drifts right of the collapsed rows. -->
  <v-list :class="['py-0', hideSubject ? 'my-0' : 'my-5 mx-4']">
    <v-list-item
      v-if="!hideSubject"
      class="px-0 pb-1 height-auto">
      <v-list-item-content class="py-0 text-title text-wrap overflow-visible">
        <v-list-item-title v-text="email.subject" class="text-wrap overflow-visible" />
      </v-list-item-content>
    </v-list-item>
    <!-- A mail scheduled to be sent (EXO-90434) is rendered as any message is, read-only:
         this line says so and offers its Edit, which takes it out of its schedule first
         -- the same slim line and text button as an unavailable message's retry. -->
    <div
      v-if="scheduled"
      class="d-flex align-center px-0 pb-3 scheduled-mail-read-only">
      <v-icon size="14" class="icon-default-color me-2">fas fa-info-circle</v-icon>
      <span class="text-subtitle">{{ $t('emailConnector.mailBox.scheduled.readOnly') }}</span>
      <v-btn
        :disabled="email.scheduledStatus === 'SENDING'"
        class="ms-2 scheduled-mail-edit"
        color="primary"
        text
        small
        @click="$emit('edit')">
        {{ $t('emailConnector.mailBox.scheduled.action.edit') }}
      </v-btn>
    </div>
    <!-- The sender asked to be notified when this message is read (EXO-90435): the
         banner, or the automatic answer once the message counts as displayed. -->
    <email-connector-read-receipt-banner
      v-if="!scheduled"
      :email="email"
      :auto-allowed="receiptAutoAllowed" />
    <v-list-item
      :class="['height-auto', recipientsClass]">
      <email-connector-mail-box-drawer-list-item-detail-sender-avatar 
        :email="avatarEmail" 
        class="me-3 my-0" />
      <v-list-item-content class="py-0">
        <v-list-item-title
          :class="['font-weight-bold mb-3', { clickable: collapsible }]"
          @click="collapsible && $emit('toggle-collapse')"
          v-text="senderLabel" />
        <v-list-item-subtitle class="text-wrap overflow-visible d-flex">
          <span class="me-1 text-wrap text-break-all">{{ recipients }}</span>
          <v-btn
            @click="toggleDetails"
            :title="recipientsToggleTooltip"
            width="20"
            height="20"
            min-width="20"
            class="mt-n1"
            icon>
            <v-icon size="8" class="icon-default-color">{{ chevronIcon }}</v-icon>
          </v-btn>
        </v-list-item-subtitle>
      </v-list-item-content>
      <!-- Scheduled: when it goes, in its state's colour, in place of the date; its own
           actions in place of a message's (favorite, reply, ⋮). -->
      <v-list-item-action
        v-if="scheduled"
        class="pt-4 my-0 d-flex flex-column align-end">
        <v-list-item-subtitle
          :class="stateColor"
          class="pb-1 d-flex align-center scheduled-mail-date">
          <v-icon
            :class="stateColor || 'icon-default-color'"
            class="me-1"
            size="12">
            far fa-clock
          </v-icon>
          {{ scheduledForLabel }}
        </v-list-item-subtitle>
        <v-list-item-subtitle
          v-if="stateText"
          :class="stateColor"
          class="pb-1 text-wrap text-end scheduled-mail-state">
          {{ stateText }}
        </v-list-item-subtitle>
        <v-menu
          v-if="scheduledActions.length"
          :nudge-top="-1"
          content-class="no-min-width border-radius z-index-modal overflow-hidden"
          offset-y
          left
          bottom
          attach>
          <template #activator="{ on, attrs }">
            <v-btn
              v-bind="attrs"
              :title="$t('emailConnector.mailBox.scheduled.actions')"
              :aria-label="$t('emailConnector.mailBox.scheduled.actions')"
              class="pa-0 scheduled-mail-menu"
              icon
              v-on="on">
              <v-icon size="20" class="icon-default-color">fa-ellipsis-v</v-icon>
            </v-btn>
          </template>
          <v-list dense>
            <v-list-item
              v-for="action in scheduledActions"
              :key="action.name"
              :data-action="action.name"
              class="scheduled-mail-action"
              @click="$emit('scheduled-action', action.name)">
              <v-list-item-icon class="me-2 my-auto">
                <v-icon size="14" class="icon-default-color">{{ action.icon }}</v-icon>
              </v-list-item-icon>
              <v-list-item-title>{{ action.label }}</v-list-item-title>
            </v-list-item>
          </v-list>
        </v-menu>
      </v-list-item-action>
      <v-list-item-action
        v-else
        class="pt-4 my-0 d-flex flex-column align-end">
        <v-list-item-subtitle class="pb-1" v-text="receivedDate" />
        <div class="d-flex flex-row align-center">
          <!-- The message's favorite (the server's \Flagged flag): toggling pushes to
               the mail server, so it follows to phone, Gmail, everywhere. -->
          <email-connector-mail-box-drawer-favorite-toggle
            :favorite="!!email.starred"
            :can-toggle="canToggleFavorite"
            :shared-owner="starSharedOwner"
            :size="18"
            @toggle="toggleFavorite" />
          <!-- Reply and the ⋮ menu (reply all, forward…) are withheld from a message
               whose full copy could not be read: its recipients and body are not
               known, and a reply would quote nothing and address nobody. -->
          <v-btn
            v-if="!unavailable"
            @click="openReplyEmailDrawer"
            :title="$t('emailConnector.mailBox.list.drawer.detail.reply.button.title')"
            icon>
            <v-icon size="20" class="icon-default-color">fa-reply</v-icon>
          </v-btn>
          <email-connector-mail-box-drawer-list-item-detail-action-menu
            v-if="!unavailable"
            :email="email"
            :in-thread="inThread" />
        </div>
      </v-list-item-action>
    </v-list-item>
    <email-connector-mail-box-drawer-list-item-detail-header 
      v-if="expandedHeader"
      :email="email" />
    <div
      v-if="unavailable"
      class="px-0 pb-4">
      <div class="text-light-color text-truncate mb-2">{{ excerpt }}</div>
      <div class="d-flex align-center">
        <span class="text-subtitle">{{ $t('emailConnector.mailBox.list.drawer.detail.unavailable') }}</span>
        <v-btn
          class="ms-2"
          color="primary"
          text
          small
          @click="retryRead">
          {{ $t('emailConnector.mailBox.list.drawer.detail.unavailable.retry') }}
        </v-btn>
      </div>
    </div>
    <email-connector-mail-box-drawer-list-item-detail-body
      v-else
      :expanded-drawer="expandedDrawer"
      :email-body="emailBody"
      :html-body="htmlBody" />
    <email-connector-mail-box-drawer-list-item-detail-attachments
      :email-attachments="emailAttachments"
      v-if="hasAttachments" />
  </v-list>
</template>

<script>
import { personLabel } from '../../js/EmailRecipientDisplay.js';
import { SCHEDULED_ACTIONS } from '../../js/EmailConnectorScheduledSendService.js';

export default {
  data() {
    return {
      expandedHeader: false,
    };
  },
  created() {
    this.$root.$on('email-detail-drawer-closed', () => {
      this.expandedHeader = false;
    });
  },
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    expandedDrawer: {
      type: Boolean,
      default: false,
    },
    // In a thread the subject is shown once at the top, so each message hides its own.
    hideSubject: {
      type: Boolean,
      default: false,
    },
    // In a thread an expanded message collapses again when its sender line is clicked.
    collapsible: {
      type: Boolean,
      default: false,
    },
    // Whether the conversation this message is rendered in holds more than one message.
    // Carried down to the 3-dots menu, which offers the per-message AI actions only
    // there — see EmailConnectorMailBoxDrawerListItemDetailActionMenuItems.
    inThread: {
      type: Boolean,
      default: false,
    },
    // The Scheduled view's row of this mail, when the reader was opened on it from that
    // view (EXO-90434): its actions and its reason are the row's, run by the view.
    scheduledRow: {
      type: Object,
      default: null,
    },
    // Whether this message counts as displayed to the user, so a read receipt its
    // sender asked for may leave on its own under the ALWAYS policy (EXO-90435): false
    // unless the reader says so, as a message it only shows in passing is not read.
    receiptAutoAllowed: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    /**
     * Whether the message is a draft scheduled to be sent: shown read-only, with when
     * it goes in place of a date (EXO-90434).
     *
     * @returns {Boolean} true for a scheduled draft
     */
    scheduled() {
      return !!this.email?.scheduled;
    },
    /**
     * The message as its avatar reads it: a scheduled draft is the user's own, and its
     * row may carry no sender picture (or no sender at all, when only the Scheduled
     * view's row is known), so it shows the user's own platform avatar.
     *
     * @returns {Object} the message, or a copy with the user's avatar
     */
    avatarEmail() {
      if (!this.scheduled || this.email.sender?.avatarUrl) {
        return this.email;
      }
      const userName = window.eXo?.env?.portal?.userName || '';
      return {
        ...this.email,
        sender: { ...(this.email.sender || {}), avatarUrl: `/portal/rest/v1/social/users/${encodeURIComponent(userName)}/avatar` },
      };
    },
    /**
     * @returns {String} "Scheduled for {date}", in the zone the date was chosen in
     */
    scheduledForLabel() {
      return this.$t('emailConnector.mailBox.scheduled.at', {
        0: this.$emailConnectorMailBoxService.formatScheduledDate(this.email.scheduledDate, this.email.scheduledTimeZone),
      });
    },
    /**
     * What the scheduled mail's state says: the Scheduled view's row when there is one,
     * which also carries the reason, else the status the draft's row carries.
     *
     * @returns {Object} {key, reasonKey, color}, or null while it simply waits
     */
    stateLine() {
      return this.scheduled
        ? this.$emailConnectorMailBoxService.scheduledStateLine(this.scheduledRow || { status: this.email.scheduledStatus })
        : null;
    },
    /**
     * @returns {String} the state's colour class, nothing while it simply waits
     */
    stateColor() {
      return this.stateLine?.color || '';
    },
    /**
     * The state's words, nothing while it simply waits. A conversation's row carries
     * the status and not the reason, so without the view's row a mail not sent says
     * where the reason is rather than inventing one.
     *
     * @returns {String} the words
     */
    stateText() {
      if (!this.stateLine) {
        return '';
      }
      if (!this.stateLine.reasonKey) {
        return this.$t(this.stateLine.key);
      }
      return this.scheduledRow
        ? this.$t(this.stateLine.key, { 0: this.$t(this.stateLine.reasonKey) })
        : this.$t('emailConnector.mailBox.list.drawer.thread.draft.notSent');
    },
    /**
     * The actions the Scheduled view's row offers, {name, icon, label}: none without
     * that row, since only the view runs them -- the read-only line's Edit is offered
     * everywhere.
     *
     * @returns {Array} the actions
     */
    scheduledActions() {
      if (!this.scheduledRow) {
        return [];
      }
      return this.$emailConnectorMailBoxService.scheduledActions(this.scheduledRow)
        .map(name => ({ name, icon: SCHEDULED_ACTIONS[name].icon, label: this.$t(SCHEDULED_ACTIONS[name].label) }));
    },
    receivedDate() {
      return this.$emailConnectorMailBoxService.formatDateString(this.email.receivedDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
    },
    // A message from the user's own Sent folder is shown as "Me", like Gmail.
    senderLabel() {
      if (this.scheduled) {
        // The user's own: their name when the draft's row carries it, else "Me".
        return this.email.sender?.name || this.$t('emailConnector.mailBox.list.drawer.detail.me');
      }
      return this.email.folder === 'SENT'
        ? this.$t('emailConnector.mailBox.list.drawer.detail.me')
        : this.email.sender.name;
    },
    chevronIcon() {
      return this.expandedHeader ? 'fa-chevron-up' : 'fa-chevron-down';
    },
    recipients() {
      // A draft's row may leave cc and bcc out (the Scheduled view's row does).
      const recipients = [...(this.email.to || []), ...(this.email.cc || []), ...(this.email.bcc || [])];
      if (recipients.length === 0) {
        return '';
      }
      if (recipients.length <= 3) {
        return `${this.$t('emailConnector.mailBox.list.drawer.detail.to')} ${recipients.map(item => this.recipientLabel(item)).join(', ')}`;
      }
      else {
        const firstRecipients = recipients.slice(0, 3);
        const remainingCount = recipients.length - 3;
        return `${this.$t('emailConnector.mailBox.list.drawer.detail.to')} ${firstRecipients.map(item => this.recipientLabel(item)).join(', ')}, +${remainingCount}`;
      }
    },
    recipientsClass() {
      return this.expandedHeader && 'px-0 pb-3' || 'px-0 pb-8';
    },
    recipientsToggleTooltip() {
      return this.expandedHeader ? this.$t('emailConnector.mailBox.list.drawer.detail.hideRecipients') : this.$t('emailConnector.mailBox.list.drawer.detail.displayRecipients');
    },
    // Read off the message in script rather than in the template: the template
    // compiler of the component tests (vue-jest) does not parse optional chaining.
    emailBody() {
      return this.email.content?.body;
    },
    htmlBody() {
      return this.email.content?.html !== false;
    },
    excerpt() {
      return this.email.content?.excerpt || '';
    },
    // The full copy of this message could not be read (see settleListingRow).
    unavailable() {
      return !!this.email?.unavailable;
    },
    hasAttachments() {
      return this.emailAttachments.length > 0;
    },
    emailAttachments() {
      return this.email.content?.attachments || [];
    },
    isMobile() {
      return this.$vuetify.breakpoint.smAndDown;
    },
    // An INBOX message, or one of a shared mailbox where the user holds w (canStar,
    // EXO-90550), can be toggled; a favorite Sent/Archive copy of the user's own in a
    // conversation still shows its (read-only) favorite.
    canToggleFavorite() {
      return this.$emailConnectorMailBoxService.canStar(this.email.folder);
    },
    /**
     * The owner of the shared mailbox whose INBOX this message is in -- said on the star
     * there only, where it is true: her Favorites list her INBOX stars alone (EXO-90550).
     * Empty elsewhere, and in the user's own mailbox.
     *
     * @returns {String} the owner's name, or empty
     */
    starSharedOwner() {
      const entry = this.$emailConnectorMailBoxService.sharedMailboxOfFolder(this.email.folder);
      return entry && entry.folderKey === this.email.folder ? entry.ownerFullName || '' : '';
    },
  },
  methods: {
    /**
     * What one name on the recipients line reads: "Me" for the reader themselves,
     * otherwise the person's name — and their address when they have no name, which
     * this line used to render as a gap between two commas.
     *
     * @param {object} recipient - the addressed recipient
     * @returns {string} the label to show
     */
    recipientLabel(recipient) {
      return recipient.currentUser && this.$t('emailConnector.mailBox.list.drawer.detail.me') || personLabel(recipient);
    },
    toggleDetails() {
      this.expandedHeader = !this.expandedHeader;
    },
    // The optimistic flip and the service call are centralized in the mailbox
    // drawer's handler, which also rolls this very object back (through the
    // thread's own listener) if the mail server refuses the flag.
    toggleFavorite() {
      this.$root.$emit('update-email-favorite-status', !this.email.starred, [this.email.mailRemoteId], false, this.email.folder || 'INBOX');
    },
    /**
     * Asks the drawer holding this message to read it again, through the same request
     * it opens a message with.
     *
     * @returns {void}
     */
    retryRead() {
      this.$root.$emit('retry-email-read', this.email);
    },
    openReplyEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email);
    }
  }
};
</script>