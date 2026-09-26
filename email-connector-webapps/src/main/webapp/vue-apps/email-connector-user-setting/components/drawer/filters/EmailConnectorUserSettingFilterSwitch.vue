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
  <!-- One on/off choice of a mail filter form, shared by the server and the eXo filter
       forms: its label, then the switch at the end of the label's row, without a ripple
       halo, as the platform's drawer forms lay out a switch (the sidebar link drawer's
       "open in the same tab", the activity stream settings' options). The label names
       the switch for assistive technologies. -->
  <div class="d-flex align-center justify-space-between full-width">
    <div
      :id="labelId"
      :class="disabled && 'text--disabled'"
      class="me-2">
      {{ label }}
    </div>
    <v-switch
      :input-value="value"
      :disabled="disabled"
      :aria-labelledby="labelId"
      :ripple="false"
      class="ma-0 width-fit-content"
      hide-details
      @change="$emit('input', !!$event)" />
  </div>
</template>

<script>
let nextId = 1;

export default {
  props: {
    // Whether the choice is on.
    value: {
      type: Boolean,
      default: false,
    },
    // The localized label.
    label: {
      type: String,
      required: true,
    },
    // Whether the choice cannot be changed, e.g. what the mail server cannot run.
    disabled: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    labelId: `emailFilterSwitch${nextId++}`,
  }),
};
</script>
