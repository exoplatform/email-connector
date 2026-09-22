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
  <div class="mb-4">
    <!--
      Who chooses the connector, before the connectors themselves: this row
      decides whether the users of this instance are attached to one of the
      connectors below automatically, so the list is read differently
      depending on what it says. The switch sits at the right edge, and the
      pencil beside it appears only once there is a choice to revisit.
    -->
    <div class="d-flex align-center">
      <div class="flex-grow-1 text-start">
        <div class="font-weight-bold text-title-color">{{ $t('emailConnector.admin.managed.title') }}</div>
        <div class="text-subtitle">{{ managedSummary }}</div>
      </div>
      <v-btn
        v-if="managedOn"
        :aria-label="$t('emailConnector.admin.managed.title')"
        :title="$t('emailConnector.admin.managed.title')"
        icon
        @click="openManagedDrawer">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
      <v-switch
        v-model="managedOn"
        :aria-label="$t('emailConnector.admin.managed.title')"
        class="ma-0 ms-2 pa-0"
        hide-details
        @change="flipManagedMode" />
    </div>
    <!--
      The drawer is the platform's, shared with the CalDAV connector: this row
      hands it the declared connectors as candidates, the eligibility answers,
      and its own save. What is mail here is the icon a row is drawn with and
      the way out of the empty state, so both come in through slots.
    -->
    <managed-connector-drawer
      ref="managedDrawer"
      :candidates="managedCandidates"
      :connection-requirements="connectionRequirements"
      :save="saveManagedMode"
      @saved="managedApplied"
      @cancelled="managedCancelled">
      <template #icon="{candidate}">
        <email-connector-icon
          :image-url="candidate.imageUrl"
          :icon="candidate.icon"
          icon-size="24" />
      </template>
      <template #empty-action>
        <v-btn
          :aria-label="$t('emailConnector.admin.connectors.add')"
          class="btn btn-primary"
          @click="addConnectorFromManagedDrawer">
          <v-icon size="18">fa-plus</v-icon>
          <span class="text-none ms-2">{{ $t('emailConnector.admin.connectors.add') }}</span>
        </v-btn>
      </template>
    </managed-connector-drawer>
    <!--
      Off is not an ordinary flip either: it is an instance-wide change, so the
      switch asks before it commits - and says what happens today: users choose
      again, the accounts already connected keep syncing. What becomes of the
      users managed mode attached is EXO-89654's, and its wording will change
      when that lands.
    -->
    <confirm-dialog
      ref="managedOffConfirm"
      :title="$t('emailConnector.admin.managed.off.confirm.title')"
      :message="$t('emailConnector.admin.managed.off.confirm.message', {0: managed && managed.connectorName || ''})"
      :ok-label="$t('emailConnector.admin.managed.off.confirm.ok')"
      :cancel-label="$t('emailConnector.admin.managed.off.confirm.cancel')"
      @ok="clearManagedMode"
      @closed="managedOffDeclined" />
  </div>
</template>

<script>
export default {
  props: {
    /** The declared connectors, as the administration app already read them. */
    connectors: {
      type: Array,
      default: () => [],
    },
  },
  data: () => ({
    /**
     * What the instance decided about who chooses the mail connector —
     * {connectorId, connectorName, excludedGroups, managedForMe}. Null until it
     * has been read, so the row can tell "not read yet" from "off".
     */
    managed: null,
    /**
     * The switch's own state, deliberately NOT derived from `managed`. Flipping
     * it is a request, not the commit: it runs ahead of the setting for as long
     * as the drawer or the confirmation is open, and goes back if the
     * administrator declines.
     */
    managedOn: false,
    /**
     * Provider name to whether that provider asks the user for anything. What
     * decides which declared connectors the drawer offers.
     */
    connectionRequirements: {},
    /**
     * Whether the off confirmation was accepted during this opening. The
     * dialog's `closed` fires after OK and after Cancel alike, and only one of
     * them must leave the switch off.
     */
    managedOffConfirmed: false,
  }),
  computed: {
    /**
     * Who chooses the mail connector, in one line. Three answers, not two: an
     * instance with nothing declared yet is neither "users connect their own
     * account" — there is none to connect to — nor managed.
     *
     * @returns {String} the summary line
     */
    managedSummary() {
      if (this.managed && this.managed.connectorId) {
        const excluded = this.managed.excludedGroups && this.managed.excludedGroups.length || 0;
        if (excluded) {
          return this.$t('emailConnector.admin.managed.onExcept', {0: this.managed.connectorName || '', 1: excluded});
        }
        return this.$t('emailConnector.admin.managed.on', {0: this.managed.connectorName || ''});
      }
      if (!this.activeConnectors.length) {
        return this.$t('emailConnector.admin.managed.noConnectors');
      }
      return this.$t('emailConnector.admin.managed.off');
    },
    activeConnectors() {
      return (this.connectors || []).filter(connector => connector.active);
    },
    /**
     * The declared connectors as the shared drawer reads them. The provider name
     * travels under the drawer's own key: it is what the drawer matches against
     * the requirements to keep only the rows that ask their users for nothing.
     *
     * @returns {Array} one candidate per declared connector
     */
    managedCandidates() {
      return (this.connectors || []).map(connector => ({
        id: connector.id,
        name: connector.name,
        subtitle: connector.imapUrl || '',
        active: !!connector.active,
        providerName: connector.authProviderName,
        imageUrl: connector.imageUrl,
        icon: connector.icon,
      }));
    },
  },
  created() {
    this.retrieveManagedMode();
    this.retrieveConnectionRequirements();
  },
  methods: {
    /**
     * Reads whether the instance chooses the connector for its users, and which.
     * A failure leaves the row saying "off" rather than inventing a state.
     *
     * @returns {Promise} resolves once the mode has been read or given up on
     */
    retrieveManagedMode() {
      return this.$emailConnectorAdministrationService.getManagedMode()
        .then(managed => {
          this.managed = managed || null;
          this.managedOn = !!(managed && managed.connectorId);
        })
        .catch(error => console.error('cannot read the mail managed mode', error));
    },
    /**
     * Reads which providers ask their users for anything. A failure leaves the
     * map empty, and an empty map makes every connector ineligible: the drawer
     * then says none can be designated, the conservative reading.
     *
     * @returns {Promise} resolves once the answers have been read or given up on
     */
    retrieveConnectionRequirements() {
      return this.$emailConnectorAdministrationService.getConnectionRequirements()
        .then(requirements => this.connectionRequirements = requirements || {})
        .catch(error => console.error('cannot read the mail connection requirements', error));
    },
    /**
     * Reacts to the switch, which commits nothing by itself. On opens the
     * drawer: there is no honest way to turn managed mode on without naming a
     * connector. Off asks first: an instance-wide change.
     *
     * @param {Boolean} on the position the switch was moved to
     * @returns {void}
     */
    flipManagedMode(on) {
      if (on) {
        this.openManagedDrawer();
        return;
      }
      this.managedOffConfirmed = false;
      this.$refs.managedOffConfirm.open();
    },
    /**
     * Switches managed mode off, once confirmed.
     *
     * @returns {Promise} resolves once the mode is cleared
     */
    clearManagedMode() {
      this.managedOffConfirmed = true;
      return this.$emailConnectorAdministrationService.clearManagedMode()
        .then(managed => {
          this.managed = managed || null;
          this.managedOn = false;
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.managed.cleared'), 'success');
        })
        .catch(error => {
          console.error('cannot switch the mail managed mode off', error);
          this.managedOn = true;
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.managed.clearFailed'), 'error');
        });
    },
    /**
     * Puts the switch back on when the off confirmation closed without OK.
     *
     * @returns {void}
     */
    managedOffDeclined() {
      if (!this.managedOffConfirmed) {
        this.managedCancelled();
      }
    },
    /**
     * Stores the choice the drawer collected, through this add-on's own
     * endpoint — the drawer is shared and knows no URL. The snackbar is this
     * row's too: the drawer does not know what a mail connector is called.
     *
     * @param {Number} connectorId the chosen connector
     * @param {Array<String>} excludedGroups the eXo group ids the choice must not reach
     * @returns {Promise<Object>} the mode now in force
     */
    saveManagedMode(connectorId, excludedGroups) {
      return this.$emailConnectorAdministrationService.saveManagedMode(connectorId, excludedGroups)
        .then(managed => {
          const named = this.$t('emailConnector.admin.managed.saved', {0: managed && managed.connectorName || ''});
          this.$root.$emit('alert-message', named, 'success');
          return managed;
        });
    },
    /**
     * Opens the declaration drawer from the shared drawer's empty state. The
     * shared drawer closes on the way: the two are drawers on the same side.
     *
     * @returns {void}
     */
    addConnectorFromManagedDrawer() {
      this.$refs.managedDrawer.close();
      this.$root.$emit('open-email-connector-drawer');
    },
    /**
     * Opens the drawer on the mode in force, so the choice is made against
     * exactly what is stored.
     *
     * @returns {void}
     */
    openManagedDrawer() {
      const managed = this.managed || {};
      this.$refs.managedDrawer.open({
        connectorId: managed.connectorId,
        excludedGroups: managed.excludedGroups || [],
      });
    },
    /**
     * Records what the drawer stored. This — and only this — is what puts the
     * row into its managed state.
     *
     * @param {Object} managed the mode now in force
     * @returns {void}
     */
    managedApplied(managed) {
      this.managed = managed || null;
      this.managedOn = !!(managed && managed.connectorId);
    },
    /**
     * Puts the switch back where the setting says it is, after a drawer or a
     * confirmation closed without committing.
     *
     * @returns {void}
     */
    managedCancelled() {
      this.managedOn = !!(this.managed && this.managed.connectorId);
    },
  },
};
</script>
