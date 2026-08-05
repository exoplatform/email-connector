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
      <rich-editor
        ref="emailContent"
        v-model="email.content.body"
        :placeholder="$t('emailConnector.mailBox.newEmail.drawer.content.placeholder')"
        ck-editor-type="email"
        class="mx-4 mt-3"
        content-link-enabled
        :tag-enabled="false"
        disable-suggester
        hide-chars-count />
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
     * Whether Send is unavailable: no recipient, or an attachment still going up.
     *
     * @returns {boolean} true when sending must wait
     */
    disabled() {
      return !this.to.length || this.attachments.some(attachment => attachment.uploading);
    },
    /**
     * Whether closing the drawer would throw away work, and so needs confirming.
     *
     * @returns {boolean} true when the composer holds anything
     */
    confirmClose() {
      return !!(this.email.content.body || this.email.subject) || !!this.to.length || !!this.cc.length || !!this.bcc.length;
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
      this.email.subject = '';
      this.email.content.body = '';
      this.email.mailHeaderId = null;
      this.email.attachments = [];
      this.attachments = [];
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