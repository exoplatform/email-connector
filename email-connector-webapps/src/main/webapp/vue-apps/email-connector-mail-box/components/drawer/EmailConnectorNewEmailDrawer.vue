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
      loading: false,
      title: '',
    };
  },
  created() {
    this.$root.$on('open-new-email-drawer', (email, forward, replyAll) => {
      this.open(email, forward, replyAll);
    });
    this.$root.$on('send-email', (email) => {
      this.sendEmail(email);
    });
  },
  computed: {
    disabled() {
      return !this.toEmails;
    },
    confirmClose() {
      return !!(this.email.content.body || this.email.subject || this.toEmails || this.ccEmails || this.bccEmails);
    }
  },
  methods: {
    open(email, forward, replyAll) {
      this.title = forward ? this.$t('emailConnector.mailBox.forwardEmail.drawer.title') : email ? this.$t('emailConnector.mailBox.replyEmail.drawer.title') : this.$t('emailConnector.mailBox.newEmail.drawer.title');
      if (email) {
        if (!forward) {
          this.toEmails = email.sender ? email.sender.address : '';
          if (replyAll) {
            this.ccEmails = [...new Set([...(email.to || []), ...(email.cc || [])]
              .filter(item => item.address !== this.$emailConnectorMailBoxService.accountEmail)
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
            bodyParts.push(`${this.$t('emailConnector.mailBox.newEmail.drawer.to.label')} ${email.to.map(item => `${item.name?.trim()} &lt;${item.address?.trim()}&gt;`).join(', ')}`);
          }
          if (email.cc?.length) {
            bodyParts.push('<br>');
            bodyParts.push(`${this.$t('emailConnector.mailBox.newEmail.drawer.cc.label')} ${email.cc.map(item => `${item.name?.trim()} &lt;${item.address?.trim()}&gt;`).join(', ')}`);
          }
          bodyParts.push('<br><br><br>');
          bodyParts.push(email.content.body || '');
          this.email.content.body = bodyParts.join('\n');
        }
        this.email.subject = `${forward ? this.$t('emailConnector.mailBox.forwardEmail.drawer.subject.prefix') : this.$t('emailConnector.mailBox.replyEmail.drawer.subject.prefix')} ${email.subject || ''}`;
      }
      this.newEmailDrawer = true;
    },
    close() {
      this.toEmails = '';
      this.ccEmails = '';
      this.bccEmails = '';
      this.email.subject = '';
      this.email.content.body = '';
      this.email.mailHeaderId = null;
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
      html = html.replace(/<([^>]*@[^>]+)>/g, '&lt;$1&gt;');
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