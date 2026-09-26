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
  <!-- The one form of a mail filter (EXO-90652, EXO-90654): its name, its conditions
       joined by "all" or "any" -- the mail server's and the ones only eXo reads (the body,
       an attachment) -- and every action: where the mail goes, its flags, a category, a
       notification, and the ones a module adds through the ('EmailFilter',
       'email-filter-action') extension point (the assistant, shipped by the enterprise
       glue). The user never says where the filter runs: the server decides when it is
       saved, from what the mail server can do, and the form says it live -- on the mail
       server as mail arrives, after eXo's sync, or marked by the server and acted on by
       eXo. The size is the one condition only the mail server checks.
       Laid out like the platform's drawer forms, as the automatic reply's: a plain label
       above each field, outlined dense fields without a floating label, section titles
       over the conditions and the actions, the condition rows with a minus button each
       and a plus button at the end of their title, each on/off choice a switch at the
       end of its label's row, hints in the platform's subtitle style. -->
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
        @click="addCondition()">
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
    <div v-if="subjectSuggestion" class="mb-2">
      <v-btn
        class="px-0"
        color="primary"
        text
        small
        @click="addSuggestion">
        <v-icon size="12" class="me-2">fas fa-plus</v-icon>
        <span class="text-truncate">{{ $t('UserSettings.emailConnector.filters.exo.form.addSubject', { 0: subjectSuggestion.value }) }}</span>
      </v-btn>
    </div>
    <div v-if="sizeRunsNowhere" class="error--text mb-2">
      {{ $t('UserSettings.emailConnector.filters.form.sizeServerOnly') }}
    </div>
    <div class="d-flex align-center">
      <v-btn
        :disabled="!conditionsValid"
        :loading="previewing"
        class="btn"
        @click="preview">
        <v-icon size="14" class="me-2">fas fa-eye</v-icon>
        {{ $t('UserSettings.emailConnector.filters.exo.form.preview') }}
      </v-btn>
    </div>
    <div
      v-if="previewResult"
      class="text-subtitle mt-2"
      role="status">
      <div>{{ previewText }}</div>
      <div v-if="previewResult.notPreviewable && previewResult.notPreviewable.length">
        {{ $t('UserSettings.emailConnector.filters.exo.form.preview.notPreviewable') }}
      </div>
      <v-list
        v-if="previewResult.sample && previewResult.sample.length"
        class="pa-0 mt-1"
        dense>
        <v-list-item
          v-for="row in previewResult.sample"
          :key="row.emailId"
          class="pa-0"
          dense>
          <v-list-item-content class="pa-0">
            <v-list-item-title class="text-truncate">
              {{ row.subject || $t('UserSettings.emailConnector.filters.exo.noSubject') }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-truncate">{{ row.sender }}</v-list-item-subtitle>
          </v-list-item-content>
        </v-list-item>
      </v-list>
    </div>
    <div
      v-else-if="previewError"
      class="error--text mt-2"
      role="alert">
      {{ previewError }}
    </div>
    <div class="mt-4 mb-2 text-header">{{ $t('UserSettings.emailConnector.filters.form.then') }}</div>
    <component
      :is="extension.vueComponent"
      v-for="extension in exoDisabled ? [] : actionExtensions"
      :key="extension.id"
      v-model="extensionActions[extension.type]"
      :capabilities="capabilities"
      :sample-email-id="filter && filter.sampleEmailId || null"
      class="mb-4" />
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
    <div v-if="!exoDisabled" class="mt-4 mb-2">
      {{ $t('UserSettings.emailConnector.filters.exo.form.category') }}
    </div>
    <v-select
      v-if="!exoDisabled"
      ref="categorySelect"
      v-model="categoryId"
      :items="categoryItems"
      :menu-props="{ bottom: true, offsetY: true }"
      :aria-label="$t('UserSettings.emailConnector.filters.exo.form.category')"
      class="pa-0"
      clearable
      dense
      outlined
      hide-details
      @blur="$refs.categorySelect.blur()" />
    <email-connector-user-setting-filter-switch
      v-model="markRead"
      :label="$t('UserSettings.emailConnector.filters.form.markRead')"
      class="mt-4" />
    <email-connector-user-setting-filter-switch
      v-model="star"
      :label="$t('UserSettings.emailConnector.filters.form.star')"
      class="mt-2" />
    <email-connector-user-setting-filter-switch
      v-if="!exoDisabled"
      v-model="notify"
      :label="$t('UserSettings.emailConnector.filters.exo.form.notify')"
      class="mt-2" />
    <email-connector-user-setting-filter-switch
      v-model="stop"
      :label="$t('UserSettings.emailConnector.filters.form.stop')"
      class="mt-2" />
    <div v-if="!hasAction" class="text-subtitle mt-4">
      {{ $t('UserSettings.emailConnector.filters.form.actionRequired') }}
    </div>
    <div v-if="hasAgent" class="text-subtitle mt-2">
      {{ $t('UserSettings.emailConnector.filters.exo.form.afterAgent') }}
    </div>
    <div
      v-if="exoOnlyRefused"
      class="error--text mt-4"
      role="alert">
      {{ $t('UserSettings.emailConnector.filters.form.serverOnly') }}
    </div>
    <div
      class="text-subtitle mt-4"
      role="status"
      aria-live="polite">
      {{ $t(`UserSettings.emailConnector.filters.form.where.${capabilitiesUnknown ? 'UNKNOWN' : kind}`) }}
    </div>
    <div v-if="!capabilitiesUnknown" class="text-subtitle mt-2">
      {{ kind === 'SERVER'
        ? $t('UserSettings.emailConnector.filters.form.atDelivery')
        : $t('UserSettings.emailConnector.filters.exo.form.afterSync') }}
    </div>
    <div v-if="publishes" class="text-subtitle mt-2">
      {{ $t('UserSettings.emailConnector.filters.form.publishes') }}
    </div>
  </v-form>
</template>

<script>
import {
  ALL_FIELDS,
  EXO_ONLY_FIELDS,
  FILTER_ACTION_EXTENSION,
  filtersMessage,
  headerError,
  isFlagField,
  isSupported,
  routeOf,
  valueError,
} from '../../../js/EmailConnectorFilters.js';

let nextKey = 1;

export default {
  props: {
    // The filter to edit -- an item of the one list, a server one or an eXo one -- or a
    // prefilled new one ({name, matchAll, conditions, subjectSuggestion,
    // sampleEmailId}); null for a blank new one.
    filter: {
      type: Object,
      default: null,
    },
    // What the mail server can do, as the server group read it; null when unknown.
    capabilities: {
      type: Object,
      default: null,
    },
    // Whether what the mail server can do is unknown, its group not read: where the
    // filter runs is decided on save.
    capabilitiesUnknown: {
      type: Boolean,
      default: false,
    },
    // Whether the deployment switched eXo's filters off: only what the mail server runs
    // is offered.
    exoDisabled: {
      type: Boolean,
      default: false,
    },
    // The folders a filter may file into: {key, label}.
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
     * Every condition field; the size, which only the mail server checks, greyed out when
     * it cannot.
     *
     * @returns {Object[]} the select's items
     */
    fieldItems() {
      return ALL_FIELDS.filter(field => !this.exoDisabled || !EXO_ONLY_FIELDS.includes(field)).map(field => ({
        value: field,
        text: this.$t(`UserSettings.emailConnector.filters.field.${field}`),
        disabled: field === 'MESSAGE_SIZE' && !isSupported(this.capabilities, field),
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
     * Whether the filter runs an assistant.
     *
     * @returns {Boolean} true with an AGENT action
     */
    hasAgent() {
      return this.usedExtensionActions.some(action => action.type === 'AGENT');
    },
    /**
     * Whether the filter does anything at all.
     *
     * @returns {Boolean} true with one action at least
     */
    hasAction() {
      return !!this.moveTo || !!this.categoryId || this.markRead || this.star || this.notify || this.usedExtensionActions.length > 0;
    },
    /**
     * The actions, as the REST takes them.
     *
     * @returns {Object[]} the actions
     */
    actions() {
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
      return actions;
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
     * Where the filter will run, as the server decides it on save.
     *
     * @returns {String} SERVER, HOP or EXO
     */
    kind() {
      return routeOf({ conditions: this.conditionsValue, actions: this.actions }, this.capabilities);
    },
    /**
     * Whether saving writes on the mail server: a filter the server runs or marks, or a
     * server filter that leaves it.
     *
     * @returns {Boolean} true when it writes there
     */
    publishes() {
      return this.kind !== 'EXO' || this.filter?.kind === 'SERVER';
    },
    /**
     * Whether the filter would need eXo, which the deployment switched off.
     *
     * @returns {Boolean} true when it cannot be saved for that
     */
    exoOnlyRefused() {
      return this.exoDisabled && this.kind !== 'SERVER';
    },
    /**
     * Whether the size is asked of a filter the mail server will not run: only the server
     * checks the size.
     *
     * @returns {Boolean} true when the size cannot be checked
     */
    sizeRunsNowhere() {
      return this.kind === 'EXO' && this.conditions.some(condition => condition.field === 'MESSAGE_SIZE');
    },
    /**
     * The filter as the one entry point takes it.
     *
     * @returns {Object} the filter
     */
    value() {
      return {
        name: this.name.trim(),
        enabled: this.enabled,
        kind: this.kind,
        mailboxScope: 'OWN',
        matchAll: this.matchAll,
        conditions: this.conditionsValue,
        actions: this.actions,
        stopProcessing: this.stop,
      };
    },
    /**
     * Whether every condition passes its rule, and can be checked where the filter runs.
     *
     * @returns {Boolean} true when valid
     */
    conditionsValid() {
      return !this.sizeRunsNowhere
        && this.conditions.every(condition => (condition.field !== 'HEADER' || !headerError(condition.header))
          && !valueError(condition.field, condition.value));
    },
    /**
     * Whether the filter can be saved as it stands.
     *
     * @returns {Boolean} true when valid
     */
    valid() {
      return this.nameRule(this.name) === true && this.hasAction && this.conditionsValid && !this.exoOnlyRefused;
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
        this.$emit('change', { value: this.value, valid: this.valid, kind: this.kind });
      },
    },
    kind() {
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
    /**
     * Fills the form from an item of the list, a prefilled filter, or with a new one.
     *
     * @param {Object} filter - the filter, or null
     * @returns {void}
     */
    fill(filter) {
      this.name = filter?.name || '';
      this.enabled = filter?.id || filter?.ref ? !!filter.enabled : true;
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
     * Counts what the filter would match among the mail eXo keeps of the inbox.
     *
     * @returns {Promise<void>} resolved once shown
     */
    preview() {
      this.previewing = true;
      this.previewError = null;
      return this.$emailConnectorUserSettingService.previewFilter({
        kind: this.kind,
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
