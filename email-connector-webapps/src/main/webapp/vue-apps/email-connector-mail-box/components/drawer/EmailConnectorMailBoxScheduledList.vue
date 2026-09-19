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
  <!-- The "Scheduled" view (EXO-90434), in place of a folder's list: the mails waiting
       to be sent at a date, soonest first, read from their own endpoint -- they are
       drafts, frozen, and no folder listing holds them. On the pane's own background,
       like a folder's rows (EXO-90415): nothing here paints a white surface over it. No
       loading bar of its own either (EXO-90412): what it waits on is told to the drawer
       (the loading event), whose header bar is the only one. -->
  <div class="scheduled-email-list">
    <v-list
      v-if="items.length"
      class="py-0 transparent"
      dense>
      <template v-for="(scheduled, index) in items">
        <v-divider v-if="index > 0" :key="`divider-${scheduled.draftLocalId}`" />
        <email-connector-mail-box-scheduled-list-item
          :key="scheduled.draftLocalId"
          :scheduled="scheduled"
          :busy="busyIds.includes(scheduled.draftLocalId)"
          :opened="openedId === scheduled.draftLocalId"
          :expanded="compact"
          @open="open"
          @action="onAction" />
      </template>
    </v-list>
    <div
      v-if="hasMore"
      class="d-flex justify-center py-2">
      <v-btn
        :loading="loading"
        class="scheduled-email-load-more"
        color="primary"
        small
        text
        @click="loadMore">
        {{ $t('emailConnector.mailBox.scheduled.loadMore') }}
      </v-btn>
    </div>
    <div
      v-if="loaded && !items.length"
      :class="compact ? 'pt-10' : 'pt-16'"
      class="text-center px-4 scheduled-email-empty">
      <v-icon :size="compact ? 32 : 60" class="icon-default-color">far fa-clock</v-icon>
      <div class="mt-2 text-subtitle text-sub-title text-wrap">
        {{ $t('emailConnector.mailBox.scheduled.empty') }}
      </div>
    </div>
    <!-- Reschedule: the composer's own date and time card (email-connector-schedule-picker)
         in the platform's standard popup, as exo-confirm-dialog draws it -- which has no
         room for a form, hence the add-on's copy with a slot (email-connector-mail-box-
         popup). Not a bare v-dialog: that one never tells the platform it is open
         (modalOpened), so the drawers' overlay stays over it and takes its clicks. One
         confirm: the popup's Reschedule, the picker's own check left out. -->
    <email-connector-mail-box-popup
      ref="rescheduleModal"
      :title="$t('emailConnector.mailBox.scheduled.reschedule.title')"
      :ok-label="$t('emailConnector.mailBox.scheduled.action.reschedule')"
      :cancel-label="$t('emailConnector.mailBox.scheduled.reschedule.cancel')"
      :ok-disabled="!rescheduleValid"
      :loading="rescheduling"
      width="460px"
      persistent
      @ok="$refs.reschedulePicker && $refs.reschedulePicker.confirm()"
      @dialog-closed="rescheduled = null">
      <email-connector-schedule-picker
        v-if="rescheduled"
        ref="reschedulePicker"
        :key="rescheduled.draftLocalId"
        :value="rescheduled.scheduledDate"
        :loading="rescheduling"
        class="scheduled-email-reschedule-picker"
        hide-confirm
        @valid="rescheduleValid = $event"
        @confirm="reschedule" />
    </email-connector-mail-box-popup>
    <exo-confirm-dialog
      ref="scheduledConfirmDialog"
      :title="confirmation && confirmation.title"
      :message="confirmation && confirmation.message"
      :ok-label="confirmation && confirmation.okLabel"
      :cancel-label="$t('emailConnector.mailBox.scheduled.confirm.cancelButton')"
      @ok="runConfirmed" />
  </div>
</template>

<script>
import { SCHEDULED_PAGE_SIZE } from '../../js/EmailConnectorScheduledSendService.js';

// The largest page the server serves: it clamps the limit to it (EmailBoxRest).
const MAX_SCHEDULED_READ = 100;

export default {
  props: {
    // Whether it sits in the full-screen list column rather than in the narrow drawer.
    compact: {
      type: Boolean,
      default: false,
    },
    // Changes whenever the server's count of the view does -- a mail sent by the
    // dispatcher, one that failed -- so the list re-reads itself (see the drawer's
    // scheduledViewSignal).
    signal: {
      type: String,
      default: '',
    },
  },
  data: () => ({
    items: [],
    loading: false,
    loaded: false,
    hasMore: false,
    // How many whole pages the list holds: where "Show more" reads next. Counted rather
    // than derived from the rows' number, which a dropped duplicate leaves off a page
    // boundary -- and the server floors an offset to its page.
    pagesRead: 0,
    // The mails an action is running on: their menu waits, the drawer's bar says so.
    busyIds: [],
    // The mail the reschedule popup is open for.
    rescheduled: null,
    // Whether its picker holds an instant: the popup's Reschedule waits for one.
    rescheduleValid: false,
    rescheduling: false,
    // The question the confirmation dialog is asking: {title, message, okLabel, run}.
    confirmation: null,
    // The mail the reader was opened on from this view, by its draft's local id.
    openedId: null,
  }),
  computed: {
    /**
     * Whether the view is waiting on the server: a read, an action on a row, a
     * reschedule. Told to the drawer, whose header bar shows it (EXO-90412).
     *
     * @returns {Boolean} true while something is on its way
     */
    waiting() {
      return this.loading || this.rescheduling || this.busyIds.length > 0;
    },
  },
  watch: {
    signal() {
      this.reload();
    },
    waiting: {
      immediate: true,
      handler(waiting) {
        this.$emit('loading', waiting);
      },
    },
  },
  created() {
    // Which read is current: an answer for an older one is dropped. Plain: nothing
    // renders it.
    this.readRequest = 0;
    this.$root.$on('scheduled-emails-changed', this.reload);
    // The reader offers the opened mail's actions; they run here, where their questions
    // and their dialog are.
    this.$root.$on('scheduled-email-action', this.onAction);
    // The reader moved off the mail, or its drawer closed: no row stays lit.
    this.$root.$on('set-opened', this.onSetOpened);
    this.$root.$on('email-detail-drawer-closed', this.onReaderClosed);
    this.reload();
  },
  beforeDestroy() {
    this.$root.$off('scheduled-emails-changed', this.reload);
    this.$root.$off('scheduled-email-action', this.onAction);
    this.$root.$off('set-opened', this.onSetOpened);
    this.$root.$off('email-detail-drawer-closed', this.onReaderClosed);
    // Gone with its wait: the drawer's bar must not stay on for a list nobody sees.
    this.$emit('loading', false);
  },
  methods: {
    /**
     * Reads the view again from its first page, as far as it was read. The server pages
     * by offset / limit, so the read is kept to whole pages: a length that is a multiple
     * of the page size is what lets the next "Show more" land on the next page rather
     * than inside one already listed. Capped at the largest page the server serves.
     *
     * @returns {Promise<void>} resolved once read
     */
    reload() {
      const pages = Math.max(1, Math.ceil(this.items.length / SCHEDULED_PAGE_SIZE), this.pagesRead);
      return this.read(0, Math.min(pages * SCHEDULED_PAGE_SIZE, MAX_SCHEDULED_READ), true);
    },
    /**
     * Reads the next page.
     *
     * @returns {Promise<void>} resolved once read
     */
    loadMore() {
      return this.read(this.pagesRead * SCHEDULED_PAGE_SIZE, SCHEDULED_PAGE_SIZE, false);
    },
    /**
     * Reads a page of the view. The server pages by multiples of the page size, so a
     * reload of several pages asks for them as one.
     *
     * @param {Number} offset the first row
     * @param {Number} limit how many rows
     * @param {Boolean} replace whether the page replaces the list, or follows it
     * @returns {Promise<void>} resolved once read, or dropped
     */
    read(offset, limit, replace) {
      const request = ++this.readRequest;
      this.loading = true;
      return this.$emailConnectorMailBoxService.getScheduledEmails(offset, limit)
        .then(page => {
          if (request !== this.readRequest) {
            return;
          }
          const rows = page || [];
          if (replace) {
            this.items = rows;
            this.pagesRead = limit / SCHEDULED_PAGE_SIZE;
          } else {
            this.pagesRead++;
            // A mail listed already is not listed twice, whatever moved in between.
            const listed = new Set(this.items.map(item => item.draftLocalId));
            this.items = [...this.items, ...rows.filter(row => !listed.has(row.draftLocalId))];
          }
          this.hasMore = rows.length === limit;
          this.followOpened();
        })
        .catch(() => {
          if (request === this.readRequest) {
            this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.scheduled.loadError'), 'error');
          }
        })
        .finally(() => {
          if (request === this.readRequest) {
            this.loading = false;
            this.loaded = true;
          }
        });
    },
    /**
     * Opens a mail read-only in the reader, the way a folder's row opens its mail: in
     * the full-screen reader beside the list, else in the mail drawer. The reader shows
     * the draft in its conversation, read-only (PO decision (a)), with this row's
     * actions (see scheduledReaderRow).
     *
     * @param {Object} scheduled the scheduled mail
     * @returns {void}
     */
    open(scheduled) {
      const row = this.$emailConnectorMailBoxService.scheduledReaderRow(scheduled);
      if (this.compact) {
        this.$root.$emit('open-email-thread-content', row);
      } else {
        this.$root.$emit('open-email-thread-drawer', row, [row], false, null);
      }
      // After the opening: it clears the lit row (set-opened) before lighting its own.
      this.openedId = scheduled.draftLocalId;
    },
    /**
     * Tells the reader what became of the mail it shows, once the view was read again:
     * its new date or state, or that it is no longer scheduled -- sent, cancelled,
     * discarded, taken to the composer -- and the reader then lets it go.
     *
     * @returns {void}
     */
    followOpened() {
      if (!this.openedId) {
        return;
      }
      const scheduled = this.items.find(item => item.draftLocalId === this.openedId);
      this.$root.$emit('scheduled-email-updated', this.openedId,
        scheduled ? this.$emailConnectorMailBoxService.scheduledReaderRow(scheduled) : null);
      if (!scheduled) {
        this.openedId = null;
      }
    },
    /**
     * Forgets the opened mail when the full-screen reader shows nothing any more.
     *
     * @param {Number} mailRemoteId what the reader now shows, nothing for nothing
     * @returns {void}
     */
    onSetOpened(mailRemoteId) {
      if (mailRemoteId == null) {
        this.openedId = null;
      }
    },
    /**
     * Forgets the opened mail when the mail drawer showing it closed.
     *
     * @returns {void}
     */
    onReaderClosed() {
      this.openedId = null;
    },
    /**
     * Runs a row's action, asking first for the ones that send or lose something.
     *
     * @param {String} action the action name (see scheduledActions)
     * @param {Object} scheduled the scheduled mail
     * @returns {void}
     */
    onAction(action, scheduled) {
      switch (action) {
      case 'edit':
        this.$root.$emit('edit-scheduled-email', {
          draftLocalId: scheduled.draftLocalId,
          scheduledDate: scheduled.scheduledDate,
          timeZone: scheduled.timeZone,
          threadId: scheduled.threadId,
        });
        break;
      case 'reschedule':
        this.rescheduled = scheduled;
        this.$nextTick(() => this.$refs.rescheduleModal.open());
        break;
      case 'sendNow':
      case 'retry':
      case 'sendAgain':
        this.confirm({
          title: this.$t('emailConnector.mailBox.scheduled.sendNow.confirm.title'),
          message: this.$t(action === 'sendAgain'
            ? 'emailConnector.mailBox.scheduled.sendNow.confirm.uncertainMessage'
            : 'emailConnector.mailBox.scheduled.sendNow.confirm.message'),
          okLabel: this.$t('emailConnector.mailBox.scheduled.sendNow.confirm.ok'),
          run: () => this.sendNow(scheduled),
        });
        break;
      case 'cancel':
      case 'moveToDrafts':
        this.confirm({
          title: this.$t('emailConnector.mailBox.scheduled.cancel.confirm.title'),
          message: this.$t('emailConnector.mailBox.scheduled.cancel.confirm.message'),
          okLabel: this.$t('emailConnector.mailBox.scheduled.cancel.confirm.ok'),
          run: () => this.cancel(scheduled),
        });
        break;
      case 'discard':
        this.confirm({
          title: this.$t('emailConnector.mailBox.scheduled.discard.confirm.title'),
          message: this.$t('emailConnector.mailBox.scheduled.discard.confirm.message'),
          okLabel: this.$t('emailConnector.mailBox.scheduled.discard.confirm.ok'),
          run: () => this.discard(scheduled),
        });
        break;
      default:
        break;
      }
    },
    /**
     * Asks a question, and runs its answer on "ok".
     *
     * @param {Object} confirmation {title, message, okLabel, run}
     * @returns {void}
     */
    confirm(confirmation) {
      this.confirmation = confirmation;
      this.$nextTick(() => this.$refs.scheduledConfirmDialog.open());
    },
    /**
     * Runs what the confirmed question was about.
     *
     * @returns {void}
     */
    runConfirmed() {
      const run = this.confirmation?.run;
      this.confirmation = null;
      if (run) {
        run();
      }
    },
    /**
     * Gives the mail the date picked in the popup, which closes once it is taken; a
     * refusal leaves it open on the picked date, to pick another.
     *
     * @param {Number} scheduledDate the instant, epoch milliseconds
     * @param {String} timeZone the zone it was chosen in
     * @returns {Promise<void>} resolved once done or refused
     */
    reschedule(scheduledDate, timeZone) {
      const scheduled = this.rescheduled;
      if (!scheduled || this.rescheduling) {
        return Promise.resolve();
      }
      this.rescheduling = true;
      return this.$emailConnectorMailBoxService.rescheduleEmail(scheduled.draftLocalId, scheduledDate, timeZone)
        .then(updated => {
          this.$refs.rescheduleModal?.close();
          this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.scheduled.reschedule.success', {
            0: this.$emailConnectorMailBoxService.formatScheduledDate(updated?.scheduledDate || scheduledDate,
              updated?.timeZone || timeZone),
          }), 'success');
          this.changed();
        })
        .catch(error => {
          this.refused(error);
          // A refusal (409) means the mail's state moved under the user: shown as it is.
          this.changed();
        })
        .finally(() => this.rescheduling = false);
    },
    /**
     * Sends the mail now, or again. The request answers once the mail server has, which
     * may take minutes: the row says it is sending meanwhile, and the user is told so.
     *
     * @param {Object} scheduled the scheduled mail
     * @returns {Promise<void>} resolved once the server answered
     */
    sendNow(scheduled) {
      return this.onRow(scheduled, () => {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.scheduled.sendNow.progress'), 'info');
        return this.$emailConnectorMailBoxService.sendScheduledEmailNow(scheduled.draftLocalId)
          .then(result => {
            if (result?.status === 'SENT') {
              this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.scheduled.sendNow.success'), 'success');
              // The Sent folder receives its copy from the mail server, a moment later:
              // the mailbox keeps re-reading for it, as after any send.
              this.$root.$emit('email-sent');
              return;
            }
            if (result?.status === 'SCHEDULED') {
              // The mail server could not even be reached: nothing went out, and the mail
              // is back in its schedule for an automatic retry.
              this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.scheduled.sendNow.retryLater'), 'warning');
              return;
            }
            const line = this.$emailConnectorMailBoxService.scheduledStateLine(result);
            const reason = line?.reasonKey ? this.$t(line.key, { 0: this.$t(line.reasonKey) }) : this.$t(line?.key
              || 'emailConnector.mailBox.scheduled.action.error');
            this.$root.$emit('alert-message', reason, 'error');
          });
      });
    },
    /**
     * Takes the mail out of its schedule: back to Drafts, content kept.
     *
     * @param {Object} scheduled the scheduled mail
     * @returns {Promise<void>} resolved once done or refused
     */
    cancel(scheduled) {
      return this.onRow(scheduled, () => this.$emailConnectorMailBoxService.cancelScheduledEmail(scheduled.draftLocalId)
        .then(() => this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.scheduled.cancel.success'), 'success')));
    },
    /**
     * Throws the mail away: the draft goes, and its schedule with it.
     *
     * @param {Object} scheduled the scheduled mail
     * @returns {Promise<void>} resolved once done or refused
     */
    discard(scheduled) {
      return this.onRow(scheduled, () => this.$emailConnectorMailBoxService.deleteDraft(scheduled.draftLocalId)
        .then(() => this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.scheduled.discard.success'), 'success')));
    },
    /**
     * Runs an action on one row: the row is busy meanwhile, a refusal is said in the
     * user's words, and the view and the folder counts are read again either way --
     * a refusal (409) means the mail's state moved under the user.
     *
     * @param {Object} scheduled the scheduled mail
     * @param {Function} action runs the request, answers a promise
     * @returns {Promise<void>} resolved once done or refused
     */
    onRow(scheduled, action) {
      const id = scheduled.draftLocalId;
      if (this.busyIds.includes(id)) {
        return Promise.resolve();
      }
      this.busyIds.push(id);
      return Promise.resolve()
        .then(action)
        .catch(error => this.refused(error))
        .finally(() => {
          this.busyIds = this.busyIds.filter(busy => busy !== id);
          this.changed();
        });
    },
    /**
     * Says why a request was refused, in the user's words.
     *
     * @param {Error} error the refusal
     * @returns {void}
     */
    refused(error) {
      this.$root.$emit('alert-message', this.$emailConnectorMailBoxService.scheduledErrorMessage(error, this,
        'emailConnector.mailBox.scheduled.action.error'), 'error');
    },
    /**
     * Reads the view again, and has the mailbox re-read its folders: the view's count and
     * warning colour, Drafts' count.
     *
     * @returns {void}
     */
    changed() {
      this.reload();
      this.$root.$emit('refresh-email-box');
    },
  },
};
</script>
