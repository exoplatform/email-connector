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
  <exo-drawer
    id="newEmailDrawer"
    ref="newEmailDrawer"
    v-model="newEmailDrawer"
    right
    go-back-button
    allow-expand
    @closed="close">
    <template #title>
      <span>
        {{ title }}
      </span>
    </template>
    <template v-if="newEmailDrawer" #content>
      <email-connector-recipient-field
        v-model="to"
        class="mx-4"
        field-id="to"
        :label="$t('emailConnector.mailBox.newEmail.drawer.to.label')"
        :placeholder="$t('emailConnector.mailBox.newEmail.drawer.to.placeholder')" />
      <v-divider />
      <div>
        <email-connector-recipient-field
          v-model="cc"
          class="mx-4"
          field-id="cc"
          :label="$t('emailConnector.mailBox.newEmail.drawer.cc.label')"
          :placeholder="$t('emailConnector.mailBox.newEmail.drawer.cc.placeholder')" />
        <v-divider />
        <email-connector-recipient-field
          v-model="bcc"
          class="mx-4"
          field-id="bcc"
          :label="$t('emailConnector.mailBox.newEmail.drawer.bcc.label')"
          :placeholder="$t('emailConnector.mailBox.newEmail.drawer.bcc.placeholder')" />
        <v-divider />
      </div>
      <v-list-item class="pa-0 ms-1 me-4">
        <v-textarea
          v-model="email.subject"
          class="pt-0 textarea-no-border"
          autocomplete="subject"
          auto-grow
          rows="1"
          solo
          flat
          no-resize
          hide-details
          :placeholder="$t('emailConnector.mailBox.newEmail.drawer.subject.placeholder')" />
      </v-list-item>
      <v-divider />
      <div ref="editorWrapper" class="mx-4 mt-3">
        <rich-editor
          v-if="editorMaxHeight"
          ref="emailContent"
          v-model="email.content.body"
          :placeholder="$t('emailConnector.mailBox.newEmail.drawer.content.placeholder')"
          ck-editor-type="email"
          :auto-grow-max-height="editorMaxHeight"
          content-link-enabled
          :tag-enabled="false"
          disable-suggester
          hide-chars-count />
      </div>
      <email-connector-new-email-drawer-attachments
        v-model="attachments"
        :active="newEmailDrawer" />
    </template>
    <template #footer>
      <div class="d-flex align-center">
        <v-btn
          v-if="hasContent"
          :loading="discarding"
          @click="discardDraft()"
          class="btn"
          text>
          {{ $t('emailConnector.mailBox.newEmail.drawer.discard.label') }}
        </v-btn>
        <span v-if="draftStatusLabel" class="text-caption text-sub-title ms-2">{{ draftStatusLabel }}</span>
        <v-spacer />
        <v-btn
          :disabled="disabled"
          :loading="loading"
          @click="sendEmail()"
          class="btn btn-primary">
          {{ $t('emailConnector.mailBox.newEmail.drawer.send.label') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
const DEFAULT_EDITOR_MAX_HEIGHT = 300;

// How long a pause in typing counts as "the user has stopped for a moment". This is
// the LOCAL save, so it can be short: it costs one row and it is what actually
// protects the words.
const LOCAL_SAVE_DEBOUNCE_MS = 1000;

// How long the composer sits untouched before the draft is pushed up to the mail
// server. Deliberately long, and deliberately unlike the number above. The server
// copy exists so the user's OTHER mail clients can see the draft, and those do not
// need it within thirty seconds — while every push re-uploads the entire message
// (IMAP has no update), attachments included. Two minutes of genuine inactivity is
// the point where the user has plainly stopped writing.
const SERVER_PUSH_IDLE_MS = 2 * 60 * 1000;

export default {
  data() {
    return {
      newEmailDrawer: false,
      // The three recipient fields hold [{name, address, avatarUrl}] chips. The
      // send payload is still built from them as [{address}] on the way out, so
      // this phase changes the composer without touching the send API.
      to: [],
      cc: [],
      bcc: [],
      email: {
        mailHeaderId: null,
        to: [],
        cc: [],
        bcc: [],
        subject: '',
        content: {
          body: ''
        },
      },
      attachments: [],
      loading: false,
      title: '',
      editorMaxHeight: 0,
      // The draft this composer session is writing. draftLocalId is the server's
      // handle on it, minted on the first save and unchanged from then on — the IMAP
      // UID cannot play that role, since saving a draft means appending a new message
      // and deleting the old one, so the UID moves under us mid-sentence.
      draftLocalId: null,
      draftRevision: 0,
      draftState: null,
      // The composed text as it stood at the last save, so a save that would change
      // nothing is never made. Comparing what we have against what we sent is cheaper
      // and more honest than trying to track "dirty" through a dozen input events.
      savedSignature: null,
      localSaveTimer: null,
      serverPushTimer: null,
      draftSaving: false,
      discarding: false,
      // Said once per composer session, not once per save: an account with no Drafts
      // folder would otherwise repeat it on every pause in typing.
      localOnlyNotified: false,
    };
  },
  created() {
    this.$root.$on('open-new-email-drawer', (email, forward, replyAll, prefill) => {
      this.open(email, forward, replyAll, prefill);
    });
    this.$root.$on('send-email', (email) => {
      this.sendEmail(email);
    });
    this.$root.$on('resume-draft', (draft) => {
      this.resume(draft);
    });
  },
  watch: {
    // One watcher per field the draft is made of, all landing on the same handler.
    // A single deep watcher on `email` would not see the recipient chips, which live
    // outside it, and would fire on the fields that are not part of the draft.
    to() {
      this.onComposeChanged();
    },
    cc() {
      this.onComposeChanged();
    },
    bcc() {
      this.onComposeChanged();
    },
    'email.subject'() {
      this.onComposeChanged();
    },
    'email.content.body'() {
      this.onComposeChanged();
    },
  },
  computed: {
    /**
     * Whether Send is unavailable: no recipient, or an attachment still going up.
     *
     * @returns {boolean} true when sending must wait
     */
    disabled() {
      return !this.to.length || this.attachments.some(attachment => attachment.uploading);
    },
    /**
     * Whether the composer holds anything at all — which is also what makes a draft
     * worth saving and a Discard button worth offering.
     *
     * @returns {boolean} true when the composer holds anything
     */
    hasContent() {
      return !!(this.email.content.body || this.email.subject) || !!this.to.length || !!this.cc.length || !!this.bcc.length;
    },
    /**
     * The quiet line beside the Discard button that says where the draft stands.
     * Only ever says something once there is a draft to say it about.
     *
     * @returns {string} the label, or an empty string when there is nothing to say
     */
    draftStatusLabel() {
      if (this.draftSaving) {
        return this.$t('emailConnector.mailBox.newEmail.drawer.draft.saving');
      }
      if (!this.draftLocalId) {
        return '';
      }
      if (this.draftState === 'SYNCED') {
        return this.$t('emailConnector.mailBox.newEmail.drawer.draft.savedOnServer');
      }
      return this.$t('emailConnector.mailBox.newEmail.drawer.draft.savedLocally');
    }
  },
  methods: {
    /**
     * Opens the composer for a new mail, a reply, a reply-all or a forward.
     *
     * prefill ({to: [{name, address}]}) seeds a NEW email's recipients — the
     * Contacts drawer's "compose to" hand-off. It only applies when no email is
     * passed, so reply/forward prefills stay exactly what they were.
     *
     * @param {object} email - the message being replied to or forwarded
     * @param {boolean} forward - whether this is a forward
     * @param {boolean} replyAll - whether the reply addresses everyone
     * @param {object} prefill - recipients to seed a new mail with
     * @returns {void}
     */
    open(email, forward, replyAll, prefill) {
      this.attachments = [];
      this.resetDraftTracking();
      if (!email && prefill?.to?.length) {
        this.to = this.toRecipients(prefill.to);
      }
      this.title = forward ? this.$t('emailConnector.mailBox.forwardEmail.drawer.title') : email ? this.$t('emailConnector.mailBox.replyEmail.drawer.title') : this.$t('emailConnector.mailBox.newEmail.drawer.title');
      if (email) {
        if (!forward) {
          this.to = email.replyTo?.length > 0
            ? this.toRecipients(email.replyTo)
            : this.toRecipients([email.sender]);
          if (replyAll) {
            this.cc = this.toRecipients([...(email.to || []), ...(email.cc || [])]
              .filter(item => item.address !== email.userEmail));
          }
          this.email.mailHeaderId = email.mailHeaderId;
        }
        else {
          const bodyParts = [ 
            '<br><br>',
            this.$t('emailConnector.mailBox.forwardEmail.drawer.forwardedMessage'),
            '<br>',
            `${this.$t('emailConnector.mailBox.forwardEmail.drawer.from')} ${email.sender.name?.trim()} &lt;${email.sender.address?.trim()}&gt;`,
            '<br>',
            `${this.$t('emailConnector.mailBox.forwardEmail.drawer.date')} ${this.$emailConnectorMailBoxService.formatDateString(email.receivedDate, '', this.$t('emailConnector.mailBox.forwardEmail.drawer.date.at'), true)}`
          ];
          if (email.subject) {
            bodyParts.push('<br>');
            bodyParts.push(`${this.$t('emailConnector.mailBox.forwardEmail.drawer.subject')} ${email.subject}`);
          }
          if (email.to?.length) {
            bodyParts.push('<br>');
            bodyParts.push(`${this.$t('emailConnector.mailBox.newEmail.drawer.to.label')} ${email.to.map(item => `${item.name?.trim()} <span>&lt;<a href="mailto:${item.address?.trim()}">${item.address?.trim()}</a>&gt;</span>`).join(', ')}`);
          }
          if (email.cc?.length) {
            bodyParts.push('<br>');
            bodyParts.push(`${this.$t('emailConnector.mailBox.newEmail.drawer.cc.label')} ${email.cc.map(item => `${item.name?.trim()} <span>&lt;<a href="mailto:${item.address?.trim()}">${item.address?.trim()}</a>&gt;</span>`).join(', ')}`);
          }
          bodyParts.push('<br><br><br>');
          bodyParts.push(email.content.body || '');
          this.email.content.body = bodyParts.join('\n');
        }
        this.email.subject = `${forward ? this.$t('emailConnector.mailBox.forwardEmail.drawer.subject.prefix') : this.$t('emailConnector.mailBox.replyEmail.drawer.subject.prefix')} ${email.subject || ''}`;
      }
      this.newEmailDrawer = true;
      this.$nextTick(() => this.measureEditorMaxHeight());
      // Whatever the reply/forward prefill just put in the fields is the draft's
      // starting point, not an edit of it: record it as already saved so opening a
      // reply does not immediately write a draft nobody has typed a word into.
      this.$nextTick(() => {
        this.savedSignature = this.composeSignature();
      });
    },
    /**
     * Reopens a draft the user saved earlier — from the Drafts folder, or from the
     * conversation it sits at the bottom of.
     *
     * Everything about the draft's identity (its Message-ID, the parent it replies
     * to, the conversation it belongs to) stays on the server side and is left
     * strictly alone here. All the composer sends back is the local id and the text,
     * which is why a resumed reply keeps its place in the thread without this code
     * having to know anything about threading.
     *
     * @param {object} draft - the stored draft
     * @returns {void}
     */
    resume(draft) {
      this.attachments = [];
      this.resetDraftTracking();
      this.title = this.$t('emailConnector.mailBox.newEmail.drawer.draft.title');
      this.to = this.toRecipients(draft.to);
      this.cc = this.toRecipients(draft.cc);
      this.bcc = this.toRecipients(draft.bcc);
      this.email.subject = draft.subject || '';
      this.email.content.body = draft.content?.body || '';
      // The PARENT's id, not the draft's own. On a stored draft, mailHeaderId holds
      // the draft's own minted id and must never be sent back as a parent — that
      // would thread the draft against itself. What the composer needs here is what
      // the draft is a reply TO, so the send path keeps the conversation. The save
      // path ignores it anyway once the draft has an id (see saveDraft).
      this.email.mailHeaderId = draft.inReplyTo || null;
      this.draftLocalId = draft.draftLocalId;
      this.draftRevision = draft.draftRevision || 0;
      this.draftState = draft.draftState;
      this.newEmailDrawer = true;
      this.$nextTick(() => this.measureEditorMaxHeight());
      this.$nextTick(() => {
        this.savedSignature = this.composeSignature();
      });
    },
    measureEditorMaxHeight(previousHeight = null, attempt = 0) {
      // exo-drawer's opening transition isn't finished right after $nextTick, so we
      // re-sample every frame until the measurement settles. It has to be final before
      // <rich-editor> mounts (v-if): CKEditor's autogrow plugin captures auto-grow-max-height
      // once at creation and never re-reads it.
      const height = this.computeAvailableEditorHeight();
      if (height === previousHeight || attempt >= 30) {
        this.editorMaxHeight = height > 0 ? height : DEFAULT_EDITOR_MAX_HEIGHT;
        return;
      }
      requestAnimationFrame(() => this.measureEditorMaxHeight(height, attempt + 1));
    },
    computeAvailableEditorHeight() {
      const wrapperEl = this.$refs.editorWrapper;
      const drawerEl = this.$refs.newEmailDrawer?.$el;
      if (!wrapperEl || !drawerEl) {
        return 0;
      }
      const footerEl = drawerEl.querySelector('.drawerFooter');
      const footerHeight = footerEl ? footerEl.offsetHeight : 52;
      // toolbar renders below the editable area (toolbarPosition="bottom"), fixed 30px
      // height (platform-ui-skin CKEditor/Style.less .cke_bottom) — must be reserved too.
      const toolbarHeight = 30;
      const marginBottom = 16;
      const rawHeight = drawerEl.getBoundingClientRect().bottom - wrapperEl.getBoundingClientRect().top - toolbarHeight - footerHeight - marginBottom;
      // floor, not an exact-fit float: browser zoom rounds these rects to non-integer CSS
      // px, and a fractional overshoot here would trigger the drawer's own scrollbar too.
      return Math.floor(rawHeight);
    },
    /**
     * Turns whatever carries an address — a stored recipient, a sender, a
     * "compose to" hand-off — into the chip shape the recipient field speaks,
     * dropping the blanks and the duplicates a reply-all naturally produces.
     *
     * @param {Array} people - objects carrying an address, and maybe a name
     * @returns {Array} the recipient chips
     */
    toRecipients(people) {
      const seen = new Set();
      return (people || []).filter(person => person?.address?.trim())
        .map(person => ({
          name: person.name?.trim(),
          address: person.address.trim(),
          avatarUrl: person.avatarUrl,
        }))
        .filter(person => {
          const key = person.address.toLowerCase();
          if (seen.has(key)) {
            return false;
          }
          seen.add(key);
          return true;
        });
    },
    /**
     * Closes and empties the composer, saving the draft on the way out.
     *
     * Closing saves rather than asking "are you sure you want to lose this?" — a
     * question that no longer has an honest answer once the words are being kept. It
     * is also the moment the draft is pushed to the mail server, which is the point
     * at which the user has most plainly finished for now.
     *
     * The push is fired and not waited for: the drawer closes immediately, because
     * the words are already safe locally and making someone watch a spinner over an
     * IMAP round-trip they did not ask for would be the wrong trade. The local state
     * is cleared straight away too, which is why the id and revision are captured
     * into the call before the reset runs.
     *
     * @returns {void}
     */
    close() {
      this.cancelDraftTimers();
      if (this.hasContent && this.hasUnsavedChanges()) {
        this.saveDraft(true);
      } else if (this.draftLocalId && this.draftState !== 'SYNCED') {
        // Nothing new typed, but the copy up there is not the copy down here.
        this.saveDraft(true);
      }
      this.to = [];
      this.cc = [];
      this.bcc = [];
      this.email.subject = '';
      this.email.content.body = '';
      this.email.mailHeaderId = null;
      this.email.attachments = [];
      this.attachments = [];
      this.editorMaxHeight = 0;
      this.resetDraftTracking();
      this.newEmailDrawer = false;
    },
    /**
     * Throws the draft away, on the user's explicit say-so, and closes.
     *
     * The composer is emptied BEFORE the drawer closes, so the close handler finds
     * nothing to save and cannot resurrect what was just discarded.
     *
     * @returns {void}
     */
    discardDraft() {
      const draftLocalId = this.draftLocalId;
      this.emptyComposer();
      if (!draftLocalId) {
        this.close();
        return;
      }
      this.discarding = true;
      this.$emailConnectorMailBoxService.deleteDraft(draftLocalId).then(() => {
        this.$root.$emit('refresh-email-box');
      }).catch(() => {
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: 'error',
          alertMessage: this.$t('emailConnector.mailBox.newEmail.drawer.draft.discard.error'),
        }}));
      }).finally(() => {
        this.discarding = false;
        this.close();
      });
    },
    /**
     * Empties everything the composer holds and forgets the draft it was writing —
     * so that the close which follows finds nothing worth saving.
     *
     * @returns {void}
     */
    emptyComposer() {
      this.to = [];
      this.cc = [];
      this.bcc = [];
      this.email.subject = '';
      this.email.content.body = '';
      this.email.mailHeaderId = null;
      this.attachments = [];
      this.resetDraftTracking();
    },
    /**
     * Removes the local draft of a message that has just been sent, and empties the
     * composer's draft tracking so the close that follows cannot re-save it.
     *
     * The order is deliberately the reverse of the compensating re-insert that
     * archive and delete use: the send has already happened and cannot be taken
     * back, so if this cleanup fails there is nothing to restore and nothing worth
     * restoring. The failure is swallowed rather than reported, because there is no
     * action the user could take on it.
     *
     * Only the LOCAL row goes. A copy that was pushed to the mail server's Drafts
     * folder stays there until the slice that owns sending a draft removes it.
     *
     * @returns {void}
     */
    dropDraftAfterSend() {
      const draftLocalId = this.draftLocalId;
      this.emptyComposer();
      if (!draftLocalId) {
        return;
      }
      this.$emailConnectorMailBoxService.deleteDraft(draftLocalId).catch(() => {
        // Nothing to say and nothing to do: the mail is gone, the draft row is stale
        // rather than wrong, and the next save path will not resurrect it.
      });
    },
    /**
     * Reacts to the user typing: schedules the local save that protects the words,
     * and restarts the much longer idle countdown that eventually pushes the draft
     * to the mail server.
     *
     * Restarting the push countdown on every change is what makes it mean "genuine
     * inactivity" rather than "every two minutes regardless".
     *
     * @returns {void}
     */
    onComposeChanged() {
      if (!this.newEmailDrawer || this.savedSignature === null) {
        return;
      }
      clearTimeout(this.localSaveTimer);
      this.localSaveTimer = setTimeout(() => this.saveDraft(false), LOCAL_SAVE_DEBOUNCE_MS);
      clearTimeout(this.serverPushTimer);
      this.serverPushTimer = setTimeout(() => this.saveDraft(true), SERVER_PUSH_IDLE_MS);
    },
    /**
     * Saves the draft, optionally pushing it to the mail server.
     *
     * Does nothing at all when nothing has changed since the last save — which is
     * what makes the close-and-push path free for a draft the user only looked at.
     * Never surfaced as an error to the user either: a failed autosave is not
     * something they asked for and cannot act on, and the text is still in front of
     * them.
     *
     * @param {boolean} push - whether to also upload the draft to the mail server
     * @returns {void}
     */
    saveDraft(push) {
      const signature = this.composeSignature();
      if (!this.hasContent || (signature === this.savedSignature && !push)) {
        return;
      }
      this.draftRevision++;
      const payload = {
        draftLocalId: this.draftLocalId,
        draftRevision: this.draftRevision,
        // On a FIRST save this carries the PARENT's Message-ID, which is what makes a
        // draft reply join its conversation while it is still being written. On every
        // later save the server ignores it.
        mailHeaderId: this.draftLocalId ? null : this.email.mailHeaderId,
        subject: this.email.subject,
        content: {body: this.email.content.body},
        to: this.toAddresses(this.to),
        cc: this.toAddresses(this.cc),
        bcc: this.toAddresses(this.bcc),
      };
      this.savedSignature = signature;
      this.draftSaving = true;
      this.$emailConnectorMailBoxService.saveDraft(payload, push).then((saved) => {
        this.draftLocalId = saved.draftLocalId;
        this.draftState = saved.draftState;
        if (saved.draftRevision) {
          this.draftRevision = Math.max(this.draftRevision, saved.draftRevision);
        }
        if (push) {
          this.$root.$emit('refresh-email-box');
          this.notifyIfLocalOnly(saved);
        }
      }).catch(() => {
        // Let the next change try again rather than pretending this one landed.
        this.savedSignature = null;
      }).finally(() => {
        this.draftSaving = false;
      });
    },
    /**
     * Tells the user, once per composer session, that their draft could not be put
     * on the mail server and lives only here — the honest answer for an account
     * whose mailbox has no Drafts folder, since we deliberately never create one.
     *
     * @param {object} saved - the draft as the server stored it
     * @returns {void}
     */
    notifyIfLocalOnly(saved) {
      if (saved.draftState === 'SYNCED' || this.localOnlyNotified) {
        return;
      }
      this.localOnlyNotified = true;
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {
        alertType: 'info',
        alertMessage: this.$t('emailConnector.mailBox.newEmail.drawer.draft.localOnly'),
      }}));
    },
    /**
     * Whether anything has been typed since the last save.
     *
     * @returns {boolean} true when there is something new to store
     */
    hasUnsavedChanges() {
      return this.composeSignature() !== this.savedSignature;
    },
    /**
     * A single string standing for everything a draft is made of, so "has this
     * changed" is one comparison rather than a scattering of dirty flags.
     *
     * @returns {string} the composed state's signature
     */
    composeSignature() {
      return JSON.stringify([
        this.email.subject || '',
        this.email.content.body || '',
        this.toAddresses(this.to),
        this.toAddresses(this.cc),
        this.toAddresses(this.bcc),
      ]);
    },
    /**
     * Forgets the draft this composer session was writing. Called when the composer
     * opens on something else and when it closes.
     *
     * @returns {void}
     */
    resetDraftTracking() {
      this.cancelDraftTimers();
      this.draftLocalId = null;
      this.draftRevision = 0;
      this.draftState = null;
      this.savedSignature = null;
      this.localOnlyNotified = false;
    },
    /**
     * Stops the pending local save and the pending server push.
     *
     * @returns {void}
     */
    cancelDraftTimers() {
      clearTimeout(this.localSaveTimer);
      clearTimeout(this.serverPushTimer);
      this.localSaveTimer = null;
      this.serverPushTimer = null;
    },
    /**
     * Sends what the composer holds, or re-sends the payload the no-subject
     * confirmation handed back.
     *
     * @param {object} email - a ready payload, or nothing to build one
     * @returns {void}
     */
    sendEmail(email) {
      if (email) {
        this.email = email;
      }
      else {
        // The send API takes plain addresses; the chips' names and avatars are
        // the field's business and stop here.
        this.email.to = this.toAddresses(this.to);
        this.email.cc = this.toAddresses(this.cc);
        this.email.bcc = [
          ...(this.email.bcc || []),
          ...this.toAddresses(this.bcc)
        ];
        if (!this.email.subject) {
          this.$root.$emit('open-no-subject-email-confirm-popup', this.email);
          return;
        }
      }
      this.email.attachments = this.attachments
        .filter(attachment => attachment.uploadId)
        .map(attachment => ({
          uploadId: attachment.uploadId,
          name: attachment.name,
          mimeType: attachment.mimeType,
          size: attachment.size,
        }));
      this.email.content.body = this.formatEmailBody(this.email.content.body);
      this.loading = true;
      // Nothing may push a draft of a message that is about to be sent: the close
      // handler below would otherwise upload one, and showing someone a draft of a
      // mail they have already sent — and inviting them to send it twice — is worse
      // than any of the alternatives.
      this.cancelDraftTimers();
      this.$emailConnectorMailBoxService.sendEmail(this.email).then(() => {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.send.success'), 'success');
        this.dropDraftAfterSend();
        this.close();
      }).catch(() => {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.send.error'), 'error');
      }).finally(() => this.loading = false);
    },
    /**
     * Narrows recipient chips to what the send API consumes.
     *
     * @param {Array} recipients - the field's chips
     * @returns {Array} [{address}] entries
     */
    toAddresses(recipients) {
      return (recipients || []).map(recipient => ({ address: recipient.address?.trim() }))
        .filter(recipient => recipient.address);
    },
    /**
     * Widens the quoted blocks the editor produced into something a mail client
     * renders as a quote.
     *
     * @param {string} html - the composed body
     * @returns {string} the body to send
     */
    formatEmailBody(html) {
      if (!html) {
        return html;
      }
      return html.replace(/<blockquote>/g, `
      <blockquote style="
        margin: 0 0 0 6px;
        padding-left: 8px;
        border-left: 1px solid #ccc;
      ">
     `);
    }
  }
};
</script>