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
  <!-- The filters drawer (EXO-90652, EXO-90654): the user's mail filters in one list,
       wherever they run -- on the mail server as mail arrives, or in eXo after its sync --
       and the one form of a filter in place of the list while it is edited. Where a
       filter runs is not the user's choice: the server decides it on save, from what the
       mail server can do. The server's rules are read live on every opening -- eXo keeps
       no copy -- with the state the server is in and its one action; eXo's are read from
       eXo, so a server that cannot be reached never hides them. Opened by the root event
       OPEN_FILTERS_DRAWER_EVENT from the Settings row, and from the mailbox's "Create a
       filter from this mail" with the rule it suggests. -->
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
          <template v-if="!editing">
            <!-- The one-off pass a filter eXo runs is offered once it is created: once,
                 over the mail eXo keeps of the inbox, the assistant only when asked. -->
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
            <v-progress-linear
              v-if="loading && !group"
              indeterminate
              color="primary"
              class="mb-4" />
            <div
              v-else-if="groupError"
              class="error--text mb-4"
              role="alert">
              {{ groupError }}
            </div>
            <div v-else-if="!supported && !exoDisabled" class="text-subtitle mb-4">
              {{ $t('UserSettings.emailConnector.filters.exoOnly') }}
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
              class="text-subtitle mb-4">
              {{ foreignMessage }}
            </div>
            <email-connector-user-setting-filter-list
              v-if="drawer"
              :key="listKey"
              :server-rules="supported && group ? group.rules : null"
              :folders="folders"
              @edit="edit"
              @changed="read"
              @exo-disabled="exoDisabled = true" />
          </template>
          <template v-else>
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
              :filter="editing.item"
              :capabilities="supported ? group.capabilities : null"
              :capabilities-unknown="!!groupError"
              :exo-disabled="exoDisabled"
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
  </div>
</template>

<script>
import { OPEN_FILTERS_DRAWER_EVENT, filtersMessage, notifyFiltersUpdated } from '../../../js/EmailConnectorFilters.js';

/** The refusal of a deployment that switched server rules off: not an error, eXo runs every filter. */
const RULES_DISABLED = 'emailConnector.rules.disabled';

/** The refusal of a server write the user has not consented to yet. */
const CONSENT_REQUIRED = 'emailConnector.rules.consentRequired';

export default {
  data: () => ({
    drawer: false,
    group: null,
    // Why the server group could not be read, or null.
    groupError: null,
    // Whether the deployment switched eXo's filters off: only server filters remain.
    exoDisabled: false,
    loading: false,
    saving: false,
    error: null,
    folders: [],
    // The filter being edited, {item} with item null or a prefill for a new one; null on
    // the list.
    editing: null,
    // Bumped on every edit, so the form fills again.
    formKey: 1,
    // The form's last {value, valid, kind}.
    formValue: null,
    // Bumped to read eXo's filters again.
    listKey: 1,
    // The filter eXo runs just created, while its one-off pass is offered.
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
      return this.editing.item && (this.editing.item.ref || this.editing.item.id)
        ? this.$t('UserSettings.emailConnector.filters.form.editTitle')
        : this.$t('UserSettings.emailConnector.filters.form.newTitle');
    },
    /**
     * Whether the filter just created runs an assistant.
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
     * What the user must know about the server's state.
     *
     * @returns {String} the localized message, or null
     */
    stateMessage() {
      if (!this.supported) {
        return null;
      }
      switch (this.group.state) {
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
      if (!this.supported) {
        return null;
      }
      switch (this.group.state) {
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
      return this.supported && this.group.state === 'OWN' && this.group.foreignScriptName
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
     * Opens the drawer on the list, read again, and the folders a filter may file into.
     * With the rule a mail suggests, opens straight on its form.
     *
     * @param {Object} [options] - {prefill: {name, matchAll, conditions, subjectSuggestion}}
     * @returns {void}
     */
    open(options) {
      this.editing = null;
      this.error = null;
      this.created = null;
      this.exoDisabled = false;
      this.drawer = true;
      this.listKey++;
      const prefill = options?.prefill || null;
      this.read().then(() => {
        if (prefill) {
          this.edit(prefill);
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
     * Reads the server group; a deployment without server rules is no error.
     *
     * @returns {Promise<void>} resolved once read, or once the refusal is shown
     */
    read() {
      this.loading = true;
      return this.$emailConnectorUserSettingService.getServerFilters()
        .then(group => {
          this.group = group;
          this.groupError = null;
        })
        .catch(error => {
          this.group = null;
          this.groupError = error?.message === RULES_DISABLED ? null : filtersMessage(this.$t.bind(this), error);
        })
        .finally(() => this.loading = false);
    },
    /**
     * Shows the form of a filter of the list, or of a new one.
     *
     * @param {Object} item - the filter, a prefill, or null
     * @returns {void}
     */
    edit(item) {
      this.error = null;
      this.formValue = null;
      this.editing = { item };
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
     * Saves the form, after the one-time consent when the filter writes on the mail
     * server and the user never gave it.
     *
     * @returns {void}
     */
    submit() {
      if (!this.formValue?.valid) {
        return;
      }
      if (this.formValue.kind === 'EXO' || this.group?.consented) {
        this.save(false);
      } else {
        this.$refs.consentDialog.open();
      }
    },
    /**
     * Saves the edited filter through the one entry point, which decides where it runs
     * and moves it when that changed. After creating a filter eXo runs, offers to apply
     * it to the mail already in the inbox.
     *
     * @param {Boolean} consent - whether the user just agreed that eXo manages rules on
     *   their mail server
     * @returns {Promise<void>} resolved once saved, or once the refusal is shown
     */
    save(consent) {
      const item = this.editing?.item;
      let origin = null;
      if (item?.kind === 'SERVER' && item.ref) {
        origin = { ref: item.ref };
      } else if (item?.id) {
        origin = { id: item.id };
      }
      this.saving = true;
      this.error = null;
      return this.$emailConnectorUserSettingService.saveRoutedFilter(this.formValue.value, origin, { consent })
        .then(filter => {
          notifyFiltersUpdated();
          const saved = filter?.kind === 'SERVER' ? 'UserSettings.emailConnector.filters.saved' : 'UserSettings.emailConnector.filters.exo.saved';
          this.$root.$emit('alert-message', this.$t(saved), 'success');
          this.editing = null;
          this.listKey++;
          this.read();
          if (!origin && filter?.id && filter.enabled) {
            this.created = filter;
            this.applyWithAgent = false;
          }
        })
        .catch(error => {
          if (!consent && error?.message === CONSENT_REQUIRED) {
            // The form could not tell the filter writes on the mail server (its answer was
            // unknown when the drawer opened): the consent is asked now.
            this.$refs.consentDialog.open();
          } else {
            this.error = filtersMessage(this.$t.bind(this), error);
          }
        })
        .finally(() => this.saving = false);
    },
    /**
     * Applies the filter just created to the mail already in the inbox, once.
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
          this.listKey++;
        })
        .catch(error => this.$root.$emit('alert-message', filtersMessage(this.$t.bind(this), error), 'error'))
        .finally(() => this.applying = false);
    },
    /**
     * Re-activates, or re-publishes, the filters eXo manages on the server.
     *
     * @param {Boolean} republish - overwrite an edit made outside eXo
     * @returns {Promise<void>} resolved once written, or once the refusal is shown
     */
    publish(republish) {
      this.saving = true;
      this.error = null;
      return this.$emailConnectorUserSettingService.publishServerFilters(republish)
        .then(group => {
          this.group = { ...group, capabilities: group?.capabilities || this.group?.capabilities };
          notifyFiltersUpdated();
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.filters.saved'), 'success');
        })
        .catch(error => {
          this.error = filtersMessage(this.$t.bind(this), error);
          this.read();
        })
        .finally(() => this.saving = false);
    },
  },
};
</script>
