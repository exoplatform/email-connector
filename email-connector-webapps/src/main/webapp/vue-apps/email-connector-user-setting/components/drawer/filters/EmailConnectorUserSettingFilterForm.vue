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
  <!-- One server filter's form (EXO-90652): its name, its conditions joined by "all" or
       "any", where the mail goes and which flags it gets, and whether the filters after
       it still apply. What this mail server cannot run is greyed out, per element, from
       the capabilities the drawer read. The folder a mail is filed into is picked from
       the user's own mirrored folders, never typed.
       Laid out like the platform's drawer forms, as the automatic reply's: a plain label
       above each field, outlined dense fields without a floating label, a section title
       over the conditions and over the actions, each on/off choice a switch at the end of
       its label's row, the condition rows with a minus button each and a plus button at
       the end of their title, hints in the platform's subtitle style. The condition row
       and the switch row are shared with the eXo filter form. -->
  <v-form class="d-flex flex-column" @submit.prevent>
    <div class="mb-2">
      {{ $t('UserSettings.emailConnector.filters.form.name') }}
    </div>
    <v-text-field
      v-model="name"
      :rules="[nameRule]"
      :aria-label="$t('UserSettings.emailConnector.filters.form.name')"
      class="border-box-sizing width-auto pt-0"
      maxlength="100"
      type="text"
      outlined
      dense />
    <email-connector-user-setting-filter-switch
      v-model="enabled"
      :label="$t('UserSettings.emailConnector.filters.form.enabled')" />
    <div class="d-flex align-center justify-space-between mt-4 mb-2">
      <span class="text-header">{{ $t('UserSettings.emailConnector.filters.form.when') }}</span>
      <v-btn
        :disabled="conditions.length >= 10"
        :title="$t('UserSettings.emailConnector.filters.form.addCondition')"
        :aria-label="$t('UserSettings.emailConnector.filters.form.addCondition')"
        icon
        @click="addCondition">
        <v-icon size="16">fas fa-plus</v-icon>
      </v-btn>
    </div>
    <v-radio-group
      v-model="matchAll"
      class="mt-0 mb-2 ms-n1 text-no-wrap"
      mandatory
      hide-details
      row>
      <v-radio :value="true" class="mx-0 me-4">
        <template #label>
          <span class="text-font-size">{{ $t('UserSettings.emailConnector.filters.form.matchAll') }}</span>
        </template>
      </v-radio>
      <v-radio :value="false" class="mx-0">
        <template #label>
          <span class="text-font-size">{{ $t('UserSettings.emailConnector.filters.form.matchAny') }}</span>
        </template>
      </v-radio>
    </v-radio-group>
    <email-connector-user-setting-filter-condition
      v-for="(condition, index) in conditions"
      :key="condition.key"
      v-model="conditions[index]"
      :field-items="fieldItems"
      :removable="conditions.length > 1"
      @remove="conditions.splice(index, 1)" />
    <div class="mt-2 mb-2 text-header">{{ $t('UserSettings.emailConnector.filters.form.then') }}</div>
    <div class="mb-2">
      {{ $t('UserSettings.emailConnector.filters.form.moveTo') }}
    </div>
    <v-select
      ref="moveToSelect"
      v-model="moveTo"
      :items="moveItems"
      :menu-props="{ bottom: true, offsetY: true }"
      :aria-label="$t('UserSettings.emailConnector.filters.form.moveTo')"
      class="pa-0"
      clearable
      dense
      outlined
      hide-details
      @blur="$refs.moveToSelect.blur()" />
    <email-connector-user-setting-filter-switch
      v-model="markRead"
      :label="$t('UserSettings.emailConnector.filters.form.markRead')"
      :disabled="!supports('MARK_READ')"
      class="mt-4" />
    <email-connector-user-setting-filter-switch
      v-model="star"
      :label="$t('UserSettings.emailConnector.filters.form.star')"
      :disabled="!supports('STAR')"
      class="mt-2" />
    <email-connector-user-setting-filter-switch
      v-model="stop"
      :label="$t('UserSettings.emailConnector.filters.form.stop')"
      class="mt-2" />
    <div v-if="!hasAction" class="text-subtitle mt-4">
      {{ $t('UserSettings.emailConnector.filters.form.actionRequired') }}
    </div>
    <div class="text-subtitle mt-4">
      {{ $t('UserSettings.emailConnector.filters.form.atDelivery') }}
    </div>
    <div class="text-subtitle mt-2">
      {{ $t('UserSettings.emailConnector.filters.form.publishes') }}
    </div>
  </v-form>
</template>

<script>
import { FIELDS, headerError, isFlagField, isSupported, valueError } from '../../../js/EmailConnectorFilters.js';

let nextKey = 1;

export default {
  props: {
    rule: {
      type: Object,
      default: null,
    },
    capabilities: {
      type: Object,
      default: null,
    },
    folders: {
      type: Array,
      default: () => [],
    },
  },
  data: () => ({
    name: '',
    enabled: true,
    matchAll: true,
    conditions: [],
    moveTo: null,
    markRead: false,
    star: false,
    stop: false,
  }),
  computed: {
    /**
     * The condition fields, greyed out where the server cannot test them.
     *
     * @returns {Object[]} the select's items
     */
    fieldItems() {
      return FIELDS.map(field => ({
        value: field,
        text: this.$t(`UserSettings.emailConnector.filters.field.${field}`),
        disabled: !this.supports(field),
      }));
    },
    /**
     * Where a mail can be filed: the user's mirrored folders, then Junk and Trash, each
     * greyed out where the server cannot file.
     *
     * @returns {Object[]} the select's items
     */
    moveItems() {
      const items = this.folders.map(folder => ({ value: folder.key, text: folder.label, disabled: !this.supports('MOVE_TO_FOLDER') }));
      items.push({ value: 'JUNK', text: this.$t('UserSettings.emailConnector.filters.form.moveTo.junk'), disabled: !this.supports('MARK_JUNK') });
      items.push({ value: 'TRASH', text: this.$t('UserSettings.emailConnector.filters.form.moveTo.trash'), disabled: !this.supports('DELETE') });
      return items;
    },
    /**
     * Whether the filter does anything at all.
     *
     * @returns {Boolean} true with a filing action or a flag
     */
    hasAction() {
      return !!this.moveTo || this.markRead || this.star;
    },
    /**
     * The filter as the server group's REST takes it.
     *
     * @returns {Object} {name, enabled, matchAll, conditions, actions, stop}
     */
    value() {
      const actions = [];
      if (this.moveTo === 'JUNK') {
        actions.push({ type: 'MARK_JUNK' });
      } else if (this.moveTo === 'TRASH') {
        actions.push({ type: 'DELETE' });
      } else if (this.moveTo) {
        actions.push({ type: 'MOVE_TO_FOLDER', folderKey: this.moveTo });
      }
      if (this.markRead) {
        actions.push({ type: 'MARK_READ' });
      }
      if (this.star) {
        actions.push({ type: 'STAR' });
      }
      return {
        name: this.name.trim(),
        enabled: this.enabled,
        matchAll: this.matchAll,
        conditions: this.conditions.map(condition => ({
          field: condition.field,
          operator: condition.operator,
          header: condition.field === 'HEADER' ? condition.header.trim() : null,
          value: isFlagField(condition.field) ? null : condition.value.trim(),
        })),
        actions,
        stop: this.stop,
      };
    },
    /**
     * Whether the filter can be saved as it stands.
     *
     * @returns {Boolean} true when every field passes its rule
     */
    valid() {
      return this.nameRule(this.name) === true && this.hasAction
        && this.conditions.every(condition => this.supports(condition.field)
          && (condition.field !== 'HEADER' || !headerError(condition.header))
          && !valueError(condition.field, condition.value));
    },
  },
  watch: {
    value: {
      immediate: true,
      handler() {
        this.$emit('change', { value: this.value, valid: this.valid });
      },
    },
  },
  created() {
    this.fill(this.rule);
  },
  methods: {
    /**
     * Fills the form from a filter the server holds, or with a new one.
     *
     * @param {Object} rule - the filter, or null
     * @returns {void}
     */
    fill(rule) {
      this.name = rule?.name || '';
      this.enabled = rule ? !!rule.enabled : true;
      this.matchAll = rule ? rule.matchAll !== false : true;
      this.stop = !!rule?.stop;
      const conditions = rule?.conditions?.length ? rule.conditions : [{ field: 'FROM', operator: 'CONTAINS' }];
      this.conditions = conditions.map(condition => ({
        key: nextKey++,
        field: condition.field,
        operator: condition.operator,
        header: condition.header || '',
        value: condition.value || '',
      }));
      const actions = rule?.actions || [];
      const filing = actions.find(action => ['MOVE_TO_FOLDER', 'MARK_JUNK', 'DELETE'].includes(action.type));
      if (filing?.type === 'MARK_JUNK') {
        this.moveTo = 'JUNK';
      } else if (filing?.type === 'DELETE') {
        this.moveTo = 'TRASH';
      } else {
        this.moveTo = filing?.folderKey || null;
      }
      this.markRead = actions.some(action => action.type === 'MARK_READ');
      this.star = actions.some(action => action.type === 'STAR');
    },
    /**
     * Whether the server can run an element.
     *
     * @param {String} element - a field or an action type
     * @returns {Boolean} true when supported
     */
    supports(element) {
      return isSupported(this.capabilities, element);
    },
    /**
     * Adds a condition on the sender.
     *
     * @returns {void}
     */
    addCondition() {
      this.conditions.push({ key: nextKey++, field: 'FROM', operator: 'CONTAINS', header: '', value: '' });
    },
    /**
     * The name's rule.
     *
     * @param {String} value - the name
     * @returns {Boolean|String} true, or why not
     */
    nameRule(value) {
      const name = (value || '').trim();
      return (name.length > 0 && name.length <= 100) || this.$t('UserSettings.emailConnector.filters.form.required');
    },
  },
};
</script>
