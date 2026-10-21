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
  <ai-agent-text :text="message" />
</template>
<script>
export default {
  props: {
    toolExecution: {
      type: Object,
      default: null,
    },
  },
  computed: {
    toolInput() {
      return this.toolExecution?.toolInput || {};
    },
    files() {
      const attachments = Array.isArray(this.toolInput.attachments) ? this.toolInput.attachments : [];
      return attachments.map(name => String(name || '').trim()).filter(name => name);
    },
    // Every value as text: a reply draft's subject is the received mail's, and the card
    // is rendered as HTML, so markup in it must not hide the recipients or the files.
    parameters() {
      return {
        subject: this.escape(this.toolInput.subject),
        to: this.list(this.toolInput.to),
        cc: this.list(this.toolInput.cc),
        bcc: this.list(this.toolInput.bcc),
        files: this.files.map(this.escape).join(', '),
      };
    },
    message() {
      const key = this.files.length
        ? 'AiAgentChat.tool.confirm.send_email_draft.withFiles'
        : 'AiAgentChat.tool.confirm.send_email_draft';
      return this.withLinks(this.$t(key, this.parameters));
    },
  },
  methods: {
    /**
     * The card's text with the user and content references marked as the AI app's own
     * approval card marks them, so a draft without files reads as it did before.
     *
     * @param {string} text the card's text
     * @returns {string} the text, its references marked
     */
    withLinks(text) {
      return text
        .replace(/(\/user:([a-zA-Z0-9]+))/ig, '<user-by-name>$2</user-by-name>')
        .replace(/(\/[a-z]+:[a-zA-Z0-9]+)/ig, '<content-link>$1</content-link>');
    },
    /**
     * A value as text inside the card's HTML: the values come from the assistant and,
     * through a reply's subject, from a received mail.
     *
     * @param {*} value the value
     * @returns {string} the value as a string, its HTML characters escaped
     */
    escape(value) {
      return String(value ?? '').replace(/[&<>"']/g, character => `&#${character.charCodeAt(0)};`);
    },
    /**
     * Addresses as text inside the card's HTML, one after the other.
     *
     * @param {*} addresses the addresses, a list or a single value
     * @returns {string} the addresses escaped, separated by commas
     */
    list(addresses) {
      return (Array.isArray(addresses) ? addresses : [addresses]).filter(address => address).map(this.escape).join(', ');
    },
  },
};
</script>
