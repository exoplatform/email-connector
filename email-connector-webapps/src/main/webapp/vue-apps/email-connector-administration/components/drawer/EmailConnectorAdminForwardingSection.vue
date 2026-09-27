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
  <!-- The connector's forwarding (EXO-90656): whether its users may forward their mail
       from eXo, and to which domains. Saved with the connector, by the drawer's Save;
       applies at once. Until it is saved, the deployment's properties apply, and the
       deployment's kill switch always wins, which the section says. -->
  <div>
    <v-list-item-title class="pa-0 mt-7 mb-4 text-header">
      {{ $t('emailConnector.admin.connectors.drawer.forwarding') }}
    </v-list-item-title>
    <div class="d-flex align-center justify-space-between full-width mb-2">
      <div id="emailConnectorForwardingLabel">
        {{ $t('emailConnector.admin.connectors.drawer.forwarding.enabled') }}
      </div>
      <v-switch
        :input-value="value.authoringEnabled"
        :disabled="value.killSwitch"
        aria-labelledby="emailConnectorForwardingLabel"
        :ripple="false"
        class="ma-0 width-fit-content"
        hide-details
        @change="update({ authoringEnabled: $event })" />
    </div>
    <div class="caption text-light-color text-wrap mb-4">
      {{ $t('emailConnector.admin.connectors.drawer.forwarding.enabled.help') }}
    </div>
    <div
      v-if="value.killSwitch"
      class="caption warning--text text-wrap mb-4"
      role="status">
      {{ $t('emailConnector.admin.connectors.drawer.forwarding.killSwitch') }}
    </div>
    <div class="mb-2">
      {{ $t('emailConnector.admin.connectors.drawer.forwarding.domains') }}
    </div>
    <v-combobox
      :value="value.allowedDomains"
      :rules="[domainsRule]"
      :aria-label="$t('emailConnector.admin.connectors.drawer.forwarding.domains')"
      :placeholder="$t('emailConnector.admin.connectors.drawer.forwarding.domains.placeholder')"
      :delimiters="[',', ' ', ';']"
      class="pt-0"
      append-icon=""
      multiple
      small-chips
      deletable-chips
      outlined
      dense
      @change="update({ allowedDomains: normalize($event) })" />
    <div class="caption text-light-color text-wrap">
      {{ $t('emailConnector.admin.connectors.drawer.forwarding.domains.help') }}
    </div>
    <div v-if="!value.saved" class="caption text-light-color text-wrap mt-2">
      {{ $t('emailConnector.admin.connectors.drawer.forwarding.properties') }}
    </div>
  </div>
</template>

<script>
/** A plain domain, as the server accepts it. */
const DOMAIN = /^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

export default {
  props: {
    // {authoringEnabled, allowedDomains, saved, killSwitch}
    value: {
      type: Object,
      default: () => ({ authoringEnabled: false, allowedDomains: [], saved: false, killSwitch: false }),
    },
  },
  methods: {
    /**
     * Tells the drawer the new value, and whether it can be saved.
     *
     * @param {Object} change - the fields that changed
     * @returns {void}
     */
    update(change) {
      const value = { ...this.value, ...change };
      this.$emit('input', value);
      this.$emit('valid', this.domainsRule(value.allowedDomains) === true);
    },
    /**
     * The domains as the server keeps them: trimmed, lower-case, a leading at-sign
     * dropped, each once.
     *
     * @param {String[]} domains - the domains typed
     * @returns {String[]} the domains
     */
    normalize(domains) {
      const clean = (domains || [])
        .map(domain => String(domain || '').trim().toLowerCase().replace(/^@/, ''))
        .filter(domain => domain);
      return [...new Set(clean)];
    },
    /**
     * The domains' rule: each a plain domain.
     *
     * @param {String[]} domains - the domains
     * @returns {Boolean|String} true, or why not
     */
    domainsRule(domains) {
      return (domains || []).every(domain => DOMAIN.test(domain))
        || this.$t('emailConnector.admin.connectors.drawer.forwarding.domains.invalid');
    },
  },
};
</script>
