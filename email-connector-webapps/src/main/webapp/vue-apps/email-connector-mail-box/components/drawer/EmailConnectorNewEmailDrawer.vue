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
      <div ref="editorWrapper" class="mx-4 mt-3 position-relative">
        <div
          v-if="draggingImage"
          class="d-flex flex-column align-center justify-center position-absolute z-index-two rounded"
          style="inset: 0; pointer-events: none; border: 3px dashed var(--allPagesPrimaryColor, #578dc9); background-color: var(--allPagesBaseBackground, #fff); opacity: 0.92;">
          <v-icon size="32" color="primary">fas fa-image</v-icon>
          <span class="text-subtitle-2 primary--text mt-2">{{ $t('emailConnector.mailBox.newEmail.drawer.content.dropImage') }}</span>
        </div>
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
          hide-chars-count
          @ready="onEditorReady" />
      </div>
      <email-connector-new-email-drawer-attachments
        v-model="attachments"
        :active="newEmailDrawer"
        :persist="persistAttachment"
        :unpersist="unpersistAttachment" />
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
import { personName } from '../../js/EmailRecipientDisplay.js';
import { escapeHtml, replyQuoteBody } from '../../js/EmailReplyQuote.js';

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
//
// It does not run at all once the draft carries a file: see onComposeChanged. The
// arithmetic that makes two minutes reasonable for a page of text makes it
// unreasonable for a page of text plus 20 MB.
const SERVER_PUSH_IDLE_MS = 2 * 60 * 1000;

/**
 * A fresh record for the draft one composer session is writing: nothing saved yet,
 * no save in flight, and an empty queue for the saves to come.
 *
 * @returns {object} the session record
 */
function newDraftSession() {
  return {
    // The server's handle on the draft, minted on the first save and unchanged from
    // then on — the IMAP UID cannot play that role, since saving a draft means
    // appending a new message and deleting the old one, so the UID moves under us
    // mid-sentence.
    localId: null,
    revision: 0,
    state: null,
    // How many saves of this session are still in flight, so the footer says "Saving…"
    // for as long as any of them is, and so a close can tell that a draft is on its
    // way into existence even though its id is not back yet.
    pending: 0,
    // The saves of this session, run one after another. Two saves of one draft must
    // never be in flight together: the second would carry no local id while the first
    // is still minting one, and the server would answer it with a SECOND draft of the
    // same reply.
    queue: Promise.resolve(),
    // Set once the drawer has closed on this session, so the closing save is fired
    // exactly once however many times the drawer reports itself closed.
    closed: false,
  };
}

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
      draggingImage: false,
      loading: false,
      title: '',
      editorMaxHeight: 0,
      // The draft this composer session is writing, as ONE record rather than a
      // scattering of fields. Every save reads the draft's identity from here and
      // writes back to this same object, and opening the composer on something else
      // puts a new record in its place — so a save still in flight when the drawer
      // closes goes on talking about the draft it was given, and can neither be handed
      // the next draft's text nor hand its own id to the next composer session.
      draftSession: newDraftSession(),
      // The composed text as it stood at the last save, so a save that would change
      // nothing is never made. Comparing what we have against what we sent is cheaper
      // and more honest than trying to track "dirty" through a dozen input events.
      savedSignature: null,
      localSaveTimer: null,
      serverPushTimer: null,
      discarding: false,
      // Said once per composer session, not once per save: an account with no Drafts
      // folder would otherwise repeat it on every pause in typing.
      localOnlyNotified: false,
      // Same discipline for the "your other mail client removed the copy of this
      // draft" notice: it is true until the next push puts a new copy up, so an
      // unthrottled version would repeat on every autosave in between.
      serverCopyGoneNotified: false,
      // True for the instant in which the editor hands the prefill back (see
      // onEditorReady), so that echo is not mistaken for the user typing.
      editorEchoing: false,
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
    // Attaching a file is not a change to save — the server stored it and stepped the
    // revision itself. What it IS, is a reason to take down an idle push that was
    // already counting: from the moment a file is on the draft, every push re-uploads
    // it, and the countdown was armed when there was nothing to re-upload. Without
    // this, the file attached at second 119 of a two-minute idle goes up one second
    // later, and again on the next pause.
    attachments() {
      if (this.attachments.length) {
        clearTimeout(this.serverPushTimer);
        this.serverPushTimer = null;
      }
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
      // A file counts. Attaching one to an empty composer is an ordinary way to start
      // a mail, and it is already a draft the moment it is stored — so the Discard
      // button has to be there to throw it away, and the closing save has to have
      // something to announce.
      return !!(this.email.content.body || this.email.subject) || !!this.to.length || !!this.cc.length || !!this.bcc.length
        || !!this.attachments.length;
    },
    /**
     * The quiet line beside the Discard button that says where the draft stands.
     * Only ever says something once there is a draft to say it about.
     *
     * @returns {string} the label, or an empty string when there is nothing to say
     */
    draftStatusLabel() {
      if (this.draftSession.pending > 0) {
        return this.$t('emailConnector.mailBox.newEmail.drawer.draft.saving');
      }
      if (!this.draftSession.localId) {
        return '';
      }
      if (this.draftSession.state === 'SYNCED') {
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
          // The message being answered, quoted under the cursor. Built the same way
          // whether this is a reply or a reply-all: those two differ in who receives
          // the answer, never in what is being answered.
          this.email.content.body = replyQuoteBody(this.replyAttribution(email), email.content?.body);
        }
        else {
          const bodyParts = [ 
            '<br><br>',
            this.$t('emailConnector.mailBox.forwardEmail.drawer.forwardedMessage'),
            '<br>',
            `${this.$t('emailConnector.mailBox.forwardEmail.drawer.from')} ${personName(email.sender)} &lt;${email.sender.address?.trim()}&gt;`,
            '<br>',
            `${this.$t('emailConnector.mailBox.forwardEmail.drawer.date')} ${this.$emailConnectorMailBoxService.formatDateString(email.receivedDate, '', this.$t('emailConnector.mailBox.forwardEmail.drawer.date.at'), true)}`
          ];
          if (email.subject) {
            bodyParts.push('<br>');
            bodyParts.push(`${this.$t('emailConnector.mailBox.forwardEmail.drawer.subject')} ${email.subject}`);
          }
          if (email.to?.length) {
            bodyParts.push('<br>');
            bodyParts.push(`${this.$t('emailConnector.mailBox.newEmail.drawer.to.label')} ${email.to.map(item => this.quotedRecipient(item)).join(', ')}`);
          }
          if (email.cc?.length) {
            bodyParts.push('<br>');
            bodyParts.push(`${this.$t('emailConnector.mailBox.newEmail.drawer.cc.label')} ${email.cc.map(item => this.quotedRecipient(item)).join(', ')}`);
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
        if (forward) {
          this.carryForwardedFiles(email);
        }
      });
    },
    /**
     * Brings the files of the message being forwarded onto this forward, as the same
     * chips the user's own attached files produce — removable, counted against the
     * same size cap, and carried by the draft if the composer is closed and reopened.
     *
     * Forwarding used to be a prefill and nothing else: a quoted header, the original's
     * body, and not a glance at its files. The forward went out with none of them and
     * nothing said so.
     *
     * Three things happen here, in this order and for a reason each:
     *
     * - A placeholder chip per file appears IMMEDIATELY, in the "uploading" state. It
     *   is not decoration: it is what the Send button reads to stay disabled. Without
     *   it a fast user could address the forward and send it in the second the copy
     *   takes, and the mail would go out without the files — the very defect, moved
     *   into a smaller window.
     *
     * - A draft is forced into existence, through the same queued save the paperclip
     *   uses, because a stored file has nowhere to live but a draft. This is what makes
     *   an opened forward a draft in the Drafts folder even if the user closes it
     *   without typing — deliberate, and the same thing Gmail does: the files really
     *   were copied, and a draft they can see and discard is the honest record of that.
     *
     * - The chips are rebuilt from the draft the server answers with, so what is on
     *   screen is what the draft actually carries — including a file the user attached
     *   by hand while the copy was in flight, which the answer already knows about.
     *   Anything still going up on this side is kept, since the draft does not know
     *   about it yet.
     *
     * Closing the drawer while the copy is in flight is safe rather than lucky. The
     * closing save and the copy both take the draft's lock server-side, so they cannot
     * interleave; if the save wins the race it puts a copy in the Drafts folder without
     * the forwarded files, and the copy that follows marks the row edited again, so the
     * next push replaces it. What is sent is built from the row, never from that copy.
     *
     * @param {object} email - the message being forwarded
     * @returns {void}
     */
    carryForwardedFiles(email) {
      const files = email?.content?.attachments || [];
      if (!files.length) {
        return;
      }
      const session = this.draftSession;
      this.attachments = files.map((file, index) => ({
        key: `forwarded-${index}`,
        id: null,
        name: file.name,
        title: file.name,
        mimeType: file.mimeType,
        mimetype: file.mimeType,
        size: file.size || 0,
        uploadId: null,
        // Both true until the server answers: "uploading" is what disables Send, and
        // "forwarded" is how the rebuild below tells these placeholders apart from a
        // file the user is really uploading at the same moment.
        uploading: true,
        forwarded: true,
      }));
      this.forceDraft(session)
        .then(() => {
          if (!session.localId || session !== this.draftSession) {
            // No draft could be created, or the composer has moved on to another
            // message while this was starting. Either way these files have nothing to
            // belong to.
            throw new Error('No draft to forward onto');
          }
          return this.$emailConnectorMailBoxService.addForwardedAttachments(session.localId, email.mailRemoteId, email.folder);
        })
        .then(forwarded => {
          if (session !== this.draftSession) {
            return;
          }
          this.applyDraftRevision(session, forwarded?.draft);
          // Only onto a composer that is still on screen. A drawer closed while the copy
          // was in flight has already emptied its chips, and writing them back would put
          // the closed forward's files in front of whatever is opened next.
          if (this.newEmailDrawer) {
            this.adoptStoredAttachments(forwarded?.draft);
          }
          // Said whether or not the drawer is still open: the draft was saved, and which
          // files it does NOT carry is worth knowing either way.
          this.notifyFilesLeftBehind(forwarded?.notAttached);
        })
        .catch(() => {
          if (session !== this.draftSession) {
            return;
          }
          // The placeholders stood for files that are not on the draft. They go, so the
          // composer shows only what the forward will really carry, and the user is
          // told rather than left to notice.
          if (this.newEmailDrawer) {
            this.attachments = this.attachments.filter(attachment => !attachment.forwarded);
          }
          this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.forward.attach.error'), 'error');
        });
    },
    /**
     * Puts the files a draft carries on screen as chips, keeping anything of this
     * session's that is still on its way up.
     *
     * The draft is the authority on what is stored — it knows about a file attached
     * from another tab, and about one attached here while a forward's copy was in
     * flight — but it cannot know about a chip whose upload has not landed yet, and
     * dropping that one would hide a file that is about to be on the draft.
     *
     * @param {object} draft - the draft as the server answered with it
     * @returns {void}
     */
    adoptStoredAttachments(draft) {
      const stillGoingUp = this.attachments.filter(attachment => attachment.uploading && !attachment.forwarded);
      this.attachments = [...this.storedAttachmentChips(draft), ...stillGoingUp];
    },
    /**
     * Lets a picture be dropped or pasted straight into the message.
     * <p>
     * Bound on CKEditor's own paste and drop events rather than on the DOM: the
     * editable lives in an iframe, so a listener on this component's element never
     * sees either, and CKEditor hands over the files already extracted from the
     * clipboard — which is the same path a screenshot takes, since a screenshot
     * arrives as a pasted file rather than as a drag.
     * @returns {void}
     */
    bindInlineImageDrops() {
      const editor = this.$refs.emailContent && this.$refs.emailContent.editor;
      if (!editor || editor.emailInlineImagesBound) {
        return;
      }
      // The editor outlives a single open() of this drawer, so binding on every
      // ready would stack a second handler and upload each picture twice.
      editor.emailInlineImagesBound = true;
      editor.on('paste', this.onEditorFilesDropped);
      editor.on('drop', this.onEditorFilesDropped);
      // Also on the editor's own document: while a file is dragged over the editable
      // the pointer is inside the iframe, and the drawer around it is told nothing.
      const editorDocument = editor.document && editor.document.$;
      if (editorDocument) {
        editorDocument.addEventListener('dragover', this.onDragOverEditor);
        editorDocument.addEventListener('drop', this.onDragLeftEditor);
      }
      const wrapper = this.$refs.editorWrapper;
      if (wrapper) {
        wrapper.addEventListener('dragover', this.onDragOverEditor);
        wrapper.addEventListener('drop', this.onDragLeftEditor);
      }
    },
    /**
     * Shows the drop target while a file is being dragged over the editor.
     * <p>
     * Driven off {@code dragover}, which repeats while the pointer moves, rather than
     * off the enter/leave pair: those fire on every element the pointer crosses, so
     * counting them across an iframe boundary flickers. Instead the marker is put up
     * on each {@code dragover} and taken down shortly after they stop arriving, which
     * is steady wherever the pointer goes.
     * <p>
     * Only for a drag that carries files — dragging a word of text around the message
     * is not an invitation to drop a picture.
     *
     * @param {object} event - the browser drag event
     * @returns {void}
     */
    onDragOverEditor(event) {
      const types = event?.dataTransfer?.types;
      if (!types || !Array.prototype.includes.call(types, 'Files')) {
        return;
      }
      // Without this the browser opens the dropped file in a new tab instead.
      event.preventDefault();
      this.draggingImage = true;
      clearTimeout(this.dragOverTimer);
      this.dragOverTimer = setTimeout(() => this.draggingImage = false, 150);
    },
    /**
     * Takes the drop target down at once, rather than waiting for the timer, so it
     * does not linger over the picture that has just landed.
     *
     * @returns {void}
     */
    onDragLeftEditor() {
      clearTimeout(this.dragOverTimer);
      this.draggingImage = false;
    },
    /**
     * Takes the images out of a paste or a drop and leaves everything else alone.
     * <p>
     * Cancelled only when there is an image to take, so pasting text, a link or a
     * file that is not a picture behaves exactly as it did. Cancelled when there is,
     * because CKEditor would otherwise insert the picture as a data URI — which looks
     * right in this editor and is stripped by Gmail and Outlook on the way out.
     *
     * @param {object} event - CKEditor's paste or drop event
     * @returns {void}
     */
    onEditorFilesDropped(event) {
      const transfer = event?.data?.dataTransfer;
      const count = transfer && transfer.getFilesCount ? transfer.getFilesCount() : 0;
      const images = [];
      for (let index = 0; index < count; index++) {
        const file = transfer.getFile(index);
        if (file && (file.type || '').startsWith('image/')) {
          images.push(file);
        }
      }
      if (!images.length) {
        return;
      }
      event.cancel();
      images.forEach(image => this.insertInlineImage(image));
    },
    /**
     * Puts one picture in the message: visible at once, then quietly re-pointed at
     * the copy the draft keeps.
     * <p>
     * Shown first as the bytes the browser already has, so the picture appears in the
     * sentence the moment it is dropped rather than after a round trip. It is then
     * stored on the draft exactly as an attachment is — the same upload, the same
     * persist, so it survives closing and reopening for the reasons EXO-89338 covered
     * — and the address in the text is swapped for the draft's own. That address is
     * what the send path looks for to decide the picture belongs in the body.
     * <p>
     * If storing fails the placeholder is removed rather than left behind: a picture
     * whose bytes only this browser has would reach the recipient as a broken frame.
     *
     * @param {File} file - the dropped or pasted image
     * @returns {void}
     */
    async insertInlineImage(file) {
      const session = this.draftSession;
      const editor = this.$refs.emailContent && this.$refs.emailContent.editor;
      if (!editor) {
        return;
      }
      const token = `pending-${Date.now()}-${Math.round(Math.random() * 100000)}`;
      const preview = await this.readAsDataUrl(file).catch(() => null);
      if (!preview || session !== this.draftSession) {
        return;
      }
      editor.insertHtml(`<img src="${preview}" data-email-inline="${token}" style="max-width: 100%;">`);
      try {
        const uploadId = this.$uploadService.generateRandomId();
        const resolvedId = await this.$uploadService.upload(file, uploadId);
        const stored = await this.persistAttachment({
          uploadId: resolvedId,
          name: file.name || 'image',
          mimeType: file.type,
          size: file.size,
        });
        if (!stored || !stored.id || session !== this.draftSession) {
          throw new Error('the draft moved on while the picture was going up');
        }
        this.pointInlineImageAt(token, this.$emailConnectorMailBoxService.getDraftAttachmentUrl(session.localId, stored.id));
      } catch (error) {
        this.pointInlineImageAt(token, null);
      }
    },
    /**
     * Re-points a placeholder at its stored address, or removes it when there is none.
     *
     * @param {string} token - the placeholder's marker
     * @param {string} source - the address to point at, or null to remove it
     * @returns {void}
     */
    pointInlineImageAt(token, source) {
      const editor = this.$refs.emailContent && this.$refs.emailContent.editor;
      const document = editor && editor.document && editor.document.$;
      const image = document && document.querySelector(`img[data-email-inline="${token}"]`);
      if (!image) {
        return;
      }
      if (source) {
        image.setAttribute('src', source);
        image.removeAttribute('data-email-inline');
      } else {
        image.remove();
      }
      editor.fire('change');
    },
    /**
     * The dropped file as a data URL, for the moment before it has been stored.
     *
     * @param {File} file - the image to read
     * @returns {Promise} resolves with the data URL
     */
    readAsDataUrl(file) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = reject;
        reader.readAsDataURL(file);
      });
    },
    /**
     * The ids of the stored files a body points at, which is what makes a picture
     * inline rather than an attachment. Read from the text because the text is where
     * the answer lives: delete the picture from the message and it stops being inline,
     * with nothing to keep in step.
     *
     * @param {string} body - the message body
     * @returns {Set} the stored attachment ids the body references
     */
    inlinedAttachmentIds(body) {
      const ids = new Set();
      if (!body) {
        return ids;
      }
      const pattern = /\/email-box\/drafts\/[^/"']+\/attachments\/(\d+)/g;
      let match = pattern.exec(body);
      while (match) {
        ids.add(Number(match[1]));
        match = pattern.exec(body);
      }
      return ids;
    },
    /**
     * The files a draft carries, as the chips the composer shows.
     *
     * One mapping for the two ways a draft's files reach the screen — resuming a draft
     * and answering a forward — because they are the same chips: they carry an id and
     * no upload id, which is what tells every path apart. They are already on the server
     * side of the draft, so they are downloaded from it, removed through it, and left
     * out of the send payload, since the send path reads them off the row itself.
     *
     * @param {object} draft - the draft as stored
     * @returns {Array} the chips
     */
    storedAttachmentChips(draft) {
      // A picture dropped into the text is a stored file like any other, so it arrives
      // here with the rest. Left in, the recipient's screenshot would be offered twice:
      // once where the sentence needs it and once as a paperclip. The body is the only
      // record of which is which -- deliberately, see the send path.
      const inlined = this.inlinedAttachmentIds(draft?.content?.body);
      const files = (draft?.content?.attachments || []).filter(attachment => !inlined.has(attachment?.id));
      return files.map((attachment, index) => ({
        key: `stored-${attachment.id || index}`,
        id: attachment.id,
        name: attachment.name,
        title: attachment.name,
        mimeType: attachment.mimeType,
        mimetype: attachment.mimeType,
        size: attachment.size || 0,
        uploadId: null,
        uploading: false,
        stored: true,
      }));
    },
    /**
     * Names the files of the forwarded message that are NOT on the forward.
     *
     * Said out loud, and naming them, because the alternative is the defect this whole
     * change is about: a sender who believes the files went with their forward. Which
     * ones matters — "two files were left behind" does not tell them whether the one
     * that mattered is among them.
     *
     * @param {Array} names - the names of the files that were not attached
     * @returns {void}
     */
    notifyFilesLeftBehind(names) {
      if (!names?.length) {
        return;
      }
      this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.forward.attach.leftBehind', {
        0: names.join(', '),
      }), 'warning');
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
      // The files the draft was stored with, as chips the user can see and remove.
      this.attachments = this.storedAttachmentChips(draft);
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
      this.draftSession.localId = draft.draftLocalId;
      this.draftSession.revision = draft.draftRevision || 0;
      this.draftSession.state = draft.draftState;
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
          // personName rather than the raw name, because this is the way IN: a reply
          // seeds its chips from the message it answers and a resumed draft from its
          // own stored row, so anything wrong with a name here is typed back into the
          // draft on the next autosave. A name that is the word null is dropped at the
          // door instead of being carried around and rendered.
          name: personName(person),
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
     * The drawer closes IMMEDIATELY, and the save and the conversation's reload happen
     * behind it: the words are already safe locally, and making someone watch an IMAP
     * round-trip they did not ask for would be the wrong trade. What the save must not
     * do is read the composer afterwards, since the lines below empty it — so it is
     * handed a snapshot taken first, and the draft's identity lives on the session
     * record rather than on fields this method clears.
     *
     * The session is deliberately NOT rotated here. Rotating it is how the composer
     * forgets a draft, and it belongs to opening the composer on something else (see
     * resetDraftTracking, called by open, resume and emptyComposer) — doing it here as
     * well would cut the closing save off from the very record it is about to write.
     *
     * @returns {void}
     */
    close() {
      this.cancelDraftTimers();
      this.flushDraft();
      this.to = [];
      this.cc = [];
      this.bcc = [];
      this.email.subject = '';
      this.email.content.body = '';
      this.email.mailHeaderId = null;
      this.email.attachments = [];
      this.attachments = [];
      this.editorMaxHeight = 0;
      this.newEmailDrawer = false;
    },
    /**
     * The save a close fires: it stores what was on screen at the moment the drawer
     * closed, and then tells the conversation, whatever came of it.
     *
     * Three cases, and the middle one is the defect this exists for:
     *
     * - Something typed since the last save is stored, from the snapshot, so what is
     *   written is what the user was looking at rather than the emptied fields the
     *   close leaves behind a line later.
     *
     * - Nothing typed since the last save, but there IS a draft — already saved, or
     *   still being saved. That second half is the bug: a local autosave fires a
     *   second after the last keystroke and takes a moment to answer, and a close
     *   landing in that moment used to find no local id and no unsaved change and save
     *   NOTHING. The draft existed, nobody said so, and the conversation behind the
     *   composer went on showing no reply in progress until the user reopened it. A
     *   save in flight now counts as a draft, and the queue means this save runs after
     *   it and under the id it minted rather than creating a second draft of the same
     *   reply.
     *
     * - A composer holding only what a reply prefilled has nothing to save, and must
     *   not leave an empty draft behind.
     *
     * The conversation is told in every case where a draft exists — including when the
     * save failed, because the row may well have been written before whatever failed
     * did (production has produced exactly that: a stored draft whose response then
     * errored), and re-reading is the only way to find out. That announcement is what
     * makes the draft appear under the message it answers with nothing reopened by
     * hand.
     *
     * @returns {void}
     */
    flushDraft() {
      const session = this.draftSession;
      if (session.closed) {
        // Closing clears the flag the drawer is bound to, so the drawer can report
        // itself closed a second time; the draft is saved on the first of them.
        return;
      }
      session.closed = true;
      const snapshot = this.snapshotDraft();
      if (snapshot.hasContent && (snapshot.signature !== this.savedSignature || session.localId || session.pending)) {
        this.storeDraft(session, snapshot, true, true);
      } else if (session.localId) {
        this.$root.$emit('refresh-email-box');
      }
    },
    /**
     * Everything a save needs, read off the screen in one go: the text as it stands,
     * the recipients as the chips they are, and the answers to "is there anything
     * here" and "has it changed since the last save".
     *
     * Taken as a snapshot because the save that matters most is fired by the close,
     * which empties these fields immediately afterwards and may open on another draft
     * before the request lands.
     *
     * @returns {object} the composed state, as a save consumes it
     */
    snapshotDraft() {
      return {
        subject: this.email.subject,
        body: this.email.content.body,
        // Names as well as addresses, unlike the send payload. A draft is read back
        // into these very fields when it is resumed, so what is not stored is what the
        // user sees disappear from a chip they typed. The send API has no use for them
        // — the mail server resolves nothing from a display name — but the draft row
        // is the composer's own memory of the state it was in.
        to: this.toDraftRecipients(this.to),
        cc: this.toDraftRecipients(this.cc),
        bcc: this.toDraftRecipients(this.bcc),
        // On a FIRST save this carries the PARENT's Message-ID, which is what makes a
        // draft reply join its conversation while it is still being written. On every
        // later save the server ignores it.
        parentMessageId: this.email.mailHeaderId,
        hasContent: this.hasContent,
        signature: this.composeSignature(),
      };
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
      const draftLocalId = this.draftSession.localId;
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
     * Reacts to the user typing: schedules the local save that protects the words,
     * and restarts the much longer idle countdown that eventually pushes the draft
     * to the mail server.
     *
     * Restarting the push countdown on every change is what makes it mean "genuine
     * inactivity" rather than "every two minutes regardless".
     *
     * The push countdown is not armed at all while the draft carries a file. IMAP has
     * no update, so every push re-uploads the WHOLE message: on a draft with a 20 MB
     * attachment the idle push would send those 20 MB up again every couple of minutes
     * for as long as the composer stays open, for a copy nobody but the author is
     * reading. A draft with files is pushed when the drawer closes and when it is sent
     * — the two moments the user has plainly finished — and never in between.
     *
     * @returns {void}
     */
    onComposeChanged() {
      // editorEchoing: the editor handing the prefill back is not somebody typing,
      // and must not start the clock on a save (see onEditorReady).
      if (!this.newEmailDrawer || this.savedSignature === null || this.editorEchoing) {
        return;
      }
      clearTimeout(this.localSaveTimer);
      this.localSaveTimer = setTimeout(() => this.saveDraft(false), LOCAL_SAVE_DEBOUNCE_MS);
      clearTimeout(this.serverPushTimer);
      this.serverPushTimer = this.attachments.length ? null
        : setTimeout(() => this.saveDraft(true), SERVER_PUSH_IDLE_MS);
    },
    /**
     * The editor has taken the prefill and gone live.
     *
     * CKEditor does not hand back what it was given. The value is run through
     * DOMPurify and the editor's own normalisation on the way in, and echoed
     * straight back out through v-model the moment the instance is ready — so the
     * body can come back subtly different from the string open() wrote. It hardly
     * showed while a reply opened empty, because an empty body survives any
     * normalisation unchanged. A reply that opens with a quoted block in it is a
     * different matter: one rewritten attribute is a change of the body, and the
     * close handler would read that change as something the user had written and
     * leave behind a draft of a reply they only glanced at.
     *
     * Whatever the editor made of the prefill is still the draft's starting point,
     * not an edit of it. The flag keeps the echo from starting a save, and the
     * re-stamp records what is now really on screen as the thing to compare against.
     *
     * A macrotask rather than $nextTick, and this is the whole reason it is one: the
     * echo arrives through v-model and wakes the field watchers, and Vue runs those
     * on its own nextTick queue. A $nextTick registered here would be queued ahead
     * of them and would stamp the signature before the echo had been applied —
     * which is precisely the bug it exists to prevent.
     *
     * @returns {void}
     */
    onEditorReady() {
      // Captured, like every other deferred piece of work in this composer: by the
      // time the callback runs the drawer may have closed, or opened on another
      // message, and this editor's starting point is not that one's.
      const session = this.draftSession;
      this.bindInlineImageDrops();
      this.editorEchoing = true;
      setTimeout(() => {
        this.editorEchoing = false;
        if (this.newEmailDrawer && session === this.draftSession) {
          this.savedSignature = this.composeSignature();
        }
      });
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
      const snapshot = this.snapshotDraft();
      if (!snapshot.hasContent || (snapshot.signature === this.savedSignature && !push)) {
        return;
      }
      this.storeDraft(this.draftSession, snapshot, push, false);
    },
    /**
     * Puts an uploaded file onto the draft, so it stops depending on this browser
     * session.
     *
     * A commons upload is a temporary file. A draft's whole point is to be there
     * tomorrow, so a draft whose files were uploads would resume showing chips that
     * resolve to nothing and would send with nothing attached. This hands the upload
     * to the server, which copies it into the platform's file store and answers with
     * the attachment as stored.
     *
     * There has to be a draft to attach to, and at the moment the first file lands
     * there often is not: the local id only exists once a save has come back with
     * one, and attaching a file to an empty composer is a perfectly ordinary way to
     * start a mail. So a draft is forced into existence first, through the same
     * queued save every other write goes through — which is also what makes this safe
     * against a save that is already in flight.
     *
     * The revision the server answers with is taken on board, because attaching is an
     * edit and the row has moved past what this composer believed. Without that, the
     * next autosave would arrive stale and be dropped, and the words typed since the
     * paperclip would sit unsaved until the one after it.
     *
     * @param {object} entry - the uploaded attachment, carrying its upload id
     * @returns {Promise} resolves with the stored attachment, or rejects
     */
    async persistAttachment(entry) {
      const session = this.draftSession;
      if (!session.localId) {
        await this.forceDraft(session);
      }
      if (!session.localId || session !== this.draftSession) {
        // No draft could be created, or the composer has moved on to another message
        // while this file was going up. Either way this file has nothing to belong to.
        throw new Error('No draft to attach to');
      }
      const draft = await this.$emailConnectorMailBoxService.addDraftAttachment(session.localId, {
        uploadId: entry.uploadId,
        name: entry.name,
        mimeType: entry.mimeType,
        size: entry.size,
      });
      this.applyDraftRevision(session, draft);
      const stored = (draft?.content?.attachments || []);
      return stored.length ? stored[stored.length - 1] : null;
    },
    /**
     * Takes a stored file off the draft. Same revision bookkeeping as attaching, for
     * the same reason: a detach is an edit.
     *
     * @param {object} entry - the stored attachment, carrying its own id
     * @returns {Promise} resolves once the file is off the draft
     */
    async unpersistAttachment(entry) {
      const session = this.draftSession;
      if (!session.localId || !entry?.id) {
        return;
      }
      const draft = await this.$emailConnectorMailBoxService.removeDraftAttachment(session.localId, entry.id);
      this.applyDraftRevision(session, draft);
    },
    /**
     * Creates the draft row this composer does not have one for yet, by running one
     * save through the session's own queue.
     *
     * Through the queue rather than around it, because that is what makes a draft's
     * local id exist exactly once: a save started here alongside one already in flight
     * would both carry no id, and the server would mint a second draft of one message.
     *
     * @param {object} session - the draft session to give an id to
     * @returns {Promise} resolves once the session has an id, or gave up
     */
    forceDraft(session) {
      const snapshot = this.snapshotDraft();
      this.storeDraft(session, snapshot, false, false);
      return session.queue;
    },
    /**
     * Takes on board a revision the server stepped without the composer asking — what
     * attaching and detaching do.
     *
     * The saved signature is deliberately left alone: the words on screen have not
     * changed, and clearing it would make the next pause re-save text that is already
     * stored.
     *
     * @param {object} session - the draft session
     * @param {object} draft - the draft as the server answered with it
     * @returns {void}
     */
    applyDraftRevision(session, draft) {
      if (!draft) {
        return;
      }
      if (draft.draftRevision) {
        session.revision = Math.max(session.revision, draft.draftRevision);
      }
      session.state = draft.draftState;
    },
    /**
     * Writes one revision of a draft, behind whatever save of the same session is
     * still in flight.
     *
     * The queue is not caution about load, it is about identity: a draft's local id
     * exists only once a save has come back with it, so two saves in flight together
     * would both carry none, and the server would answer the second with a second
     * draft of the same reply. Chained, the later save reads the id the earlier one
     * minted — which is also what lets a close save under a draft whose first save has
     * not answered yet.
     *
     * Everything it writes goes to the session record it was given, and to the
     * composer only while that record is still the one on screen. A save that outlives
     * its composer session must not put its draft's id back into fields the next reply
     * is being typed into.
     *
     * @param {object} session - the draft session this save belongs to
     * @param {object} snapshot - the composed state to store
     * @param {boolean} push - whether to also upload the draft to the mail server
     * @param {boolean} closing - whether this is the save a close fired, which tells
     *          the conversation about its result whatever that result is
     * @returns {void}
     */
    storeDraft(session, snapshot, push, closing) {
      this.savedSignature = snapshot.signature;
      session.pending++;
      session.queue = session.queue.then(() => {
        const payload = {
          draftLocalId: session.localId,
          draftRevision: ++session.revision,
          mailHeaderId: session.localId ? null : snapshot.parentMessageId,
          subject: snapshot.subject,
          content: {body: snapshot.body},
          to: snapshot.to,
          cc: snapshot.cc,
          bcc: snapshot.bcc,
        };
        // Captured before the request: the answer is judged against what this session
        // believed a moment ago, and the assignment below is what replaces it.
        const previousState = session.state;
        return this.$emailConnectorMailBoxService.saveDraft(payload, push)
          .then(saved => this.applySavedDraft(session, saved, previousState, push, push && !closing));
      }).catch(() => {
        // Let the next change try again rather than pretending this one landed.
        if (session === this.draftSession) {
          this.savedSignature = null;
        }
      }).finally(() => {
        session.pending--;
        if (closing) {
          this.$root.$emit('refresh-email-box');
        }
      });
    },
    /**
     * Takes in what the server made of a save.
     *
     * @param {object} session - the draft session the save belonged to
     * @param {object} saved - the draft as stored, or nothing when there is no such
     *          draft any more
     * @param {string} previousState - what the session believed before this save
     * @param {boolean} push - whether this save was asked to reach the mail server,
     *          which is the only kind that can find out the account has no Drafts
     *          folder to reach
     * @param {boolean} announce - whether the conversation and the folder list should
     *          be re-read now (a close announces itself, so it does not ask here)
     * @returns {void}
     */
    applySavedDraft(session, saved, previousState, push, announce) {
      if (!saved) {
        // The draft this session was writing is gone: sent or discarded from another
        // tab, or deleted in another mail client and reconciled away by the sync.
        // Forgetting the id is most of the reaction — what is still on screen belongs
        // to nothing now — but the saved signature has to be forgotten with it, or a
        // composer the user does not type into again would close believing it had
        // nothing to save, and the words in front of them would go. Cleared, the very
        // next save writes them as a new draft of their own, which is the only honest
        // answer when the two versions can no longer be reconciled.
        session.localId = null;
        session.state = null;
        if (session === this.draftSession) {
          this.savedSignature = null;
          this.notifyDraftGone();
        }
        return;
      }
      session.localId = saved.draftLocalId;
      session.state = saved.draftState;
      if (saved.draftRevision) {
        session.revision = Math.max(session.revision, saved.draftRevision);
      }
      if (session === this.draftSession) {
        this.notifyIfServerCopyGone(previousState, saved);
        if (push) {
          this.notifyIfLocalOnly(saved);
        }
      }
      if (announce) {
        this.$root.$emit('refresh-email-box');
      }
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
     * Tells the user their draft no longer exists — and, by saying so while their
     * text is still on screen, that nothing of theirs has been lost.
     *
     * Only while the composer is actually open on something. The same answer comes
     * back for an autosave that was in flight when the user pressed Send, and
     * congratulating someone on a mail they have just sent with a notice about a
     * missing draft would be noise about a non-event.
     *
     * @returns {void}
     */
    notifyDraftGone() {
      if (!this.newEmailDrawer || !this.hasContent) {
        return;
      }
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {
        alertType: 'info',
        alertMessage: this.$t('emailConnector.mailBox.newEmail.drawer.draft.goneElsewhere'),
      }}));
    },
    /**
     * Tells the user that the copy of this draft in their mailbox has been removed
     * somewhere else, while what they are typing is untouched.
     *
     * The signal is a state the composer cannot reach on its own: a draft it has
     * seen on the server (SYNCED, or DIRTY — uploaded once and typed into since)
     * answering LOCAL_ONLY. An ordinary edit of either keeps DIRTY, and a failed
     * push leaves the state alone, so this transition means one thing only — the
     * sync found the server copy gone and put the row back to "never uploaded" so
     * the next save re-uploads it. The user's version is not overwritten by any of
     * that; it wins, and the notice is how they learn their phone no longer has it.
     *
     * @param {string} previousState - what the composer believed before this save
     * @param {object} saved - the draft as the server stored it
     * @returns {void}
     */
    notifyIfServerCopyGone(previousState, saved) {
      if (saved.draftState !== 'LOCAL_ONLY' || (previousState !== 'SYNCED' && previousState !== 'DIRTY')
          || this.serverCopyGoneNotified) {
        return;
      }
      this.serverCopyGoneNotified = true;
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {
        alertType: 'info',
        alertMessage: this.$t('emailConnector.mailBox.newEmail.drawer.draft.serverCopyGone'),
      }}));
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
     * Forgets the draft this composer session was writing, by starting a session.
     * Called when the composer opens on something else, and when what it was holding
     * has been sent or discarded.
     *
     * A NEW record rather than a cleared one: a save of the previous session may still
     * be in flight, and it goes on writing to the object it was given — where it can
     * no longer put a stale id under the draft that is about to be typed here.
     *
     * @returns {void}
     */
    resetDraftTracking() {
      this.cancelDraftTimers();
      this.draftSession = newDraftSession();
      this.savedSignature = null;
      this.localOnlyNotified = false;
      this.serverCopyGoneNotified = false;
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
      // Two send endpoints, and which one applies is decided by whether there is a
      // draft row behind this composer. There is one whenever the user paused for a
      // second, so this is the common path for anything longer than a one-liner. It
      // exists because sending a draft is not "send, then tidy up from the client":
      // the save, the send and the two removals have to happen in one order, on the
      // server, where a failure between them can be reasoned about.
      const send = this.draftSession.localId
        ? this.$emailConnectorMailBoxService.sendDraft(this.draftSession.localId, this.email)
        : this.$emailConnectorMailBoxService.sendEmail(this.email);
      send.then(() => {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.send.success'), 'success');
        // The row and its server copy are already gone; all that is left here is to
        // forget the draft so the close below cannot save it back.
        this.emptyComposer();
        // Announced BEFORE the reload, and it is the ordering that matters: the mailbox
        // re-arms its watch on this, and the reload right after is the first poll of
        // that watch. The copy of this message is not in the Sent folder cache yet — the
        // server files it and the add-on re-reads that folder in the background — so the
        // list has to keep re-reading itself for a moment rather than once, now.
        this.$root.$emit('email-sent');
        this.$root.$emit('refresh-email-box');
        this.close();
      }).catch(() => {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.send.error'), 'error');
      }).finally(() => this.loading = false);
    },
    /**
     * The "On <date>, <sender> wrote:" line above a reply's quoted block.
     *
     * Every part of it is somebody else's: the date the message arrived and the name
     * and address it came from. So every part of it is escaped on the way into
     * markup, and the only thing this composer contributes is the sentence around
     * them — which is UI text, so it comes from the bundle and not from here.
     *
     * The date is formatted by the same helper the forward header and the message
     * header use, in full, so a quote reads in the reader's language and clock
     * rather than in a format invented for this one line. The word between the day
     * and the time is the forward header's: it is the same word, already translated
     * into every locale this addon ships, and a second key for it would only ask
     * translators to say "at" twice and read as the untranslated key until they had.
     *
     * @param {object} email - the message being replied to
     * @returns {string} the attribution line, ready to sit in markup
     */
    replyAttribution(email) {
      const at = this.$t('emailConnector.mailBox.forwardEmail.drawer.date.at');
      const date = this.$emailConnectorMailBoxService.formatDateString(email.receivedDate, '', at, true);
      const name = personName(email.sender);
      const address = email.sender?.address?.trim();
      // Name and address together, the way a mail header reads, and whichever one
      // exists when only one does — never a dangling pair of empty angle brackets.
      const sender = name && address ? `${escapeHtml(name)} &lt;${escapeHtml(address)}&gt;`
        : escapeHtml(name || address || '');
      return this.$t('emailConnector.mailBox.replyEmail.drawer.quote.attribution', {
        0: escapeHtml(date),
        1: sender,
      });
    },
    /**
     * One addressed person in the quoted header a forward carries: their name in
     * front of their address, or the address alone when they have no name — which
     * this line used to print as the word undefined.
     *
     * @param {object} recipient - an addressed person of the forwarded message
     * @returns {string} the markup for that person
     */
    quotedRecipient(recipient) {
      const address = recipient.address?.trim();
      const name = personName(recipient);
      const link = `<span>&lt;<a href="mailto:${address}">${address}</a>&gt;</span>`;
      return name ? `${name} ${link}` : link;
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
     * The same chips for a draft save, keeping the name each one carries so that
     * resuming the draft puts back the chips the user typed rather than bare
     * addresses. Avatars stay out: they are resolved from the address on the way
     * back in, and storing a URL would only let it go stale.
     *
     * A name is stored only when it is one somebody chose. The chip of a bare typed
     * address reads as that address, and writing that back as a display name would
     * store an invention — after which the row can no longer say whether the person
     * has a name, and a later lookup that finds their real one has to fight it.
     *
     * @param {Array} recipients - the field's chips
     * @returns {Array} [{name, address}] entries
     */
    toDraftRecipients(recipients) {
      return (recipients || []).map(recipient => {
        const address = recipient.address?.trim();
        const name = personName(recipient);
        return {
          name: name === address ? null : name,
          address: address,
        };
      }).filter(recipient => recipient.address);
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