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
      :categories="categories"
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
    <div v-if="categorySuggestion" class="mb-2">
      <v-btn
        class="px-0"
        color="primary"
        text
        small
        @click="addCategorySuggestion">
        <v-icon size="12" class="me-2">fas fa-plus</v-icon>
        <span class="text-truncate">{{ $t('UserSettings.emailConnector.filters.exo.form.addCategory', { 0: categorySuggestion.name }) }}</span>
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
    <!-- With an assistant (EXO-90659), each of these four is Off, Always, or left to the
         assistant, in the platform's button-group pattern as this add-on's folder access
         list (EmailConnectorUserSettingFolderAccessList) and social's SelectPeriod use it:
         a dense v-btn-toggle of small text buttons, one always chosen. Always is the plain
         action; "Assistant decides" is an output of the assistant's action. Without an
         assistant they are the plain switches and select they always were. -->
    <template v-if="hasAgent">
      <template v-for="row in decidableRows">
        <div
          :key="`${row.output}-row`"
          class="d-flex align-center justify-space-between full-width flex-wrap mt-4">
          <div :id="`${uid}-${row.output}`" class="me-2">{{ $t(row.labelKey) }}</div>
          <v-btn-toggle
            :value="modeOf(row.output)"
            :aria-labelledby="`${uid}-${row.output}`"
            mandatory
            dense
            @change="setMode(row.output, $event)">
            <v-btn
              v-for="mode in MODES"
              :key="mode"
              :value="mode"
              x-small
              text>
              {{ $t(`UserSettings.emailConnector.filters.exo.form.mode.${mode}`) }}
            </v-btn>
          </v-btn-toggle>
        </div>
        <v-select
          v-if="row.output === 'CATEGORY' && modeOf('CATEGORY') === 'ALWAYS'"
          :key="`${row.output}-select`"
          v-model="categoryId"
          :items="categoryItems"
          :menu-props="{ bottom: true, offsetY: true }"
          :aria-label="$t('UserSettings.emailConnector.filters.exo.form.category')"
          class="pa-0 mt-2"
          dense
          outlined
          hide-details />
      </template>
    </template>
    <template v-else>
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
    </template>
    <div
      v-if="agentOffHint"
      class="text-subtitle mt-2"
      role="status">
      {{ $t('UserSettings.emailConnector.filters.exo.form.agentOffHint') }}
    </div>
    <!-- "Forward to" (EXO-90656): a copy of the mail to one confirmed address in the
         allowed domains, the mail kept; only where the deployment enabled forwarding, and
         only on the mail server. The address is confirmed here, by the code eXo sends to
         it, before the filter can be saved. -->
    <template v-if="forwardOffered">
      <div class="mt-4 mb-2">
        {{ $t('UserSettings.emailConnector.filters.form.forwardTo') }}
      </div>
      <v-text-field
        v-model="forwardInput"
        :rules="[forwardRule]"
        :aria-label="$t('UserSettings.emailConnector.filters.form.forwardTo')"
        :placeholder="$t('UserSettings.emailConnector.forwarding.form.placeholder')"
        class="border-box-sizing width-auto pt-0"
        type="email"
        maxlength="254"
        clearable
        outlined
        dense />
      <template v-if="forwardInput">
        <div class="text-subtitle">
          {{ $t('UserSettings.emailConnector.forwarding.form.allowed', { 0: allowedDomains.join(', ') }) }}
        </div>
        <email-connector-forwarding-confirm
          :destination="forwardDestination"
          :confirmed-destinations="confirmedDestinations"
          @confirmed="forwardConfirmed = $event" />
        <div class="text-subtitle mt-2">
          {{ $t('UserSettings.emailConnector.filters.form.forwardCopy') }}
        </div>
      </template>
    </template>
    <div
      v-if="forwardNotOnServer"
      class="error--text mt-2"
      role="alert">
      {{ $t('UserSettings.emailConnector.filters.form.forwardServerOnly') }}
    </div>
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
      v-if="providedRefused"
      class="error--text mt-4"
      role="alert">
      {{ $t('UserSettings.emailConnector.filters.provided.move') }}
    </div>
    <div
      v-if="!onCategory || kind !== 'EXO'"
      class="text-subtitle mt-4"
      role="status"
      aria-live="polite">
      {{ $t(`UserSettings.emailConnector.filters.form.where.${capabilitiesUnknown ? 'UNKNOWN' : kind}`) }}
    </div>
    <div v-if="!capabilitiesUnknown || onCategory" class="text-subtitle mt-2">
      {{ kind === 'SERVER'
        ? $t('UserSettings.emailConnector.filters.form.atDelivery')
        : $t(onCategory ? 'UserSettings.emailConnector.filters.exo.form.onCategory' : 'UserSettings.emailConnector.filters.exo.form.afterSync') }}
    </div>
    <div v-if="publishes" class="text-subtitle mt-2">
      {{ $t('UserSettings.emailConnector.filters.form.publishes') }}
    </div>
  </v-form>
</template>

<script>
import { isAllowedDestination, normalizeDestination } from '../../../js/EmailConnectorForwarding.js';
import {
  ALL_FIELDS,
  DECIDABLE_OUTPUTS,
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

/** What the user may choose for an action a rule's assistant may also decide. */
const MODES = ['OFF', 'ALWAYS', 'AGENT'];

/** Makes each instance's label ids unique. */
let nextUid = 1;

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
    MODES,
    uid: `emailConnectorFilterForm${nextUid++}`,
    // With an assistant, whether the category is Always, before a category is chosen.
    categoryAlways: false,
    // The actions the assistant decided, while it is on: what turning it off turns off.
    agentDecided: [],
    // Whether the last switch-off of the assistant turned "Assistant decides" choices off.
    agentOffHint: false,
    name: '',
    // Not a field of the form: the list switches a filter on and off. A new filter is
    // created on, an edited one keeps the state it has.
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
    // The categories of the mail the rule is made from, until one is added or dismissed.
    categorySuggestionIds: [],
    previewing: false,
    previewResult: null,
    previewError: null,
    // The address a copy is forwarded to, as typed, and whether it is confirmed.
    forwardInput: '',
    forwardConfirmed: false,
    // The bounds of forwarding: {enabled, allowedDomains, confirmedDestinations}.
    forwardingAuthoring: null,
  }),
  computed: {
    /**
     * Whether the form offers "Forward to": the mail server can forward a copy and the
     * deployment lets the user set one, or the filter already forwards.
     *
     * @returns {Boolean} true when offered
     */
    forwardOffered() {
      return isSupported(this.capabilities, 'FORWARD') || !!this.forwardInput;
    },
    /**
     * @returns {String[]} the domains a copy may be forwarded to
     */
    allowedDomains() {
      return this.forwardingAuthoring?.allowedDomains || [];
    },
    /**
     * @returns {String[]} the addresses the user already confirmed
     */
    confirmedDestinations() {
      return this.forwardingAuthoring?.confirmedDestinations || [];
    },
    /**
     * The address typed, when it is a plain address in an allowed domain.
     *
     * @returns {String} the normalised address, or null
     */
    forwardDestination() {
      const destination = normalizeDestination(this.forwardInput);
      return destination && isAllowedDestination(destination, this.allowedDomains) ? destination : null;
    },
    /**
     * Whether the filter forwards and would not run on the mail server: eXo never
     * forwards a mail itself.
     *
     * @returns {Boolean} true when it cannot be saved for that
     */
    forwardNotOnServer() {
      return !!this.forwardInput && !this.capabilitiesUnknown && this.kind !== 'SERVER';
    },
    /**
     * Every condition field; the size, which only the mail server checks, greyed out when
     * it cannot.
     *
     * @returns {Object[]} the select's items
     */
    fieldItems() {
      const hasHeader = this.conditions.some(condition => condition.field === 'HEADER');
      return ALL_FIELDS.filter(field => !this.exoDisabled || !EXO_ONLY_FIELDS.includes(field)).map(field => ({
        value: field,
        text: this.$t(`UserSettings.emailConnector.filters.field.${field}`),
        // A category and a header never go together: the rule runs when the mail gets its
        // category, long after the sync that alone reads a mail's headers.
        disabled: (field === 'MESSAGE_SIZE' && !isSupported(this.capabilities, field))
          || (field === 'HEADER' && this.onCategory) || (field === 'CATEGORY' && hasHeader),
      }));
    },
    /**
     * Whether the rule has a condition on a category: it runs when a mail gets that
     * category, not after the sync.
     *
     * @returns {Boolean} true with a category condition
     */
    onCategory() {
      return this.conditions.some(condition => condition.field === 'CATEGORY');
    },
    /**
     * The default category of the mail the rule is made from, offered as a condition
     * while the rule has none on a category.
     *
     * @returns {Object|null} {id, name, nameId}
     */
    categorySuggestion() {
      if (this.onCategory || !this.categorySuggestionIds.length || this.conditions.length >= 10) {
        return null;
      }
      return this.categories.find(category => category.nameId && this.categorySuggestionIds.includes(category.id)) || null;
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
     * The assistant's action, when the rule has one.
     *
     * @returns {Object|null} {type: 'AGENT', outputs, ...}
     */
    agentAction() {
      return this.usedExtensionActions.find(action => action.type === 'AGENT') || null;
    },
    /**
     * The four actions an assistant may decide, in the form's order.
     *
     * @returns {Object[]} {output, labelKey}
     */
    decidableRows() {
      return [
        { output: 'CATEGORY', labelKey: 'UserSettings.emailConnector.filters.exo.form.category' },
        { output: 'MARK_READ', labelKey: 'UserSettings.emailConnector.filters.form.markRead' },
        { output: 'STAR', labelKey: 'UserSettings.emailConnector.filters.form.star' },
        { output: 'NOTIFY', labelKey: 'UserSettings.emailConnector.filters.exo.form.notify' },
      ];
    },
    /**
     * Whether the filter does anything at all.
     *
     * @returns {Boolean} true with one action at least
     */
    hasAction() {
      return !!this.moveTo || !!this.categoryId || this.markRead || this.star || this.notify || !!this.forwardDestination
        || this.usedExtensionActions.length > 0;
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
      if (this.forwardDestination) {
        actions.push({ type: 'FORWARD', destination: this.forwardDestination });
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
     * Whether the filter the product provides would move to the mail server, which would
     * delete it from eXo: it cannot be saved so.
     *
     * @returns {Boolean} true when it cannot be saved for that
     */
    providedRefused() {
      return !!this.filter?.provided && this.kind === 'SERVER';
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
      const forwardValid = !this.forwardInput || (!!this.forwardDestination && this.forwardConfirmed && !this.forwardNotOnServer);
      const categoryValid = !this.hasAgent || !this.categoryAlways || !!this.categoryId;
      return this.nameRule(this.name) === true && this.hasAction && this.conditionsValid && !this.exoOnlyRefused && !this.providedRefused && forwardValid
        && categoryValid;
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
    valid() {
      this.$emit('change', { value: this.value, valid: this.valid, kind: this.kind });
    },
    kind() {
      this.previewResult = null;
    },
    forwardOffered() {
      this.readForwardingAuthoring();
    },
    agentAction(action) {
      if (action) {
        this.agentDecided = (action.outputs || []).filter(output => DECIDABLE_OUTPUTS[output]);
        this.agentOffHint = false;
      } else {
        // Switched off: what was left to the assistant is off, and the form says so.
        this.agentOffHint = this.agentDecided.length > 0;
        this.agentDecided = [];
        this.categoryAlways = false;
      }
    },
  },
  created() {
    this.actionExtensions = extensionRegistry.loadExtensions(FILTER_ACTION_EXTENSION.app, FILTER_ACTION_EXTENSION.type) || [];
    this.fill(this.filter);
    this.$emailConnectorCommonService.getAvailableEmailCategories()
      .then(list => this.categories = list || [])
      .catch(() => this.categories = []);
    this.readForwardingAuthoring();
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
      this.categorySuggestionIds = filter?.categorySuggestionIds || [];
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
      this.forwardInput = actions.find(action => action.type === 'FORWARD')?.destination || '';
      const extensionActions = {};
      this.actionExtensions.forEach(extension => {
        extensionActions[extension.type] = actions.find(action => action.type === extension.type) || null;
      });
      this.extensionActions = extensionActions;
      // A rule saved before the four choices existed may hold a plain action and its
      // assistant output together: what the assistant decides wins, so the form shows
      // what runs and "never both on" holds once saved.
      const decided = (extensionActions.AGENT?.outputs || []).filter(output => DECIDABLE_OUTPUTS[output]);
      if (decided.includes('CATEGORY')) {
        this.categoryId = null;
      }
      if (decided.includes('MARK_READ')) {
        this.markRead = false;
      }
      if (decided.includes('STAR')) {
        this.star = false;
      }
      if (decided.includes('NOTIFY')) {
        this.notify = false;
      }
    },
    /**
     * What the rule does about one of the four actions an assistant may decide: Off,
     * Always -- the plain action --, or AGENT, the assistant's output.
     *
     * @param {String} output - CATEGORY, MARK_READ, STAR or NOTIFY
     * @returns {String} OFF, ALWAYS or AGENT
     */
    modeOf(output) {
      if ((this.agentAction?.outputs || []).includes(output)) {
        return 'AGENT';
      }
      const always = {
        CATEGORY: !!this.categoryId || this.categoryAlways,
        MARK_READ: this.markRead,
        STAR: this.star,
        NOTIFY: this.notify,
      };
      return always[output] ? 'ALWAYS' : 'OFF';
    },
    /**
     * Sets what the rule does about one of the four actions: the plain action on for
     * Always, the assistant's output on for AGENT, both off for Off -- never both on.
     *
     * @param {String} output - CATEGORY, MARK_READ, STAR or NOTIFY
     * @param {String} mode - OFF, ALWAYS or AGENT
     * @returns {void}
     */
    setMode(output, mode) {
      const always = mode === 'ALWAYS';
      if (output === 'CATEGORY') {
        this.categoryAlways = always;
        if (!always) {
          this.categoryId = null;
        }
      } else if (output === 'MARK_READ') {
        this.markRead = always;
      } else if (output === 'STAR') {
        this.star = always;
      } else if (output === 'NOTIFY') {
        this.notify = always;
      }
      const action = this.agentAction;
      if (action) {
        const outputs = (action.outputs || []).filter(candidate => candidate !== output);
        if (mode === 'AGENT') {
          outputs.push(output);
        }
        this.$set(this.extensionActions, 'AGENT', { ...action, outputs });
      }
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
     * Adds the category condition the mail suggested, once.
     *
     * @returns {void}
     */
    addCategorySuggestion() {
      if (this.categorySuggestion) {
        this.conditions.push(this.newCondition({ field: 'CATEGORY', operator: 'EQUALS', value: this.categorySuggestion.nameId }));
      }
      this.categorySuggestionIds = [];
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
     * Reads the bounds of forwarding once the form offers "Forward to".
     *
     * @returns {void}
     */
    readForwardingAuthoring() {
      if (!this.forwardOffered || this.forwardingAuthoring) {
        return;
      }
      this.$emailConnectorCommonService.getForwardingAuthoring()
        .then(authoring => this.forwardingAuthoring = authoring)
        .catch(() => this.forwardingAuthoring = null);
    },
    /**
     * The forward's rule: empty, or a plain address in an allowed domain.
     *
     * @param {String} value - the address as typed
     * @returns {Boolean|String} true, or why not
     */
    forwardRule(value) {
      if (!value) {
        return true;
      }
      const destination = normalizeDestination(value);
      if (!destination) {
        return this.$t('UserSettings.emailConnector.forwarding.destination.invalid');
      }
      return isAllowedDestination(destination, this.allowedDomains)
        || this.$t('UserSettings.emailConnector.forwarding.destination.notAllowed');
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
