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
  <v-list-item class="full-height align-center">
    <v-list-item-content>
      <v-icon
        size="60"
        class="tertiary--text">
        far fa-envelope
      </v-icon>
      <v-list-item-title class="text-wrap mt-5">
        {{ $t('emailConnector.mailBox.list.drawer.noEmail') }}
      </v-list-item-title>
    </v-list-item-content>
  </v-list-item>
</template>