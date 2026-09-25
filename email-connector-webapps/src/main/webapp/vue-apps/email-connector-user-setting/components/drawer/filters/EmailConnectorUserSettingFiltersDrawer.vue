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
  <!-- The filters drawer (EXO-90652): the rules the user's mail server runs at delivery,
       read live on every opening -- eXo keeps no copy -- with the state the server is in
       and its one action, and the form of one filter in place of the list while it is
       edited. Opened by the root event OPEN_FILTERS_DRAWER_EVENT from the Settings row,
       and from the mailbox's "Create a filter from this mail" with the rule it suggests.
       Under the server group, the eXo group (EXO-90654): the rules eXo runs after each
       sync, which work on every server and are shown whatever the server answered. -->
  <div>
    <exo-drawer
      id="userSettingFiltersDrawer"
      ref="filtersDrawer"
      v-model="drawer"
      right>
      <template #title>
        <span>{{ title }}</span>
      </template>
      <template #content>
        <div class="pa-4">
          <v-progress-linear
            v-if="loading && !group"
            indeterminate
            color="primary"
            class="mb-4" />
          <template v-if="!editing">
            <!-- The one-off pass a rule just created is offered: once, over the mail
                 eXo keeps of the inbox, the assistant only when asked. -->
            <v-alert
              v-if="created"
              type="info"
              class="text-body-2 mb-4"
              dense
              text>
              <div>{{ $t('UserSettings.emailConnector.filters.exo.apply.message') }}</div>
              <v-checkbox
                v-if="createdHasAgent"
                v-model="applyWithAgent"
                :label="$t('UserSettings.emailConnector.filters.exo.apply.withAgent')"
                class="mt-2"
                hide-details />
              <div class="d-flex justify-end mt-2">
                <v-btn
                  class="btn me-2"
                  small
                  @click="created = null">
                  {{ $t('UserSettings.emailConnector.filters.exo.apply.later') }}
                </v-btn>
                <v-btn
                  :loading="applying"
                  class="btn btn-primary"
                  small
                  @click="applyCreated">
                  {{ $t('UserSettings.emailConnector.filters.exo.apply.ok') }}
                </v-btn>
              </div>
            </v-alert>
            <div
              v-if="!group && !loading"
              class="error--text mb-4"
              role="alert">
              {{ error || $t('UserSettings.emailConnector.filters.error') }}
            </div>
            <div v-else-if="group && !supported" class="text-subtitle mb-4">
              {{ $t('UserSettings.emailConnector.filters.unsupported') }}
            </div>
          </template>
          <template v-if="!editing && group && supported">
            <div class="text-subtitle-1 text-color">
              {{ $t('UserSettings.emailConnector.filters.server.title') }}
            </div>
            <div class="caption text-sub-title mb-4">
              {{ $t('UserSettings.emailConnector.filters.server.description') }}
            </div>
            <v-alert
              v-if="stateMessage"
              type="warning"
              class="text-body-2 mb-4"
              dense
              text>
              <div>{{ stateMessage }}</div>
              <div v-if="stateAction" class="d-flex justify-end mt-2">
                <v-btn
                  :loading="saving"
                  class="btn"
                  small
                  @click="stateAction.run">
                  {{ stateAction.label }}
                </v-btn>
              </div>
            </v-alert>
            <v-alert
              v-if="error"
              type="error"
              class="text-body-2 mb-4"
              dense
              text>
              {{ error }}
            </v-alert>
            <div
              v-if="foreignMessage"
              class="caption text-sub-title mb-4">
              {{ foreignMessage }}
            </div>
            <!-- Each filter as the platform's settings lists show a row with actions (the
                 activity stream settings' categories): its name over what it does, then
                 its switch and its icon buttons, the delete one in the error color. -->
            <v-list
              v-if="rules.length"
              class="pa-0"
              dense>
              <v-list-item
                v-for="rule in rules"
                :key="rule.ref"
                class="pa-0"
                dense>
                <v-list-item-content class="me-2 pa-0">
                  <v-list-item-title class="text-truncate">{{ rule.name }}</v-list-item-title>
                  <v-list-item-subtitle class="text-wrap">{{ ruleSummary(rule) }}</v-list-item-subtitle>
                </v-list-item-content>
                <v-list-item-action class="mx-0 my-auto">
                  <v-switch
                    :input-value="rule.enabled"
                    :disabled="saving"
                    :aria-label="$t('UserSettings.emailConnector.filters.form.enabled')"
                    :ripple="false"
                    class="ma-0 width-fit-content"
                    hide-details
                    @change="toggle(rule, $event)" />
                </v-list-item-action>
                <v-list-item-action class="mx-0 my-auto">
                  <v-btn
                    :title="$t('UserSettings.emailConnector.filters.edit')"
                    :aria-label="$t('UserSettings.emailConnector.filters.edit')"
                    icon
                    @click="edit(rule)">
                    <v-icon size="18">fas fa-edit</v-icon>
                  </v-btn>
                </v-list-item-action>
                <v-list-item-action class="mx-0 my-auto">
                  <v-btn
                    :title="$t('UserSettings.emailConnector.filters.delete')"
                    :aria-label="$t('UserSettings.emailConnector.filters.delete')"
                    icon
                    @click="askDelete(rule)">
                    <v-icon size="18" color="error">fas fa-trash</v-icon>
                  </v-btn>
                </v-list-item-action>
              </v-list-item>
            </v-list>
            <div v-else class="text-sub-title mb-2">
              {{ $t('UserSettings.emailConnector.filters.empty') }}
            </div>
            <v-btn
              :disabled="saving"
              class="btn mt-2"
              @click="edit(null)">
              <v-icon size="14" class="me-2">fa-plus</v-icon>
              {{ $t('UserSettings.emailConnector.filters.new') }}
            </v-btn>
          </template>
          <email-connector-user-setting-exo-filters
            v-if="drawer && !editing"
            ref="exoFilters"
            :key="exoListKey"
            :server-rules="group && supported ? group.rules : null"
            :folders="folders"
            class="mt-6"
            @edit="editExo" />
          <template v-if="editing && editing.exo">
            <v-alert
              v-if="error"
              type="error"
              class="text-body-2 mb-4"
              dense
              text>
              {{ error }}
            </v-alert>
            <email-connector-user-setting-exo-filter-form
              :key="formKey"
              :filter="editing.rule"
              :capabilities="group && group.capabilities"
              :folders="folders"
              @change="formValue = $event" />
          </template>
          <template v-else-if="editing">
            <v-alert
              v-if="error"
              type="error"
              class="text-body-2 mb-4"
              dense
              text>
              {{ error }}
            </v-alert>
            <email-connector-user-setting-filter-form
              :key="formKey"
              :rule="editing.rule"
              :capabilities="group.capabilities"
              :folders="folders"
              @change="formValue = $event" />
          </template>
        </div>
      </template>
      <template #footer>
        <div class="d-flex align-center justify-end">
          <template v-if="editing">
            <v-btn class="btn" @click="backToList">
              {{ $t('UserSettings.emailConnector.userSetting.drawer.cancel') }}
            </v-btn>
            <v-btn
              :disabled="!formValue || !formValue.valid"
              :loading="saving"
              class="btn btn-primary ms-5"
              @click="submit">
              {{ $t('UserSettings.emailConnector.filters.form.save') }}
            </v-btn>
          </template>
          <v-btn
            v-else
            class="btn"
            @click="drawer = false">
            {{ $t('UserSettings.emailConnector.filters.close') }}
          </v-btn>
        </div>
      </template>
    </exo-drawer>
    <exo-confirm-dialog
      ref="consentDialog"
      :title="$t('UserSettings.emailConnector.filters.consent.title')"
      :message="$t('UserSettings.emailConnector.filters.consent.message')"
      :ok-label="$t('UserSettings.emailConnector.filters.consent.ok')"
      :cancel-label="$t('UserSettings.emailConnector.userSetting.drawer.cancel')"
      @ok="save(true)" />
    <exo-confirm-dialog
      ref="republishDialog"
      :title="$t('UserSettings.emailConnector.filters.republish')"
      :message="$t('UserSettings.emailConnector.filters.republish.message')"
      :ok-label="$t('UserSettings.emailConnector.filters.republish')"
      :cancel-label="$t('UserSettings.emailConnector.userSetting.drawer.cancel')"
      @ok="publish(true)" />
    <exo-confirm-dialog
      ref="deleteDialog"
      :title="$t('UserSettings.emailConnector.filters.delete.title')"
      :message="$t('UserSettings.emailConnector.filters.delete.message', { 0: deleting ? deleting.name : '' })"
      :ok-label="$t('UserSettings.emailConnector.filters.delete')"
      :cancel-label="$t('UserSettings.emailConnector.userSetting.drawer.cancel')"
      @ok="doDelete" />
  </div>
</template>

<script>
import { OPEN_FILTERS_DRAWER_EVENT, filtersMessage, notifyFiltersUpdated } from '../../../js/EmailConnectorFilters.js';

export default {
  data: () => ({
    drawer: false,
    group: null,
    loading: false,
    saving: false,
    error: null,
    folders: [],
    // The filter being edited, {rule} with rule null for a new one; null on the list.
    editing: null,
    // Bumped on every edit, so the form fills again.
    formKey: 1,
    // The form's last {value, valid}.
    formValue: null,
    // The filter a deletion is being confirmed for.
    deleting: null,
    // Bumped to read the eXo group again.
    exoListKey: 1,
    // The eXo rule just created, while its one-off pass is offered.
    created: null,
    // Whether the one-off pass also queues the assistant.
    applyWithAgent: false,
    applying: false,
  }),
  computed: {
    /**
     * The drawer's title: the list's, or the form's.
     *
     * @returns {String} the localized title
     */
    title() {
      if (!this.editing) {
        return this.$t('UserSettings.emailConnector.filters.title');
      }
      return this.editing.rule && (this.editing.rule.ref || this.editing.rule.id)
        ? this.$t('UserSettings.emailConnector.filters.form.editTitle')
        : this.$t('UserSettings.emailConnector.filters.form.newTitle');
    },
    /**
     * Whether the rule just created runs an assistant.
     *
     * @returns {Boolean} true with an AGENT action
     */
    createdHasAgent() {
      return (this.created?.actions || []).some(action => action.type === 'AGENT');
    },
    /**
     * Whether the mail server lets eXo manage its rules.
     *
     * @returns {Boolean} true when the engine can publish
     */
    supported() {
      return !!this.group?.capabilities?.supported;
    },
    /**
     * The filters on the server, in the order it applies them.
     *
     * @returns {Object[]} the filters
     */
    rules() {
      // A hop -- a rule whose only action is eXo's keyword -- is the server half of an
      // eXo rule, written by reconciliation: it is not the user's to edit here.
      return (this.group?.rules || []).filter(rule => !(rule.actions || []).every(action => action.type === 'TAG'));
    },
    /**
     * What the user must know about the server's state.
     *
     * @returns {String} the localized message, or null
     */
    stateMessage() {
      switch (this.group?.state) {
      case 'INACTIVE':
        return this.group.foreignScriptName
          ? this.$t('UserSettings.emailConnector.filters.state.inactive.named', { 0: this.group.foreignScriptName })
          : this.$t('UserSettings.emailConnector.filters.state.inactive');
      case 'MODIFIED':
        return this.group.foreignScriptName
          ? this.$t('UserSettings.emailConnector.filters.state.wrapperModified', { 0: this.group.foreignScriptName })
          : this.$t('UserSettings.emailConnector.filters.state.modified');
      case 'UNREADABLE':
        return this.$t('UserSettings.emailConnector.filters.state.unreadable');
      default:
        return null;
      }
    },
    /**
     * The one action the state offers.
     *
     * @returns {Object} {label, run}, or null
     */
    stateAction() {
      switch (this.group?.state) {
      case 'INACTIVE':
        return { label: this.$t('UserSettings.emailConnector.filters.reactivate'), run: () => this.publish(false) };
      case 'MODIFIED':
        // A wrapper another client changed cannot be rewritten: the script it included
        // is no longer known. The user repairs it in that client.
        return this.group.foreignScriptName
          ? null
          : { label: this.$t('UserSettings.emailConnector.filters.republish'), run: () => this.$refs.republishDialog.open() };
      default:
        return null;
      }
    },
    /**
     * The line about another client's script the server also runs, first.
     *
     * @returns {String} the localized line, or null
     */
    foreignMessage() {
      return this.group?.state === 'OWN' && this.group.foreignScriptName
        ? this.$t('UserSettings.emailConnector.filters.foreign', { 0: this.group.foreignScriptName })
        : null;
    },
  },
  created() {
    this.$root.$on(OPEN_FILTERS_DRAWER_EVENT, this.open);
  },
  beforeDestroy() {
    this.$root.$off(OPEN_FILTERS_DRAWER_EVENT, this.open);
  },
  methods: {
    /**
     * Opens the drawer on the list, read again from the server, and the folders a filter
     * may file into. With the rule a mail suggests, opens straight on its form: a server
     * filter where the server lets eXo manage its rules, an eXo rule otherwise.
     *
     * @param {Object} [options] - {prefill: {name, matchAll, conditions, subjectSuggestion}}
     * @returns {void}
     */
    open(options) {
      this.editing = null;
      this.error = null;
      this.drawer = true;
      this.exoListKey++;
      const prefill = options?.prefill || null;
      this.read().then(() => {
        if (prefill) {
          if (this.supported) {
            this.edit({ ...prefill, enabled: true, actions: [] });
          } else {
            this.editExo(prefill);
          }
        }
      });
      this.$emailConnectorUserSettingService.getMailFolders(false)
        .then(list => {
          this.folders = (list?.folders || [])
            .filter(folder => folder.key === 'ARCHIVE' || (folder.type === 'CUSTOM' && folder.syncEnabled && !folder.missing))
            .map(folder => ({
              key: folder.key,
              label: folder.key === 'ARCHIVE' ? this.$t('UserSettings.emailConnector.filters.form.moveTo.archive') : folder.displayName || folder.path,
            }));
        })
        .catch(() => this.folders = []);
    },
    /**
     * Reads the server group.
     *
     * @returns {Promise<void>} resolved once read, or once the refusal is shown
     */
    read() {
      this.loading = true;
      return this.$emailConnectorUserSettingService.getServerFilters()
        .then(group => this.group = group)
        .catch(error => {
          this.group = null;
          this.error = filtersMessage(this.$t.bind(this), error);
        })
        .finally(() => this.loading = false);
    },
    /**
     * Shows the form of a filter, or of a new one.
     *
     * @param {Object} rule - the filter, or null
     * @returns {void}
     */
    edit(rule) {
      this.error = null;
      this.formValue = null;
      this.editing = { rule, exo: false };
      this.formKey++;
    },
    /**
     * Shows the form of an eXo rule, or of a new one.
     *
     * @param {Object} filter - the rule, or null
     * @returns {void}
     */
    editExo(filter) {
      this.error = null;
      this.formValue = null;
      this.editing = { rule: filter, exo: true };
      this.formKey++;
    },
    /**
     * Leaves the form for the list, nothing saved.
     *
     * @returns {void}
     */
    backToList() {
      this.editing = null;
      this.error = null;
    },
    /**
     * Saves the form, after the one-time consent when the user never gave it.
     *
     * @returns {void}
     */
    submit() {
      if (!this.formValue?.valid) {
        return;
      }
      // An eXo rule writes nothing on the server unless it also runs at delivery.
      const publishes = !this.editing?.exo || this.formValue.value.kind === 'HOP';
      if (!publishes || this.group?.consented) {
        this.save(false);
      } else {
        this.$refs.consentDialog.open();
      }
    },
    /**
     * Saves the edited filter on the server.
     *
     * @param {Boolean} consent - whether the user just agreed that eXo manages rules on
     *   their mail server
     * @returns {Promise<void>} resolved once saved, or once the refusal is shown
     */
    save(consent) {
      if (this.editing?.exo) {
        return this.saveExo(consent);
      }
      const ref = this.editing?.rule?.ref;
      return this.write(() => this.$emailConnectorUserSettingService.saveServerFilter(this.formValue.value, ref, { consent }))
        .then(ok => {
          if (ok) {
            this.editing = null;
          }
        });
    },
    /**
     * Saves the edited eXo rule: a rule that also runs at delivery writes the server
     * first, and nothing is stored when it refuses. After a creation, offers to apply
     * the rule to the mail already in the inbox.
     *
     * @param {Boolean} consent - whether the user just agreed that eXo manages rules on
     *   their mail server
     * @returns {Promise<void>} resolved once saved, or once the refusal is shown
     */
    saveExo(consent) {
      const id = this.editing?.rule?.id;
      const value = this.formValue.value;
      this.saving = true;
      this.error = null;
      return this.$emailConnectorUserSettingService.saveExoFilter(value, id, { consent })
        .then(filter => {
          notifyFiltersUpdated();
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.filters.exo.saved'), 'success');
          this.editing = null;
          this.exoListKey++;
          if (value.kind === 'HOP') {
            this.read();
          }
          if (!id && filter?.id && filter.enabled) {
            this.created = filter;
            this.applyWithAgent = false;
          }
        })
        .catch(error => this.error = filtersMessage(this.$t.bind(this), error))
        .finally(() => this.saving = false);
    },
    /**
     * Applies the rule just created to the mail already in the inbox, once.
     *
     * @returns {Promise<void>} resolved once applied, or once the refusal is shown
     */
    applyCreated() {
      const filter = this.created;
      if (!filter) {
        return Promise.resolve();
      }
      this.applying = true;
      return this.$emailConnectorUserSettingService.applyExoFilter(filter.id, this.createdHasAgent && this.applyWithAgent)
        .then(report => {
          this.created = null;
          this.$root.$emit('alert-message',
            this.$t('UserSettings.emailConnector.filters.exo.apply.done', { 0: report?.matched || 0, 1: report?.queued || 0 }),
            'success');
          this.exoListKey++;
        })
        .catch(error => this.$root.$emit('alert-message', filtersMessage(this.$t.bind(this), error), 'error'))
        .finally(() => this.applying = false);
    },
    /**
     * Switches a filter on or off on the server.
     *
     * @param {Object} rule - the filter
     * @param {Boolean} enabled - its new state
     * @returns {Promise<void>} resolved once saved, or once the refusal is shown
     */
    toggle(rule, enabled) {
      return this.write(() => this.$emailConnectorUserSettingService.saveServerFilter({ ...rule, enabled }, rule.ref, {}));
    },
    /**
     * Asks to confirm a deletion.
     *
     * @param {Object} rule - the filter
     * @returns {void}
     */
    askDelete(rule) {
      this.deleting = rule;
      this.$refs.deleteDialog.open();
    },
    /**
     * Deletes the confirmed filter on the server.
     *
     * @returns {Promise<void>} resolved once deleted, or once the refusal is shown
     */
    doDelete() {
      const rule = this.deleting;
      this.deleting = null;
      return rule ? this.write(() => this.$emailConnectorUserSettingService.deleteServerFilter(rule.ref)) : Promise.resolve();
    },
    /**
     * Re-activates, or re-publishes, the filters eXo manages.
     *
     * @param {Boolean} republish - overwrite an edit made outside eXo
     * @returns {Promise<void>} resolved once written, or once the refusal is shown
     */
    publish(republish) {
      return this.write(() => this.$emailConnectorUserSettingService.publishServerFilters(republish));
    },
    /**
     * Runs a write: the group it answers replaces the list, the capabilities read at
     * opening kept; a refusal is said in the user's words and the list read again.
     *
     * @param {Function} request - the write
     * @returns {Promise<Boolean>} true when written
     */
    write(request) {
      this.saving = true;
      this.error = null;
      return request()
        .then(group => {
          this.group = { ...group, capabilities: group?.capabilities || this.group?.capabilities };
          notifyFiltersUpdated();
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.filters.saved'), 'success');
          return true;
        })
        .catch(error => {
          this.error = filtersMessage(this.$t.bind(this), error);
          if (!this.editing) {
            this.read();
          }
          return false;
        })
        .finally(() => this.saving = false);
    },
    /**
     * A filter in one line: what it does.
     *
     * @param {Object} rule - the filter
     * @returns {String} the localized line
     */
    ruleSummary(rule) {
      const parts = (rule.actions || []).map(action => {
        if (action.type === 'MOVE_TO_FOLDER') {
          const folder = this.folders.find(candidate => candidate.key === action.folderKey);
          return this.$t('UserSettings.emailConnector.filters.summary.move', { 0: folder?.label || action.folderPath });
        }
        return this.$t(`UserSettings.emailConnector.filters.summary.${action.type}`);
      });
      if (rule.stop) {
        parts.push(this.$t('UserSettings.emailConnector.filters.summary.stop'));
      }
      return parts.join(', ');
    },
  },
};
</script>
