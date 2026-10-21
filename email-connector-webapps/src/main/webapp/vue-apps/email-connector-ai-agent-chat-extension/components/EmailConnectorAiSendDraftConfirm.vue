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
    message() {
      if (!this.files.length) {
        return this.withLinks(this.$t('AiAgentChat.tool.confirm.send_email_draft', this.toolInput));
      }
      return this.withLinks(this.$t('AiAgentChat.tool.confirm.send_email_draft.withFiles', {
        ...this.toolInput,
        files: this.files.map(this.escape).join(', '),
      }));
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
     * A file name as text inside the card's HTML: the names come from the assistant.
     *
     * @param {string} name the file name
     * @returns {string} the name, its HTML characters escaped
     */
    escape(name) {
      return name.replace(/[&<>"']/g, character => `&#${character.charCodeAt(0)};`);
    },
  },
};
</script>
