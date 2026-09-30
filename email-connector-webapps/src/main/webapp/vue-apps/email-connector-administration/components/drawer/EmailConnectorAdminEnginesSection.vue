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
  <!-- The connector's engines (EXO-90793): which engine its server rules (automatic
       reply, forwarding) and its mailbox sharing go through, chosen among the engines
       installed. Saved with the connector, by the drawer's Save; applies at once. A
       deployment property that decides an engine wins, which the section says. -->
  <div>
    <v-list-item-title class="pa-0 mt-7 mb-4 text-header">
      {{ $t('emailConnector.admin.connectors.drawer.engines') }}
    </v-list-item-title>
    <div
      v-if="value.authProviderMissing"
      class="caption error--text text-wrap mb-4"
      role="alert">
      {{ $t('emailConnector.admin.connectors.drawer.engines.authProviderMissing', {0: value.authProviderName}) }}
    </div>
    <v-label for="emailConnectorRulesEngine">
      {{ $t('emailConnector.admin.connectors.drawer.engines.rules') }}
    </v-label>
    <v-select
      id="emailConnectorRulesEngine"
      :value="value.rulesEngine"
      :items="items('rules', value.rulesEngines, value.rulesEngine)"
      :disabled="!!value.rulesEngineProperty"
      class="pt-0"
      outlined
      dense
      hide-details
      @change="update({ rulesEngine: $event })" />
    <div class="caption text-light-color text-wrap mt-2 mb-4">
      {{ overriddenOr(value.rulesEngineProperty, 'emailConnector.admin.connectors.drawer.engines.rules.help') }}
    </div>
    <v-label for="emailConnectorAclEngine">
      {{ $t('emailConnector.admin.connectors.drawer.engines.acl') }}
    </v-label>
    <v-select
      id="emailConnectorAclEngine"
      :value="value.aclEngine"
      :items="items('acl', value.aclEngines, value.aclEngine)"
      :disabled="!!value.aclEngineProperty"
      class="pt-0"
      outlined
      dense
      hide-details
      @change="update({ aclEngine: $event })" />
    <div class="caption text-light-color text-wrap mt-2">
      {{ overriddenOr(value.aclEngineProperty, 'emailConnector.admin.connectors.drawer.engines.acl.help') }}
    </div>
  </div>
</template>

<script>
export default {
  props: {
    // {rulesEngine, aclEngine, rulesEngines, aclEngines, rulesEngineProperty,
    //  aclEngineProperty, authProviderName, authProviderMissing}
    value: {
      type: Object,
      default: () => ({ rulesEngine: 'none', aclEngine: 'imap', rulesEngines: [], aclEngines: [] }),
    },
  },
  methods: {
    /**
     * Tells the drawer the new value.
     *
     * @param {Object} change - the fields that changed
     * @returns {void}
     */
    update(change) {
      this.$emit('input', { ...this.value, ...change });
    },
    /**
     * The choices of one engine: the ones installed, and the one configured when it
     * is not installed, said to be so rather than silently replaced.
     *
     * @param {String} kind - 'rules' or 'acl'
     * @param {String[]} installed - the engines installed
     * @param {String} configured - the engine configured
     * @returns {Object[]} the select's items
     */
    items(kind, installed, configured) {
      const names = [...(installed || [])];
      const items = names.map(name => ({ text: this.label(kind, name), value: name }));
      if (configured && !names.includes(configured)) {
        items.push({
          text: this.$t('emailConnector.admin.connectors.drawer.engines.notInstalled', {0: this.label(kind, configured)}),
          value: configured,
        });
      }
      return items;
    },
    /**
     * An engine's label: its translation when a bundle has one, its name otherwise.
     *
     * @param {String} kind - 'rules' or 'acl'
     * @param {String} name - the engine's name
     * @returns {String} the label
     */
    label(kind, name) {
      const key = `emailConnector.admin.connectors.drawer.engines.${kind}.${name}`;
      return this.$te(key) ? this.$t(key) : name.charAt(0).toUpperCase() + name.slice(1);
    },
    /**
     * The help under a choice, or, when a deployment property decides it, which one.
     *
     * @param {String} property - the property deciding the engine, or null
     * @param {String} helpKey - the help's key
     * @returns {String} the text
     */
    overriddenOr(property, helpKey) {
      return property
        ? this.$t('emailConnector.admin.connectors.drawer.engines.property', {0: property})
        : this.$t(helpKey);
    },
  },
};
</script>
