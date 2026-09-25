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
  <!-- One eXo rule's form (EXO-90654): a rule eXo runs itself after each sync of the
       user's own inbox. Its conditions may read what only eXo reads (the body, an
       attachment); its actions are the post-actions eXo applies as the user, and the
       ones a module adds through the ('EmailFilter', 'email-filter-action') extension
       point -- the assistant, shipped by the enterprise glue. "Also run at delivery"
       makes it a rule with a server half (kind HOP): the server tags the mail as it
       arrives, eXo acts on it after the next sync; offered only where the server lets
       eXo manage its rules, and then the conditions are the server's.
       Laid out like the platform's drawer forms, as the automatic reply's: a plain
       label above each field, outlined dense fields, a switch at the end of its label's
       row. -->
  <v-form class="d-flex flex-column" @submit.prevent>
    <div class="mb-2">{{ $t('UserSettings.emailConnector.filters.form.name') }}</div>
    <v-text-field
      v-model="name"
      :rules="[nameRule]"
      :aria-label="$t('UserSettings.emailConnector.filters.form.name')"
      maxlength="100"
      outlined
      dense />
    <div class="d-flex align-center justify-space-between full-width mb-2">
      <div id="exoFilterEnabledLabel">
        {{ $t('UserSettings.emailConnector.filters.form.enabled') }}
      </div>
      <v-switch
        v-model="enabled"
        aria-labelledby="exoFilterEnabledLabel"
        :ripple="false"
        class="ma-0 width-fit-content"
        hide-details />
    </div>
    <template v-if="serverSupported">
      <div class="d-flex align-center justify-space-between full-width mt-2 mb-1">
        <div id="exoFilterAtDeliveryLabel">
          {{ $t('UserSettings.emailConnector.filters.exo.form.atDelivery') }}
        </div>
        <v-switch
          v-model="atDelivery"
          aria-labelledby="exoFilterAtDeliveryLabel"
          :ripple="false"
          class="ma-0 width-fit-content"
          hide-details />
      </div>
      <div v-if="atDelivery" class="caption text-sub-title mb-2">
        {{ $t('UserSettings.emailConnector.filters.exo.form.atDelivery.cost') }}
      </div>
    </template>
    <div class="mt-4 mb-2">{{ $t('UserSettings.emailConnector.filters.form.when') }}</div>
    <v-radio-group
      v-model="matchAll"
      class="mt-0"
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
        :aria-label="$t('UserSettings.emailConnector.filters.form.header')"
        :rules="[headerRule]"
        class="mt-2"
        maxlength="76"
        outlined
        dense />
      <v-text-field
        v-if="!isFlagField(condition.field)"
        v-model="condition.value"
        :placeholder="valuePlaceholder(condition.field)"
        :aria-label="$t('UserSettings.emailConnector.filters.form.value')"
        :suffix="condition.field === 'MESSAGE_SIZE' ? $t('UserSettings.emailConnector.filters.form.kb') : ''"
        :rules="[value => valueRule(condition, value)]"
        :class="condition.field === 'HEADER' ? '' : 'mt-2'"
        maxlength="500"
        outlined
        dense />
    </div>
    <div class="d-flex flex-wrap align-center">
      <v-btn
        :disabled="conditions.length >= 10"
        class="px-0 mt-2 me-4"
        color="primary"
        text
        small
        @click="addCondition()">
        <v-icon size="14" class="me-1">fa-plus</v-icon>
        {{ $t('UserSettings.emailConnector.filters.form.addCondition') }}
      </v-btn>
      <v-btn
        v-if="subjectSuggestion"
        class="px-0 mt-2"
        color="primary"
        text
        small
        @click="addSuggestion">
        <v-icon size="14" class="me-1">fa-plus</v-icon>
        {{ $t('UserSettings.emailConnector.filters.exo.form.addSubject', { 0: subjectSuggestion.value }) }}
      </v-btn>
    </div>
    <div class="d-flex align-center mt-2">
      <v-btn
        :disabled="!conditionsValid"
        :loading="previewing"
        class="btn"
        small
        @click="preview">
        {{ $t('UserSettings.emailConnector.filters.exo.form.preview') }}
      </v-btn>
    </div>
    <div
      v-if="previewResult"
      class="caption text-sub-title mt-2"
      role="status">
      <div>{{ previewText }}</div>
      <div v-if="previewResult.notPreviewable && previewResult.notPreviewable.length">
        {{ $t('UserSettings.emailConnector.filters.exo.form.preview.notPreviewable') }}
      </div>
      <ul v-if="previewResult.sample && previewResult.sample.length" class="ps-4 mt-1">
        <li v-for="row in previewResult.sample" :key="row.emailId">
          {{ row.sender }} -- {{ row.subject || $t('UserSettings.emailConnector.filters.exo.noSubject') }}
        </li>
      </ul>
    </div>
    <div
      v-else-if="previewError"
      class="caption error--text mt-2"
      role="alert">
      {{ previewError }}
    </div>
    <div class="mt-4 mb-2">{{ $t('UserSettings.emailConnector.filters.form.then') }}</div>
    <component
      :is="extension.vueComponent"
      v-for="extension in actionExtensions"
      :key="extension.id"
      v-model="extensionActions[extension.type]"
      :capabilities="capabilities"
      class="mb-2" />
    <div class="mb-2">{{ $t('UserSettings.emailConnector.filters.form.moveTo') }}</div>
    <v-select
      v-model="moveTo"
      :items="moveItems"
      :aria-label="$t('UserSettings.emailConnector.filters.form.moveTo')"
      clearable
      outlined
      dense
      hide-details />
    <div class="mt-4 mb-2">{{ $t('UserSettings.emailConnector.filters.exo.form.category') }}</div>
    <v-select
      v-model="categoryId"
      :items="categoryItems"
      :aria-label="$t('UserSettings.emailConnector.filters.exo.form.category')"
      clearable
      outlined
      dense
      hide-details />
    <v-checkbox
      v-model="markRead"
      :label="$t('UserSettings.emailConnector.filters.form.markRead')"
      class="mt-3"
      hide-details />
    <v-checkbox
      v-model="star"
      :label="$t('UserSettings.emailConnector.filters.form.star')"
      class="mt-1"
      hide-details />
    <v-checkbox
      v-model="notify"
      :label="$t('UserSettings.emailConnector.filters.exo.form.notify')"
      class="mt-1"
      hide-details />
    <div class="d-flex align-center justify-space-between full-width mt-4">
      <div id="exoFilterStopLabel">
        {{ $t('UserSettings.emailConnector.filters.form.stop') }}
      </div>
      <v-switch
        v-model="stop"
        aria-labelledby="exoFilterStopLabel"
        :ripple="false"
        class="ma-0 width-fit-content"
        hide-details />
    </div>
    <div v-if="!hasAction" class="caption text-sub-title mt-2">
      {{ $t('UserSettings.emailConnector.filters.form.actionRequired') }}
    </div>
    <div v-if="hasAgent" class="caption text-sub-title mt-2">
      {{ $t('UserSettings.emailConnector.filters.exo.form.afterAgent') }}
    </div>
    <div class="caption text-sub-title mt-4">
      {{ $t('UserSettings.emailConnector.filters.exo.form.afterSync') }}
    </div>
    <div v-if="atDelivery" class="caption text-sub-title mt-2">
      {{ $t('UserSettings.emailConnector.filters.form.publishes') }}
    </div>
  </v-form>
</template>

<script>
import {
  EXO_FIELDS,
  EXO_ONLY_FIELDS,
  FIELDS,
  FILTER_ACTION_EXTENSION,
  filtersMessage,
  isFlagField,
  isSupported,
  operatorsOf,
} from '../../../js/EmailConnectorFilters.js';

const SIZE = /^[1-9][0-9]{0,7}$/;
const HEADER_NAME = /^[A-Za-z0-9-]{1,76}$/;

let nextKey = 1;

export default {
  props: {
    // The rule to edit, or a prefilled new one ({name, matchAll, conditions,
    // subjectSuggestion}); null for a blank new one.
    filter: {
      type: Object,
      default: null,
    },
    // What the mail server can do, as the server group read it; null when unknown.
    capabilities: {
      type: Object,
      default: null,
    },
    // The folders a rule may file into: {key, label}.
    folders: {
      type: Array,
      default: () => [],
    },
  },
  data: () => ({
    name: '',
    enabled: true,
    atDelivery: false,
    matchAll: true,
    conditions: [],
    moveTo: null,
    categoryId: null,
    markRead: false,
    star: false,
    notify: false,
    stop: false,
    categories: [],
    actionExtensions: [],
    // The actions the extensions own, by action type: the action, or null when unused.
    extensionActions: {},
    subjectSuggestion: null,
    previewing: false,
    previewResult: null,
    previewError: null,
  }),
  computed: {
    /**
     * Whether the mail server lets eXo manage its rules, so a rule may run at delivery.
     *
     * @returns {Boolean} true when supported
     */
    serverSupported() {
      return !!this.capabilities?.supported && isSupported(this.capabilities, 'TAG');
    },
    /**
     * The condition fields: eXo's, or, for a rule that also runs at delivery, the
     * server's, greyed where it cannot test them.
     *
     * @returns {Object[]} the select's items
     */
    fieldItems() {
      return (this.atDelivery ? FIELDS : EXO_FIELDS).map(field => ({
        value: field,
        text: this.$t(`UserSettings.emailConnector.filters.field.${field}`),
        disabled: this.atDelivery && !isSupported(this.capabilities, field),
      }));
    },
    /**
     * Where a mail can be filed: the user's mirrored folders, then Junk and Trash.
     *
     * @returns {Object[]} the select's items
     */
    moveItems() {
      const items = this.folders.map(folder => ({ value: folder.key, text: folder.label }));
      items.push({ value: 'JUNK', text: this.$t('UserSettings.emailConnector.filters.form.moveTo.junk') });
      items.push({ value: 'TRASH', text: this.$t('UserSettings.emailConnector.filters.form.moveTo.trash') });
      return items;
    },
    /**
     * The user's categories.
     *
     * @returns {Object[]} the select's items
     */
    categoryItems() {
      return this.categories.map(category => ({ value: category.id, text: category.name }));
    },
    /**
     * The actions the extensions contribute, those in use.
     *
     * @returns {Object[]} the actions
     */
    usedExtensionActions() {
      return Object.values(this.extensionActions).filter(action => action?.type);
    },
    /**
     * Whether the rule runs an assistant.
     *
     * @returns {Boolean} true with an AGENT action
     */
    hasAgent() {
      return this.usedExtensionActions.some(action => action.type === 'AGENT');
    },
    /**
     * Whether the rule does anything at all.
     *
     * @returns {Boolean} true with one action at least
     */
    hasAction() {
      return !!this.moveTo || !!this.categoryId || this.markRead || this.star || this.notify || this.usedExtensionActions.length > 0;
    },
    /**
     * The rule as the eXo group's REST takes it.
     *
     * @returns {Object} the rule
     */
    value() {
      const actions = [...this.usedExtensionActions];
      if (this.moveTo === 'JUNK') {
        actions.push({ type: 'MARK_JUNK' });
      } else if (this.moveTo === 'TRASH') {
        actions.push({ type: 'DELETE' });
      } else if (this.moveTo) {
        actions.push({ type: 'MOVE_TO_FOLDER', folderKey: this.moveTo });
      }
      if (this.categoryId) {
        actions.push({ type: 'ADD_CATEGORY', categoryId: this.categoryId });
      }
      if (this.markRead) {
        actions.push({ type: 'MARK_READ' });
      }
      if (this.star) {
        actions.push({ type: 'STAR' });
      }
      if (this.notify) {
        actions.push({ type: 'NOTIFY' });
      }
      return {
        name: this.name.trim(),
        enabled: this.enabled,
        kind: this.atDelivery ? 'HOP' : 'EXO',
        mailboxScope: 'OWN',
        matchAll: this.matchAll,
        conditions: this.conditionsValue,
        actions,
        stopProcessing: this.stop,
      };
    },
    /**
     * The conditions as the REST takes them.
     *
     * @returns {Object[]} the conditions
     */
    conditionsValue() {
      return this.conditions.map(condition => ({
        field: condition.field,
        operator: condition.operator,
        header: condition.field === 'HEADER' ? condition.header.trim() : null,
        value: isFlagField(condition.field) ? null : condition.value.trim(),
      }));
    },
    /**
     * Whether every condition passes its rule, and fits the rule's kind.
     *
     * @returns {Boolean} true when valid
     */
    conditionsValid() {
      return this.conditions.every(condition => this.fieldAllowed(condition.field)
        && (condition.field !== 'HEADER' || this.headerRule(condition.header) === true)
        && this.valueRule(condition, condition.value) === true);
    },
    /**
     * Whether the rule can be saved as it stands.
     *
     * @returns {Boolean} true when valid
     */
    valid() {
      return this.nameRule(this.name) === true && this.hasAction && this.conditionsValid;
    },
    /**
     * The preview's sentence.
     *
     * @returns {String} the localized sentence
     */
    previewText() {
      const key = this.previewResult?.approximate
        ? 'UserSettings.emailConnector.filters.exo.form.preview.approximate'
        : 'UserSettings.emailConnector.filters.exo.form.preview.result';
      return this.$t(key, { 0: this.previewResult?.total || 0, 1: this.previewResult?.scanned || 0 });
    },
  },
  watch: {
    value: {
      immediate: true,
      handler() {
        this.$emit('change', { value: this.value, valid: this.valid });
      },
    },
    atDelivery() {
      this.previewResult = null;
    },
  },
  created() {
    this.actionExtensions = extensionRegistry.loadExtensions(FILTER_ACTION_EXTENSION.app, FILTER_ACTION_EXTENSION.type) || [];
    this.fill(this.filter);
    this.$emailConnectorCommonService.getAvailableEmailCategories()
      .then(list => this.categories = list || [])
      .catch(() => this.categories = []);
  },
  methods: {
    isFlagField,
    /**
     * Fills the form from a stored rule, a prefilled one, or with a new one.
     *
     * @param {Object} filter - the rule, or null
     * @returns {void}
     */
    fill(filter) {
      this.name = filter?.name || '';
      this.enabled = filter?.id ? !!filter.enabled : true;
      this.atDelivery = filter?.kind === 'HOP';
      this.matchAll = filter ? filter.matchAll !== false : true;
      this.stop = !!filter?.stopProcessing;
      this.subjectSuggestion = filter?.subjectSuggestion || null;
      const conditions = filter?.conditions?.length ? filter.conditions : [{ field: 'FROM', operator: 'CONTAINS' }];
      this.conditions = conditions.map(condition => this.newCondition(condition));
      const actions = filter?.actions || [];
      const filing = actions.find(action => ['MOVE_TO_FOLDER', 'MARK_JUNK', 'DELETE'].includes(action.type));
      if (filing?.type === 'MARK_JUNK') {
        this.moveTo = 'JUNK';
      } else if (filing?.type === 'DELETE') {
        this.moveTo = 'TRASH';
      } else {
        this.moveTo = filing?.folderKey || null;
      }
      this.categoryId = actions.find(action => action.type === 'ADD_CATEGORY')?.categoryId || null;
      this.markRead = actions.some(action => action.type === 'MARK_READ');
      this.star = actions.some(action => action.type === 'STAR');
      this.notify = actions.some(action => action.type === 'NOTIFY');
      const extensionActions = {};
      this.actionExtensions.forEach(extension => {
        extensionActions[extension.type] = actions.find(action => action.type === extension.type) || null;
      });
      this.extensionActions = extensionActions;
    },
    /**
     * A condition row.
     *
     * @param {Object} condition - {field, operator, header, value}
     * @returns {Object} the row
     */
    newCondition(condition) {
      return {
        key: nextKey++,
        field: condition.field,
        operator: condition.operator,
        header: condition.header || '',
        value: condition.value || '',
      };
    },
    /**
     * Whether a field fits the rule: the server must test it for a rule that also runs
     * at delivery, which can use no field only eXo reads; eXo keeps no size.
     *
     * @param {String} field - the field
     * @returns {Boolean} true when allowed
     */
    fieldAllowed(field) {
      if (this.atDelivery) {
        return !EXO_ONLY_FIELDS.includes(field) && isSupported(this.capabilities, field);
      }
      return field !== 'MESSAGE_SIZE';
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
      this.conditions.push(this.newCondition({ field: 'FROM', operator: 'CONTAINS' }));
    },
    /**
     * Adds the subject condition the mail suggested, once.
     *
     * @returns {void}
     */
    addSuggestion() {
      if (this.subjectSuggestion && this.conditions.length < 10) {
        this.conditions.push(this.newCondition(this.subjectSuggestion));
      }
      this.subjectSuggestion = null;
    },
    /**
     * Counts what the rule would match among the mail eXo keeps of the inbox.
     *
     * @returns {Promise<void>} resolved once shown
     */
    preview() {
      this.previewing = true;
      this.previewError = null;
      return this.$emailConnectorUserSettingService.previewFilter({
        kind: this.atDelivery ? 'HOP' : 'EXO',
        matchAll: this.matchAll,
        conditions: this.conditionsValue,
      })
        .then(result => this.previewResult = result)
        .catch(error => {
          this.previewResult = null;
          this.previewError = filtersMessage(this.$t.bind(this), error);
        })
        .finally(() => this.previewing = false);
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
