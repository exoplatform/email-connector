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
      <v-list-item class="pa-0 mx-4" dense>
        <v-label for="to">
          <span class="text-subtitle-color">
            {{ $t('emailConnector.mailBox.newEmail.drawer.to.label') }}
          </span>
        </v-label>
        <v-text-field
          v-model="toEmails"
          class="pt-0"
          autocomplete="to"
          id="to"
          :aria-label="$t('emailConnector.mailBox.newEmail.drawer.to.label')"
          :placeholder="$t('emailConnector.mailBox.newEmail.drawer.to.placeholder')"
          type="text"
          required="required"
          solo
          flat
          single-line
          hide-details />
      </v-list-item>
      <v-divider />
      <div>
        <v-list-item class="pa-0 mx-4" dense>
          <v-label for="cc">
            <span class="text-subtitle-color">
              {{ $t('emailConnector.mailBox.newEmail.drawer.cc.label') }}
            </span>
          </v-label>
          <v-text-field
            v-model="ccEmails"
            class="pt-0"
            autocomplete="cc"
            id="cc"
            :aria-label="$t('emailConnector.mailBox.newEmail.drawer.cc.label')"
            :placeholder="$t('emailConnector.mailBox.newEmail.drawer.cc.placeholder')"
            type="text"
            solo
            flat
            single-line
            hide-details />
        </v-list-item>
        <v-divider />
        <v-list-item class="pa-0 mx-4" dense>
          <v-label for="bcc">
            <span class="text-subtitle-color">
              {{ $t('emailConnector.mailBox.newEmail.drawer.bcc.label') }}
            </span>
          </v-label>
          <v-text-field
            v-model="bccEmails"
            class="pt-0"
            autocomplete="bcc"
            id="bcc"
            :aria-label="$t('emailConnector.mailBox.newEmail.drawer.bcc.label')"
            :placeholder="$t('emailConnector.mailBox.newEmail.drawer.bcc.placeholder')"
            type="text"
            solo
            flat
            single-line
            hide-details />
        </v-list-item>
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
      toEmails: '',
      ccEmails: '',
      bccEmails: '',
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
    disabled() {
      return !this.toEmails || this.attachments.some(attachment => attachment.uploading);
    },
    confirmClose() {
      return !!(this.email.content.body || this.email.subject || this.toEmails || this.ccEmails || this.bccEmails);
    }
  },
  methods: {
    // prefill ({to: [{name, address}]}) seeds a NEW email's recipients — the
    // Contacts drawer's "compose to" hand-off. It only applies when no email is
    // passed, so reply/forward prefills stay exactly what they were.
    open(email, forward, replyAll, prefill) {
      this.attachments = [];
      if (!email && prefill?.to?.length) {
        this.toEmails = prefill.to.map(recipient => recipient.address?.trim()).filter(Boolean).join(', ');
      }
      this.title = forward ? this.$t('emailConnector.mailBox.forwardEmail.drawer.title') : email ? this.$t('emailConnector.mailBox.replyEmail.drawer.title') : this.$t('emailConnector.mailBox.newEmail.drawer.title');
      if (email) {
        if (!forward) {
          this.toEmails = email.replyTo?.length > 0
            ? email.replyTo.map(item => item.address?.trim()).filter(Boolean).join(', ')
            : email.sender.address;
          if (replyAll) {
            this.ccEmails = [...new Set([...(email.to || []), ...(email.cc || [])]
              .filter(item => item.address !== email.userEmail)
              .map(item => item.address?.trim())
              .filter(Boolean))].join(', ');
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
    close() {
      this.toEmails = '';
      this.ccEmails = '';
      this.bccEmails = '';
      this.email.subject = '';
      this.email.content.body = '';
      this.email.mailHeaderId = null;
      this.email.attachments = [];
      this.attachments = [];
      this.editorMaxHeight = 0;
      this.newEmailDrawer = false;
    },
    sendEmail(email) {
      if (email) {
        this.email = email;
      }
      else {
        this.email.to = this.toEmails.split(',')
          .map(email => ({ address: email.trim() }))
          .filter(email => email.address);
        this.email.cc = this.ccEmails.split(',')
          .map(email => ({ address: email.trim() }))
          .filter(email => email.address);
        this.email.bcc = [
          ...(this.email.bcc || []),
          ...this.bccEmails
            .split(',')
            .map(email => ({ address: email.trim() }))
            .filter(email => email.address)
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