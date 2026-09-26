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
  <!-- One condition of a mail filter, shared by the server and the eXo filter forms: the
       field and the comparison side by side, the row's remove button at its end, then the
       header name and the value the field takes. Laid out like the platform's key/value
       rows (the AI remote agent's request headers): two equal columns and a
       minus icon button; the selects as the platform's drawers use them, dense outlined,
       their menu under the field. The row edits a copy and hands it back through input,
       so the form owns the list. -->
  <div class="mb-2">
    <div class="d-flex align-center">
      <div class="col pa-0">
        <v-select
          ref="fieldSelect"
          :value="value.field"
          :items="fieldItems"
          :menu-props="{ bottom: true, offsetY: true }"
          :aria-label="$t('UserSettings.emailConnector.filters.form.field')"
          class="pa-0"
          dense
          outlined
          hide-details
          @change="changeField"
          @blur="$refs.fieldSelect.blur()" />
      </div>
      <span class="mx-2"></span>
      <div class="col pa-0">
        <v-select
          ref="operatorSelect"
          :value="value.operator"
          :items="operatorItems"
          :menu-props="{ bottom: true, offsetY: true }"
          :aria-label="$t('UserSettings.emailConnector.filters.form.operator')"
          class="pa-0"
          dense
          outlined
          hide-details
          @change="update({ operator: $event })"
          @blur="$refs.operatorSelect.blur()" />
      </div>
      <span class="mx-1"></span>
      <v-btn
        :disabled="!removable"
        :title="$t('UserSettings.emailConnector.filters.form.removeCondition')"
        :aria-label="$t('UserSettings.emailConnector.filters.form.removeCondition')"
        color="error"
        icon
        @click="$emit('remove')">
        <v-icon size="16">fas fa-minus</v-icon>
      </v-btn>
    </div>
    <v-text-field
      v-if="value.field === 'HEADER'"
      :value="value.header"
      :placeholder="$t('UserSettings.emailConnector.filters.form.header')"
      :aria-label="$t('UserSettings.emailConnector.filters.form.header')"
      :rules="[headerRule]"
      class="border-box-sizing width-auto pt-0 mt-2"
      maxlength="76"
      type="text"
      outlined
      dense
      @input="update({ header: $event })" />
    <v-text-field
      v-if="!isFlagField(value.field)"
      :value="value.value"
      :placeholder="valuePlaceholder"
      :aria-label="$t('UserSettings.emailConnector.filters.form.value')"
      :suffix="value.field === 'MESSAGE_SIZE' ? $t('UserSettings.emailConnector.filters.form.kb') : ''"
      :rules="[valueRule]"
      :class="value.field === 'HEADER' ? '' : 'mt-2'"
      class="border-box-sizing width-auto pt-0"
      maxlength="500"
      type="text"
      outlined
      dense
      @input="update({ value: $event })" />
  </div>
</template>

<script>
import { headerError, isFlagField, operatorsOf, valueError } from '../../../js/EmailConnectorFilters.js';

export default {
  props: {
    // The condition: {key, field, operator, header, value}.
    value: {
      type: Object,
      required: true,
    },
    // The fields the form offers, each greyed where it cannot be used.
    fieldItems: {
      type: Array,
      default: () => [],
    },
    // Whether the row may be removed: a filter keeps one condition at least.
    removable: {
      type: Boolean,
      default: true,
    },
  },
  computed: {
    /**
     * The operators of the row's field, as the select's items.
     *
     * @returns {Object[]} the items
     */
    operatorItems() {
      const field = this.value.field;
      return operatorsOf(field).map(operator => ({
        value: operator,
        text: this.$t(`UserSettings.emailConnector.filters.operator.${field === 'MESSAGE_SIZE' ? `size.${operator}` : operator}`),
      }));
    },
    /**
     * The hint of the row's value.
     *
     * @returns {String} the localized hint
     */
    valuePlaceholder() {
      return this.value.field === 'MESSAGE_SIZE'
        ? this.$t('UserSettings.emailConnector.filters.form.size')
        : this.$t('UserSettings.emailConnector.filters.form.value');
    },
  },
  methods: {
    isFlagField,
    /**
     * Hands the form the condition with some of its parts changed.
     *
     * @param {Object} change - the changed parts
     * @returns {void}
     */
    update(change) {
      this.$emit('input', { ...this.value, ...change });
    },
    /**
     * Changes the field, and puts the condition on the new field's first operator when
     * the one it had does not apply to it.
     *
     * @param {String} field - the new field
     * @returns {void}
     */
    changeField(field) {
      const operators = operatorsOf(field);
      this.update({
        field,
        operator: operators.includes(this.value.operator) ? this.value.operator : operators[0],
      });
    },
    /**
     * The header name's rule.
     *
     * @param {String} value - the header name
     * @returns {Boolean|String} true, or why not
     */
    headerRule(value) {
      const error = headerError(value);
      return !error || this.$t(error);
    },
    /**
     * The value's rule.
     *
     * @param {String} value - the value
     * @returns {Boolean|String} true, or why not
     */
    valueRule(value) {
      const error = valueError(this.value.field, value);
      return !error || this.$t(error);
    },
  },
};
</script>
