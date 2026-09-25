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
       the user's own mirrored folders, never typed. -->
  <v-form class="d-flex flex-column" @submit.prevent>
    <v-text-field
      v-model="name"
      :label="$t('UserSettings.emailConnector.filters.form.name')"
      :rules="[nameRule]"
      maxlength="100"
      outlined
      dense />
    <v-switch
      v-model="enabled"
      :label="$t('UserSettings.emailConnector.filters.form.enabled')"
      class="mt-0"
      hide-details />
    <div class="text-subtitle-1 mt-4">{{ $t('UserSettings.emailConnector.filters.form.when') }}</div>
    <v-radio-group
      v-model="matchAll"
      class="mt-1"
      hide-details
      row>
      <v-radio :label="$t('UserSettings.emailConnector.filters.form.matchAll')" :value="true" />
      <v-radio :label="$t('UserSettings.emailConnector.filters.form.matchAny')" :value="false" />
    </v-radio-group>
    <div
      v-for="(condition, index) in conditions"
      :key="condition.key"
      class="mt-3">
      <div class="d-flex align-center">
        <v-select
          v-model="condition.field"
          :items="fieldItems"
          :aria-label="$t('UserSettings.emailConnector.filters.form.field')"
          class="me-2 flex-grow-1 flex-shrink-1"
          style="flex-basis: 0"
          outlined
          dense
          hide-details
          @change="resetOperator(condition)" />
        <v-select
          v-model="condition.operator"
          :items="operatorItems(condition.field)"
          :aria-label="$t('UserSettings.emailConnector.filters.form.operator')"
          class="me-1 flex-grow-1 flex-shrink-1"
          style="flex-basis: 0"
          outlined
          dense
          hide-details />
        <v-btn
          :disabled="conditions.length === 1"
          :title="$t('UserSettings.emailConnector.filters.form.removeCondition')"
          :aria-label="$t('UserSettings.emailConnector.filters.form.removeCondition')"
          icon
          small
          @click="conditions.splice(index, 1)">
          <v-icon size="16" class="icon-default-color">fa-times</v-icon>
        </v-btn>
      </div>
      <v-text-field
        v-if="condition.field === 'HEADER'"
        v-model="condition.header"
        :placeholder="$t('UserSettings.emailConnector.filters.form.header')"
        :rules="[headerRule]"
        class="mt-2"
        maxlength="76"
        outlined
        dense />
      <v-text-field
        v-if="!isFlagField(condition.field)"
        v-model="condition.value"
        :placeholder="valuePlaceholder(condition.field)"
        :suffix="condition.field === 'MESSAGE_SIZE' ? $t('UserSettings.emailConnector.filters.form.kb') : ''"
        :rules="[value => valueRule(condition, value)]"
        :class="condition.field === 'HEADER' ? '' : 'mt-2'"
        maxlength="500"
        outlined
        dense />
    </div>
    <div>
      <v-btn
        :disabled="conditions.length >= 10"
        class="px-0 mt-2"
        color="primary"
        text
        small
        @click="addCondition">
        <v-icon size="14" class="me-1">fa-plus</v-icon>
        {{ $t('UserSettings.emailConnector.filters.form.addCondition') }}
      </v-btn>
    </div>
    <div class="text-subtitle-1 mt-4">{{ $t('UserSettings.emailConnector.filters.form.then') }}</div>
    <v-select
      v-model="moveTo"
      :items="moveItems"
      :label="$t('UserSettings.emailConnector.filters.form.moveTo')"
      class="mt-2"
      clearable
      outlined
      dense
      hide-details />
    <v-checkbox
      v-model="markRead"
      :disabled="!supports('MARK_READ')"
      :label="$t('UserSettings.emailConnector.filters.form.markRead')"
      class="mt-2"
      hide-details />
    <v-checkbox
      v-model="star"
      :disabled="!supports('STAR')"
      :label="$t('UserSettings.emailConnector.filters.form.star')"
      class="mt-1"
      hide-details />
    <v-switch
      v-model="stop"
      :label="$t('UserSettings.emailConnector.filters.form.stop')"
      class="mt-4"
      hide-details />
    <div v-if="!hasAction" class="caption text-sub-title mt-2">
      {{ $t('UserSettings.emailConnector.filters.form.actionRequired') }}
    </div>
    <div class="caption text-sub-title mt-4">
      {{ $t('UserSettings.emailConnector.filters.form.atDelivery') }}
    </div>
  </v-form>
</template>

<script>
import { FIELDS, isFlagField, isSupported, operatorsOf } from '../../../js/EmailConnectorFilters.js';

const SIZE = /^[1-9][0-9]{0,7}$/;
const HEADER_NAME = /^[A-Za-z0-9-]{1,76}$/;

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
          && (condition.field !== 'HEADER' || this.headerRule(condition.header) === true)
          && this.valueRule(condition, condition.value) === true);
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
    isFlagField,
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
     * The operators of a field, as the select's items.
     *
     * @param {String} field - the field
     * @returns {Object[]} the items
     */
    operatorItems(field) {
      return operatorsOf(field).map(operator => ({
        value: operator,
        text: this.$t(`UserSettings.emailConnector.filters.operator.${field === 'MESSAGE_SIZE' ? `size.${operator}` : operator}`),
      }));
    },
    /**
     * Puts a condition on its field's first operator when the field changed under it.
     *
     * @param {Object} condition - the condition
     * @returns {void}
     */
    resetOperator(condition) {
      if (!operatorsOf(condition.field).includes(condition.operator)) {
        condition.operator = operatorsOf(condition.field)[0];
      }
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
     * The hint of a condition's value.
     *
     * @param {String} field - the field
     * @returns {String} the localized hint
     */
    valuePlaceholder(field) {
      return field === 'MESSAGE_SIZE'
        ? this.$t('UserSettings.emailConnector.filters.form.size')
        : this.$t('UserSettings.emailConnector.filters.form.value');
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
    /**
     * A header name's rule.
     *
     * @param {String} value - the header name
     * @returns {Boolean|String} true, or why not
     */
    headerRule(value) {
      return HEADER_NAME.test((value || '').trim()) || this.$t('UserSettings.emailConnector.filters.form.headerInvalid');
    },
    /**
     * A condition value's rule.
     *
     * @param {Object} condition - the condition
     * @param {String} value - the value
     * @returns {Boolean|String} true, or why not
     */
    valueRule(condition, value) {
      if (isFlagField(condition.field)) {
        return true;
      }
      const text = (value || '').trim();
      if (condition.field === 'MESSAGE_SIZE') {
        return SIZE.test(text) || this.$t('UserSettings.emailConnector.filters.form.sizeInvalid');
      }
      return (text.length > 0 && !/[\r\n]/.test(text)) || this.$t('UserSettings.emailConnector.filters.form.required');
    },
  },
};
</script>
