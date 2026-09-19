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
  <exo-confirm-dialog
    ref="discardDraftsConfirmDialog"
    :title="title"
    :message="message"
    :ok-label="$t('emailConnector.mailBox.list.drawer.discard.confirm.button.discard')"
    :cancel-label="$t('emailConnector.mailBox.list.drawer.discard.confirm.button.cancel')"
    persistent
    @ok="discardDrafts" />
</template>

<script>
export default {
  data() {
    return {
      drafts: [],
      onConfirmed: null,
    };
  },
  computed: {
    /**
     * The drafts this dialog is actually about: the selected ones a scheduled send has
     * not frozen.
     *
     * A scheduled draft is held back here rather than sent and let fail. The server
     * would take it — discarding a scheduled draft cancels its schedule (EXO-90434) —
     * and that is precisely why it must not travel from a bulk Discard: cancelling a
     * send the user has planned is a decision of its own, and nothing in "Discard 6
     * drafts?" asks it. Cancelling the schedule first, where the schedule is shown, is
     * the one place that question reads as itself.
     *
     * `scheduled` is stamped on the row by the server, which EXO-90434 has now landed
     * (EmailBoxService#markScheduledDrafts), so this reads a real flag rather than an
     * always-absent one. It is still a GUARD rather than the ordinary path, and
     * deliberately: the same work also took the scheduled drafts OUT of the Drafts
     * listing (EmailBoxStorage#getUnscheduledDrafts -- the "Scheduled" view lists them,
     * and they may not be edited while they wait), so no row this dialog is handed
     * today carries the flag. What it covers is the case the server's own comment
     * leaves open -- "another folder's listing may still carry a draft row (none
     * today)" -- and the day the Drafts listing stops filtering. Held back rather than
     * trusted: the cost of the guard is a filter, the cost of its absence is a planned
     * send silently cancelled.
     *
     * @returns {Array<Object>} the draft rows that will be discarded
     */
    discardableDrafts() {
      return this.drafts.filter(draft => !draft.scheduled);
    },
    /**
     * The selected drafts held back because they are scheduled to be sent.
     *
     * @returns {Array<Object>} the draft rows that will be left alone
     */
    scheduledDrafts() {
      return this.drafts.filter(draft => !!draft.scheduled);
    },
    /**
     * The question, which names the count — the whole reason to ask again when several
     * rows are ticked. Two wordings rather than one carrying a "1 draft(s)": the
     * singular is the common case and reads as a sentence.
     *
     * @returns {String} the dialog's title
     */
    title() {
      const count = this.discardableDrafts.length;
      return count === 1
        ? this.$t('emailConnector.mailBox.list.drawer.discard.confirm.title')
        : this.$t('emailConnector.mailBox.list.drawer.discard.confirm.titles', { 0: count });
    },
    /**
     * What the dialog says under the question: that this cannot be undone, and — when
     * the selection held scheduled drafts — that those are being left where they are
     * and why. Said BEFORE the user goes through with it rather than after, so the
     * count in the title and the count that will actually go are the same number and
     * the user can see which is which.
     *
     * @returns {String} the confirmation message
     */
    message() {
      const count = this.discardableDrafts.length;
      const discarded = count === 1
        ? this.$t('emailConnector.mailBox.list.drawer.discard.confirm.message')
        : this.$t('emailConnector.mailBox.list.drawer.discard.confirm.messages', { 0: count });
      if (!this.scheduledDrafts.length) {
        return discarded;
      }
      const skipped = this.scheduledDrafts.length === 1
        ? this.$t('emailConnector.mailBox.list.drawer.discard.confirm.scheduledKept')
        : this.$t('emailConnector.mailBox.list.drawer.discard.confirm.scheduledsKept', { 0: this.scheduledDrafts.length });
      return `${discarded} ${skipped}`;
    },
  },
  created() {
    this.$root.$on('open-discard-drafts-confirm-popup', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-discard-drafts-confirm-popup', this.open);
  },
  methods: {
    /**
     * Opens the confirmation for a set of draft rows.
     *
     * A selection of nothing but scheduled drafts asks no question: there is nothing to
     * confirm, so the reason they are being left alone is said outright instead of
     * behind a dialog whose Discard button would do nothing.
     *
     * @param {Array<Object>} drafts the selected draft rows, each carrying its
     *        draftLocalId and whether a scheduled send has frozen it
     * @param {Function} onConfirmed optional, run only if the user goes through with it
     * @returns {void}
     */
    open(drafts, onConfirmed) {
      this.drafts = drafts || [];
      this.onConfirmed = onConfirmed || null;
      if (!this.drafts.length) {
        return;
      }
      if (!this.discardableDrafts.length) {
        this.alertScheduledOnly();
        return;
      }
      this.$refs.discardDraftsConfirmDialog.open();
    },
    /**
     * Says that every draft the user ticked is scheduled, so none of them was thrown
     * away, and what to do about it.
     *
     * @returns {void}
     */
    alertScheduledOnly() {
      const count = this.scheduledDrafts.length;
      const key = count === 1
        ? 'emailConnector.mailBox.list.drawer.discard.scheduled.error'
        : 'emailConnector.mailBox.list.drawer.discard.scheduleds.error';
      document.dispatchEvent(new CustomEvent('alert-message', { detail: {
        alertType: 'warning',
        alertMessage: this.$t(key, { 0: count }),
      } }));
    },
    /**
     * Sends the discard. The mailbox drawer owns the requests, the refresh and the
     * outcome alert; this component only ever asks the question.
     *
     * @returns {void}
     */
    discardDrafts() {
      this.$root.$emit('discard-drafts', this.discardableDrafts.map(draft => draft.draftLocalId));
      if (this.onConfirmed) {
        this.onConfirmed();
      }
    },
  }
};
</script>
