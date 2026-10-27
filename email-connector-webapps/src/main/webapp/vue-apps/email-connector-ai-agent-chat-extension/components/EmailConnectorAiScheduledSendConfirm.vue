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
    // schedule_email_draft, reschedule_email or cancel_scheduled_email: the card's label
    // is the tool's own (EXO-91996).
    toolName() {
      return String(this.toolExecution?.toolName || '').replace(/[A-Z]/g, character => `_${character.toLowerCase()}`);
    },
    // Every value as text: a reply draft's subject is the received mail's, and the card
    // is rendered as HTML, so markup in it must not paint other recipients or another
    // time over the ones the user approves.
    parameters() {
      return {
        to: this.list(this.toolInput.to),
        cc: this.list(this.toolInput.cc),
        bcc: this.list(this.toolInput.bcc),
        subject: this.escape(this.toolInput.subject),
        send_at: this.escape(this.toolInput.send_at),
        time_zone: this.escape(this.toolInput.time_zone),
      };
    },
    message() {
      return this.$t(`AiAgentChat.tool.confirm.${this.toolName}`, this.parameters);
    },
  },
  methods: {
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
