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
  <!-- The one question about what happens to the contacts, asked identically
       wherever the binding is about to move. Connecting a different mailbox and
       disconnecting the current one lose the same thing, so they must not offer
       two differently worded, differently ordered choices: only the sentences
       that would be factually wrong for the other case are swapped.

       The two options are not symmetric and are not presented as if they were.
       Keeping is the safe one and comes first. Starting fresh is destructive and
       irreversible, so it is spelled out -- and the caller's button downloads the
       backup FIRST, the wipe being what happens after the file lands. -->
  <div class="mx-4 mt-4">
    <div class="mb-4">
      {{ $t(introLabel, [count]) }}
    </div>
    <v-radio-group
      :value="value"
      class="mt-0"
      @change="$emit('input', $event)">
      <v-radio value="keep">
        <template #label>
          <div class="d-flex flex-column">
            <span class="font-weight-bold">{{ $t('UserSettings.emailConnector.userSetting.switch.keep') }}</span>
            <span class="caption text-subtitle-color">{{ $t(keepHintLabel) }}</span>
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
      v-if="value === 'fresh'"
      class="error--text caption">
      {{ $t('UserSettings.emailConnector.userSetting.switch.fresh.warning') }}
    </div>
  </div>
</template>

<script>
export default {
  props: {
    value: {
      type: String,
      default: 'keep',
    },
    count: {
      type: Number,
      default: 0,
    },
    // Disconnecting for good rather than rebinding to another mailbox: the
    // choice is the same, but there is no next account to promise anything to.
    disconnecting: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    introLabel() {
      return this.disconnecting
        ? 'UserSettings.emailConnector.userSetting.switch.intro.disconnect'
        : 'UserSettings.emailConnector.userSetting.switch.intro';
    },
    keepHintLabel() {
      return this.disconnecting
        ? 'UserSettings.emailConnector.userSetting.switch.keep.hint.disconnect'
        : 'UserSettings.emailConnector.userSetting.switch.keep.hint';
    },
  },
};
</script>
