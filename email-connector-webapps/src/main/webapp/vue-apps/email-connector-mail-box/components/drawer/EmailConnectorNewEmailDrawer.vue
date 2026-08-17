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
    :confirm-close="confirmClose"
    :confirm-close-labels="{
      title: $t('emailConnector.mailBox.newEmail.drawer.confirmCancel.title'),
      message: $t('emailConnector.mailBox.newEmail.drawer.confirmCancel.message'),
      ok: $t('emailConnector.mailBox.newEmail.drawer.confirmCancel.button.yes'),
      cancel: $t('emailConnector.mailBox.newEmail.drawer.confirmCancel.button.no')
    }"
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
        :placeholder="$t('emailConnector.mailBox.newEmail.drawer.to.placeholder')"
        @pending="pendingTo = $event" />
      <v-divider />
      <div>
        <email-connector-recipient-field
          v-model="cc"
          class="mx-4"
          field-id="cc"
          :label="$t('emailConnector.mailBox.newEmail.drawer.cc.label')"
          :placeholder="$t('emailConnector.mailBox.newEmail.drawer.cc.placeholder')"
          @pending="pendingCc = $event" />
        <v-divider />
        <email-connector-recipient-field
          v-model="bcc"
          class="mx-4"
          field-id="bcc"
          :label="$t('emailConnector.mailBox.newEmail.drawer.bcc.label')"
          :placeholder="$t('emailConnector.mailBox.newEmail.drawer.bcc.placeholder')"
          @pending="pendingBcc = $event" />
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
      <div class="d-flex">
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
      // What is typed into each field but not yet a chip. Send stays reachable
      // while To holds something, so clicking Send can blur the field into
      // committing it. After that blur, anything still pending is text the field
      // REFUSED as an address -- so it is also the signal that the mail would
      // leave short of a recipient the user believes they addressed. Cc and Bcc
      // carry it for that second reason only; they never gate Send on their own.
      pendingTo: '',
      pendingCc: '',
      pendingBcc: '',
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
    };
  },
  created() {
    this.$root.$on('open-new-email-drawer', (email, forward, replyAll, prefill) => {
      this.open(email, forward, replyAll, prefill);
    });
    this.$root.$on('send-email', (email) => {
      this.sendEmail(email);
    });
  },
  computed: {
    /**
     * Whether Send is unavailable: no recipient at all, or an attachment still
     * going up.
     *
     * A recipient typed but not yet committed counts: the button must stay live
     * so that pressing it blurs the field, which commits the chip, before the
     * click lands. A disabled button receives no mousedown, so gating on the
     * chip list alone left a filled To field with no way to send from it.
     *
     * @returns {boolean} true when sending must wait
     */
    disabled() {
      return (!this.to.length && !this.pendingTo) || this.attachments.some(attachment => attachment.uploading);
    },
    /**
     * Whether closing the drawer would throw away work, and so needs confirming.
     *
     * @returns {boolean} true when the composer holds anything
     */
    confirmClose() {
      // A recipient typed but not yet a chip is work too: closing over it used
      // to discard it silently, which is the same loss the pending term was
      // introduced to notice.
      return !!(this.email.content.body || this.email.subject) || !!this.to.length || !!this.cc.length || !!this.bcc.length
          || !!this.pendingTo || !!this.pendingCc || !!this.pendingBcc;
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
      this.title = this.drawerTitle(email, forward);
      this.seedRecipients(email, forward, replyAll, prefill);
      if (email) {
        if (forward) {
          this.email.content.body = this.buildForwardedBody(email);
        }
        this.email.subject = `${forward
          ? this.$t('emailConnector.mailBox.forwardEmail.drawer.subject.prefix')
          : this.$t('emailConnector.mailBox.replyEmail.drawer.subject.prefix')} ${email.subject || ''}`;
      }
      this.newEmailDrawer = true;
      this.$nextTick(() => this.measureEditorMaxHeight());
    },
    /**
     * The drawer's heading for the three ways it can be opened.
     *
     * @param {object} email - the message being replied to or forwarded, if any
     * @param {boolean} forward - whether this is a forward
     * @returns {string} the translated title
     */
    drawerTitle(email, forward) {
      if (forward) {
        return this.$t('emailConnector.mailBox.forwardEmail.drawer.title');
      }
      if (email) {
        return this.$t('emailConnector.mailBox.replyEmail.drawer.title');
      }
      return this.$t('emailConnector.mailBox.newEmail.drawer.title');
    },
    /**
     * Fills To and Cc for the way the composer was opened: a new mail takes the
     * prefill hand-off, a reply answers the sender (everyone, on reply-all), and
     * a forward addresses nobody — the user chooses.
     *
     * @param {object} email - the message being replied to or forwarded, if any
     * @param {boolean} forward - whether this is a forward
     * @param {boolean} replyAll - whether the reply addresses everyone
     * @param {object} prefill - recipients to seed a new mail with
     * @returns {void}
     */
    seedRecipients(email, forward, replyAll, prefill) {
      if (!email) {
        if (prefill?.to?.length) {
          this.to = this.toRecipients(prefill.to);
        }
        return;
      }
      if (forward) {
        return;
      }
      this.to = email.replyTo?.length > 0
        ? this.toRecipients(email.replyTo)
        : this.toRecipients([email.sender]);
      if (replyAll) {
        this.cc = this.toRecipients([...(email.to || []), ...(email.cc || [])]
          .filter(item => item.address !== email.userEmail));
      }
      this.email.mailHeaderId = email.mailHeaderId;
    },
    /**
     * The quoted original that a forward opens with: a header block naming who
     * sent it, when, and to whom, then the message itself.
     *
     * @param {object} email - the message being forwarded
     * @returns {string} the composer's starting body
     */
    buildForwardedBody(email) {
      const bodyParts = [
        '<br><br>',
        this.$t('emailConnector.mailBox.forwardEmail.drawer.forwardedMessage'),
        '<br>',
        `${this.$t('emailConnector.mailBox.forwardEmail.drawer.from')} ${email.sender.name?.trim()} &lt;${email.sender.address?.trim()}&gt;`,
        '<br>',
        `${this.$t('emailConnector.mailBox.forwardEmail.drawer.date')} ${this.$emailConnectorMailBoxService.formatDateString(email.receivedDate, '', this.$t('emailConnector.mailBox.forwardEmail.drawer.date.at'), true)}`
      ];
      // One append per section: the break and the line it precedes are a single
      // thing, not two statements that happen to have to stay adjacent.
      if (email.subject) {
        bodyParts.push('<br>', `${this.$t('emailConnector.mailBox.forwardEmail.drawer.subject')} ${email.subject}`);
      }
      if (email.to?.length) {
        bodyParts.push('<br>', `${this.$t('emailConnector.mailBox.newEmail.drawer.to.label')} ${this.quotedRecipients(email.to)}`);
      }
      if (email.cc?.length) {
        bodyParts.push('<br>', `${this.$t('emailConnector.mailBox.newEmail.drawer.cc.label')} ${this.quotedRecipients(email.cc)}`);
      }
      bodyParts.push('<br><br><br>', email.content.body || '');
      return bodyParts.join('\n');
    },
    /**
     * One quoted recipient list for the forwarded header, name then mailto.
     *
     * @param {Array} recipients - the addressees to render
     * @returns {string} the comma-separated markup
     */
    quotedRecipients(recipients) {
      return recipients.map(item => `${item.name?.trim()} <span>&lt;<a href="mailto:${item.address?.trim()}">${item.address?.trim()}</a>&gt;</span>`).join(', ');
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
     * Closes and empties the composer.
     *
     * @returns {void}
     */
    close() {
      this.to = [];
      this.cc = [];
      this.bcc = [];
      // Reset with its siblings: the content template is v-if'd, so the field is
      // destroyed here and rebuilt with term '' -- an initial value, which the
      // watcher does not report. Left behind, a stale pending term would render
      // Send enabled on the next, empty composer, where clicking it would hit
      // the empty-To guard and do nothing at all.
      this.pendingTo = '';
      this.pendingCc = '';
      this.pendingBcc = '';
      this.email.subject = '';
      this.email.content.body = '';
      this.email.mailHeaderId = null;
      this.email.attachments = [];
      this.attachments = [];
      this.editorMaxHeight = 0;
      this.newEmailDrawer = false;
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
        // Send is reachable while To holds uncommitted text, so that clicking it
        // blurs the field and commits the chip first. By the time we run, that
        // blur has happened -- so anything STILL pending is text the field
        // refused as an address, in any of the three rows. Refusing to send on
        // it covers both shapes of the same loss: no chip at all, and a chip
        // beside a rejected address ("alice@x.com, bad-address" sending to alice
        // alone, with the warning about the other half disappearing along with
        // the drawer). The field's own message stays on screen and explains it.
        if (!this.to.length || this.pendingTo || this.pendingCc || this.pendingBcc) {
          return;
        }
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
      this.$emailConnectorMailBoxService.sendEmail(this.email).then(() => {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.send.success'), 'success');
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