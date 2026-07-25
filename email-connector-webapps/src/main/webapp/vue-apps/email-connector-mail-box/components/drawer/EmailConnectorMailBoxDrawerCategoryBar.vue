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
  <div
    v-if="categories.length"
    class="d-flex align-center pb-2"
    style="gap: 6px; overflow-x: auto; flex-wrap: nowrap;">
    <v-chip
      v-for="category in assignedCategories"
      :key="category.id"
      small
      close
      color="primary"
      text-color="white"
      @click:close="toggle(category, false)">
      {{ category.name }}
    </v-chip>
    <v-menu
      offset-y
      :close-on-content-click="false">
      <template #activator="{ on, attrs }">
        <v-btn
          x-small
          text
          class="text-none"
          v-bind="attrs"
          v-on="on">
          <v-icon size="13" class="me-1 icon-default-color">fa-tag</v-icon>
          {{ $t('emailConnector.mailBox.list.drawer.category.add') }}
        </v-btn>
      </template>
      <v-list dense class="pa-0">
        <v-list-item
          v-for="category in categories"
          :key="category.id"
          class="clickable"
          @click="toggle(category, !isAssigned(category.id))">
          <v-icon
            size="14"
            class="me-2"
            :class="isAssigned(category.id) ? 'primary--text' : 'icon-default-color'">
            {{ isAssigned(category.id) ? 'fas fa-check-square' : 'far fa-square' }}
          </v-icon>
          <span :class="{ 'primary--text font-weight-bold': isAssigned(category.id) }">
            {{ category.name }}
          </span>
        </v-list-item>
      </v-list>
    </v-menu>
  </div>
</template>

<script>
export default {
  props: {
    // The conversation's messages; the category applies to the whole thread.
    emails: {
      type: Array,
      default: () => [],
    },
  },
  data() {
    return {
      categories: [],
      assignedIds: [],
    };
  },
  computed: {
    mailRemoteIds() {
      return (this.emails || []).map(email => email.mailRemoteId);
    },
    assignedCategories() {
      return this.categories.filter(category => this.assignedIds.includes(category.id));
    },
  },
  watch: {
    emails: {
      immediate: true,
      handler() {
        this.computeAssigned();
      },
    },
  },
  created() {
    this.$emailConnectorMailBoxService.getAvailableEmailCategories()
      .then(list => this.categories = list || []);
  },
  methods: {
    computeAssigned() {
      const ids = new Set();
      (this.emails || []).forEach(email => (email.categoryIds || []).forEach(id => ids.add(id)));
      this.assignedIds = Array.from(ids);
    },
    isAssigned(id) {
      return this.assignedIds.includes(id);
    },
    // Tag/untag the whole conversation, then reflect it locally so chips update at once.
    toggle(category, assign) {
      const service = this.$emailConnectorMailBoxService;
      const request = assign
        ? service.linkEmailsToCategory(this.mailRemoteIds, category.id)
        : service.unlinkEmailsFromCategory(this.mailRemoteIds, category.id);
      request.then(() => {
        if (assign && !this.assignedIds.includes(category.id)) {
          this.assignedIds.push(category.id);
        } else if (!assign) {
          this.assignedIds = this.assignedIds.filter(id => id !== category.id);
        }
        (this.emails || []).forEach(email => {
          const current = email.categoryIds || [];
          this.$set(email, 'categoryIds', assign
            ? Array.from(new Set([...current, category.id]))
            : current.filter(id => id !== category.id));
        });
      }).catch(() => {
        // Leave the current chips as-is if the server call fails.
      });
    },
  },
};
</script>
