<!--
Copyright (C) 2025 eXo Platform SAS.

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
  <exo-drawer
    id="userSettingDrawer"
    ref="userSettingDrawer"
    v-model="userSettingDrawer"
    :loading="loading"
    right
    allow-expand
    @closed="close">
    <template #title>
      <span>{{ drawerTitle }}</span>
    </template>
    <template v-if="userSettingDrawer" #content>
      <!-- Switching account used to release the address book and relabel the
           collected contacts without a word: on one real mailbox that silently
           removed 489 people. So when there is something to lose, the binding
           does not move until the user has said what should happen to it.

           The two options are not symmetric and are not presented as if they
           were. Keeping is the safe one and comes first. Starting fresh is
           destructive and irreversible, so it is spelled out, and its button
           downloads the backup FIRST -- the wipe is what happens after the file
           lands, never before. -->
      <div
        v-if="choiceStep"
        class="mx-4 mt-4">
        <div class="mb-4">
          {{ $t('UserSettings.emailConnector.userSetting.switch.intro', [contactsCount]) }}
        </div>
        <v-radio-group v-model="contactsChoice" class="mt-0">
          <v-radio value="keep">
            <template #label>
              <div class="d-flex flex-column">
                <span class="font-weight-bold">{{ $t('UserSettings.emailConnector.userSetting.switch.keep') }}</span>
                <span class="caption text-subtitle-color">{{ $t('UserSettings.emailConnector.userSetting.switch.keep.hint') }}</span>
              </div>
            </template>
          </v-radio>
          <v-radio value="fresh" class="mt-3">
            <template #label>
              <div class="d-flex flex-column">
                <span class="font-weight-bold">{{ $t('UserSettings.emailConnector.userSetting.switch.fresh') }}</span>
                <span class="caption text-subtitle-color">{{ $t('UserSettings.emailConnector.userSetting.switch.fresh.hint') }}</span>
              </div>
            </template>
          </v-radio>
        </v-radio-group>
        <div
          v-if="contactsChoice === 'fresh'"
          class="error--text caption">
          {{ $t('UserSettings.emailConnector.userSetting.switch.fresh.warning') }}
        </div>
      </div>
      <form
        v-else
        ref="userSettingForm"
        class="mx-4 mt-4"
        @submit.stop.prevent="0">
        <v-list-item class="pa-0 mb-5" dense>
          <v-list-item-content class="py-0">
            <v-label for="email">
              {{ $t('UserSettings.emailConnector.userSetting.drawer.emailAddress') }}
            </v-label>
            <v-text-field
              id="email"
              v-model="emailSetting.emailAddress"
              :placeholder="$t('UserSettings.emailConnector.userSetting.drawer.placeHolder.emailAddress')"
              class="pt-3"
              autocomplete="email"
              type="text"
              required="required"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
        <v-list-item class="pa-0" dense>
          <v-list-item-content class="py-0">
            <v-label for="password">
              {{ $t('UserSettings.emailConnector.userSetting.drawer.password') }}
            </v-label>
            <v-text-field
              id="password"
              v-model="emailSetting.emailPassword"
              :placeholder="$t('UserSettings.emailConnector.userSetting.drawer.placeHolder.password')"
              class="pt-3"
              autocomplete="current-password"
              :type="showPassword ? 'text' : 'password'"
              required="required"
              outlined
              dense>
              <template #append>
                <v-btn
                  icon
                  :title="showPassword
                    ? $t('UserSettings.emailConnector.userSetting.drawer.hidePassword')
                    : $t('UserSettings.emailConnector.userSetting.drawer.showPassword')"
                  @click="togglePasswordVisibility">
                  <v-icon size="20">
                    {{ showPassword ? 'fa-eye-slash' : 'fa-eye' }}
                  </v-icon>
                </v-btn>
              </template>
            </v-text-field>
          </v-list-item-content>
        </v-list-item>
      </form>
    </template>
    <template #footer>
      <div class="d-flex">
        <v-spacer />
        <v-btn
          class="btn"
          @click="close">
          {{ $t('UserSettings.emailConnector.userSetting.drawer.cancel') }}
        </v-btn>
        <v-btn
          v-if="choiceStep"
          :disabled="!contactsChoice"
          :loading="loading"
          class="btn btn-primary ms-5"
          @click="applyChoiceThenConnect">
          {{ contactsChoice === 'fresh'
            ? $t('UserSettings.emailConnector.userSetting.switch.fresh.confirm')
            : $t('UserSettings.emailConnector.userSetting.drawer.save') }}
        </v-btn>
        <v-btn
          v-else
          :disabled="disabled"
          @click="connect"
          :loading="loading"
          class="btn btn-primary ms-5">
          {{ $t('UserSettings.emailConnector.userSetting.drawer.save') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  props: {
    userEmailSetting: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    loading: false,
    userSettingDrawer: false,
    drawerTitle: '',
    showPassword: false,
    // The provider-switch question: shown instead of the credentials form once
    // we know the switch would cost the user contacts.
    choiceStep: false,
    contactsChoice: 'keep',
    contactsCount: 0,
    emailSetting: {
      emailConnectorId: '',
      emailAddress: '',
      emailPassword: ''
    }
  }),
  computed: {
    disabled() {
      return !this.emailSetting.emailConnectorId || !this.emailSetting.emailAddress || !this.emailSetting.emailPassword;
    },
    emailAddressModified() {
      return this.userEmailSetting.emailConnectorId !== null && this.userEmailSetting.emailAddress !== this.emailSetting?.emailAddress;
    }
  },
  created() {
    this.$root.$on('open-user-setting-drawer', this.open);
  },
  methods: {
    open(emailConnector) {
      this.emailSetting.emailAddress = this.userEmailSetting.emailAddress;
      this.emailSetting.emailPassword = this.userEmailSetting.emailPassword;
      this.emailSetting.emailConnectorId = emailConnector?.id || this.userEmailSetting.emailConnectorId;
      this.drawerTitle =  this.$t('UserSettings.emailConnector.userSetting.drawer.title', {
        0: emailConnector?.name || this.userEmailSetting.emailConnectorName,
      });
      this.$refs.userSettingDrawer.open();
    },
    close() {
      this.choiceStep = false;
      this.contactsChoice = 'keep';
      this.contactsCount = 0;
      this.emailSetting.emailConnectorId = '';
      this.emailSetting.emailAddress = '';
      this.emailSetting.emailPassword = '';
      this.$refs.userSettingDrawer.close();
    },
    togglePasswordVisibility() {
      this.showPassword = !this.showPassword;
    },
    /**
     * Saves the binding, or first asks what should happen to the contacts.
     * <p>
     * The question is only worth asking when switching to a different mailbox
     * and there is something to lose: re-entering a password for the same
     * address changes nothing about who is in the store.
     *
     * @returns {void}
     */
    connect() {
      if (!this.choiceStep && this.emailAddressModified && this.userEmailSetting?.emailAddress) {
        this.loading = true;
        this.$emailConnectorUserSettingService.getContactsCount()
          .then(count => {
            this.contactsCount = count;
            // Nothing stored means nothing to decide about; the question would
            // be noise on a first connection, which is most of them.
            if (count > 0) {
              this.choiceStep = true;
            } else {
              this.saveBinding();
            }
          })
          .catch(() => this.saveBinding())
          .finally(() => this.loading = false);
        return;
      }
      this.saveBinding();
    },
    /**
     * Carries out the chosen handling, then saves the binding.
     * <p>
     * The ordering is the whole point and is enforced by the promise chain: on
     * "start fresh" the backup download must resolve before the binding moves,
     * so a failed or refused download leaves both the contacts and the current
     * account exactly as they were. The server enforces the same order on its
     * side -- it deletes nothing until the last byte is flushed -- and this is
     * the half of that promise the user can actually see.
     *
     * @returns {void}
     */
    applyChoiceThenConnect() {
      if (this.contactsChoice !== 'fresh') {
        // Keeping is the absence of an action: the contacts stay, and the
        // publishing that may follow is a consequence of the new account
        // having an address book, never a second question.
        this.saveBinding();
        return;
      }
      this.loading = true;
      this.$emailConnectorUserSettingService.downloadContactsBackupThenStartFresh()
        .then(() => this.saveBinding())
        .catch(() => {
          this.loading = false;
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.userSetting.switch.fresh.error'), 'error');
        });
    },
    /**
     * Writes the binding and closes, the original behaviour untouched.
     *
     * @returns {void}
     */
    saveBinding() {
      this.loading = true;
      this.$emailConnectorUserSettingService.setUserEmailSetting(this.emailSetting, this.emailAddressModified).then(() =>
      {
        this.close();
        this.$root.$emit('close-user-setting-connectors-drawer');
        document.dispatchEvent(new CustomEvent('refresh-active-connectors-list'));
        document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
        this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.userSetting.drawer.connect.success'), 'success');
        this.$emailConnectorCommonService.openEmailBox();
      }).catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.userSetting.drawer.connect.error'), 'error')
      ).finally(() => this.loading = false);
    }
  }
};
</script>