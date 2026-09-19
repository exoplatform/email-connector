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
  <!-- A reply in progress, at the bottom of the conversation it answers.

       Deliberately NOT a thread message: no avatar, no expand chrome, no date on
       the right. Those all say "this happened"; a draft has not happened. What it
       gets instead is a marker, who it is addressed to, and the first line of what
       has been written so far — enough to recognise which unfinished reply this is
       without pretending it is mail. -->
  <!-- A reply scheduled to be sent (EXO-90434, PO decision (a)): READ-ONLY here. It is
       frozen until its schedule is cancelled, so nothing on this strip edits it in place:
       it says when it goes, and its Edit takes it out of its schedule first -- the same
       Edit as the Scheduled view's. Opened from the Scheduled view (scheduledRow), it is
       what the reader shows: its whole text and its attachments' names, wrapped, and the
       row's own menu of actions in place of the lone Edit. -->
  <div
    v-if="scheduled"
    class="d-flex align-center px-3 py-3 rounded ec-thread-draft ec-thread-draft-scheduled"
    style="border: 1px dashed var(--v-borderColor, #e1e8ee);">
    <v-icon
      :class="stateLine ? stateLine.color : 'primary--text'"
      class="flex-shrink-0 me-2"
      size="14">
      fa-clock
    </v-icon>
    <div class="d-flex flex-column no-min-width flex-grow-1">
      <span class="font-weight-bold text-truncate scheduled-draft-date">{{ scheduledForLabel }}</span>
      <template v-if="scheduledRow">
        <span class="text-wrap scheduled-draft-recipients">{{ recipientsLabel }}</span>
        <span class="text-wrap text-color mt-2 scheduled-draft-text">{{ snippet }}</span>
        <div
          v-for="(attachment, index) in attachments"
          :key="`attachment-${index}`"
          class="d-flex align-center mt-1 caption scheduled-draft-attachment">
          <v-icon size="12" class="icon-default-color me-1">fa-paperclip</v-icon>
          <span class="text-truncate">{{ attachment.name }}</span>
        </div>
      </template>
      <div v-else class="d-flex align-center no-min-width">
        <span class="flex-shrink-0 text-truncate" style="max-width: 45%">{{ recipientsLabel }}</span>
        <span class="text-light-color ms-3 text-truncate">{{ snippet }}</span>
      </div>
      <span
        v-if="stateText"
        :class="stateLine.color"
        class="caption text-wrap scheduled-draft-state">
        {{ stateText }}
      </span>
    </div>
    <v-menu
      v-if="scheduledRow && actions.length"
      offset-y
      left>
      <template #activator="{ on, attrs }">
        <v-btn
          :aria-label="$t('emailConnector.mailBox.scheduled.actions')"
          :title="$t('emailConnector.mailBox.scheduled.actions')"
          class="ms-2 flex-shrink-0 align-self-start scheduled-draft-menu"
          icon
          small
          v-bind="attrs"
          v-on="on">
          <v-icon size="16" class="icon-default-color">fas fa-ellipsis-v</v-icon>
        </v-btn>
      </template>
      <v-list dense>
        <v-list-item
          v-for="action in actions"
          :key="action.name"
          :data-action="action.name"
          class="scheduled-draft-action"
          @click="$emit('action', action.name)">
          <v-list-item-icon class="me-2 my-auto">
            <v-icon size="14" class="icon-default-color">{{ action.icon }}</v-icon>
          </v-list-item-icon>
          <v-list-item-title>{{ action.label }}</v-list-item-title>
        </v-list-item>
      </v-list>
    </v-menu>
    <v-btn
      v-else-if="!scheduledRow"
      :disabled="draft.scheduledStatus === 'SENDING'"
      class="ms-2 flex-shrink-0 scheduled-draft-edit"
      color="primary"
      small
      text
      @click.stop="$emit('edit')">
      {{ $t('emailConnector.mailBox.list.drawer.thread.draft.edit') }}
    </v-btn>
  </div>
  <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
  <div
    v-else
    class="clickable d-flex align-center px-3 py-3 rounded ec-thread-draft"
    style="border: 1px dashed var(--v-borderColor, #e1e8ee);"
    tabindex="0"
    :aria-label="$t('emailConnector.mailBox.list.drawer.thread.draft.resume')"
    :title="$t('emailConnector.mailBox.list.drawer.thread.draft.resume')"
    @click="$emit('resume')"
    @keydown.enter="$emit('resume')"
    @keydown.space.prevent="$emit('resume')">
    <span class="error--text font-weight-bold flex-shrink-0 me-3">
      {{ $t('emailConnector.mailBox.list.drawer.thread.draft.label') }}
    </span>
    <div class="d-flex align-center no-min-width flex-grow-1">
      <span class="flex-shrink-0 text-truncate" style="max-width: 45%">{{ recipientsLabel }}</span>
      <span class="text-light-color ms-3 text-truncate">{{ snippet }}</span>
    </div>
    <v-btn
      icon
      small
      class="ms-2 flex-shrink-0"
      :aria-label="$t('emailConnector.mailBox.list.drawer.thread.draft.discard')"
      :title="$t('emailConnector.mailBox.list.drawer.thread.draft.discard')"
      @click.stop="$emit('discard')">
      <v-icon size="14" class="icon-default-color">fas fa-trash</v-icon>
    </v-btn>
  </div>
</template>

<script>
import { personLabel } from '../../js/EmailRecipientDisplay.js';
import { unquotedText } from '../../js/EmailQuotedHistoryFold.js';
import { SCHEDULED_ACTIONS } from '../../js/EmailConnectorScheduledSendService.js';

export default {
  props: {
    // The draft row, as the conversation query returned it.
    draft: {
      type: Object,
      default: () => null,
    },
    // The Scheduled view's row of this draft, when the reader was opened on it from
    // that view (EXO-90434): its actions and its reason are the row's.
    scheduledRow: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    /**
     * Whether the draft is scheduled to be sent at a date: then it is shown read-only.
     *
     * @returns {Boolean} true for a scheduled draft
     */
    scheduled() {
      return !!this.draft?.scheduled;
    },
    /**
     * @returns {String} "Scheduled for {date}", in the zone the date was chosen in
     */
    scheduledForLabel() {
      return this.$t('emailConnector.mailBox.list.drawer.thread.draft.scheduledFor', {
        0: this.$emailConnectorMailBoxService.formatScheduledDate(this.draft.scheduledDate, this.draft.scheduledTimeZone),
      });
    },
    /**
     * @returns {Object} what the scheduled draft's state says, or null while it waits
     */
    stateLine() {
      return this.$emailConnectorMailBoxService.scheduledStateLine(this.scheduledRow
        || { status: this.draft?.scheduledStatus });
    },
    /**
     * The state line's words, nothing while it simply waits. A conversation's row
     * carries the status and not the reason (the backend sets scheduled, scheduledDate,
     * scheduledTimeZone and scheduledStatus on reads), so a mail not sent says where
     * the reason is rather than inventing one.
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
      // The Scheduled view's row carries the reason: said, as the view says it.
      return this.scheduledRow
        ? this.$t(this.stateLine.key, { 0: this.$t(this.stateLine.reasonKey) })
        : this.$t('emailConnector.mailBox.list.drawer.thread.draft.notSent');
    },
    /**
     * @returns {Array} the actions the Scheduled view's row offers, {name, icon, label}
     */
    actions() {
      return this.$emailConnectorMailBoxService.scheduledActions(this.scheduledRow)
        .map(name => ({ name, icon: SCHEDULED_ACTIONS[name].icon, label: this.$t(SCHEDULED_ACTIONS[name].label) }));
    },
    /**
     * @returns {Array} the draft's attachments, as far as its row carries them
     */
    attachments() {
      return (this.draft?.content?.attachments || []).filter(attachment => attachment?.name);
    },
    /**
     * Who the draft is addressed to so far, or a placeholder while it is addressed
     * to nobody — which is a perfectly ordinary state for a draft to be in and must
     * not render as an empty gap.
     *
     * @returns {string} the recipients line
     */
    recipientsLabel() {
      const names = (this.draft?.to || [])
        .map(recipient => personLabel(recipient))
        .filter(Boolean);
      return names.length
        ? this.$t('emailConnector.mailBox.list.drawer.thread.draft.to', {0: names.join(', ')})
        : this.$t('emailConnector.mailBox.list.drawer.thread.draft.noRecipient');
    },
    /**
     * The first line of what the user has written, stripped of markup and of the
     * quoted message underneath it.
     *
     * A reply opens with the message it answers quoted below the cursor, so the raw
     * body of every unfinished reply in a conversation now starts with the same
     * words — and this row sits directly under the message those words are a copy
     * of. Showing them here would make each draft read like the mail above it and
     * tell the user nothing about which unfinished reply they are looking at. What
     * distinguishes it is the sentence above the quote, which is what this shows.
     *
     * @returns {string} the one-line preview
     */
    snippet() {
      return unquotedText(this.draft?.content?.body || '');
    },
  },
};
</script>
