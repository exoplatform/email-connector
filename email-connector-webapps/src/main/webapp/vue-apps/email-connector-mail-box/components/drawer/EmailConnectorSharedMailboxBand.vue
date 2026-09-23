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
  <!-- The identity cue (delegation plan 7.6): a persistent band, in the theme's warning
       tint, saying whose mailbox the user is in and with which rights -- the classic
       delegation footgun is acting in the wrong mailbox, so this is not dismissible and
       not a setting. Above a list it is pinned (`sticky`): with the header's owner name
       it is the whole cue -- the rows carry none -- so it must not scroll away. The
       composer's band is the same component.
       No fallback content inside <slot>: this webapp's templates are compiled by Vue
       2.7, which hands a slot's fallback to the runtime as a FUNCTION, and the
       platform runs Vue 2.6.11, which renders such a fallback as nothing -- the band
       then showed its icon and no text. Hence the explicit v-if on $slots.default.
       Pinned through a wrapper, not the alert itself: Vuetify's .v-alert--text forces
       a transparent background with !important, so only a wrapper can put an opaque
       surface (the platform's `white` helper) under the translucent tint and keep the
       rows scrolling beneath from showing through. -->
  <div :class="{ white: sticky }" :style="sticky ? STICKY_STYLE : null">
    <v-alert
      :icon="false"
      class="mb-0 text-body-2 border-box-sizing"
      color="warning"
      role="status"
      dense
      text
      tile>
      <!-- The row fills the alert's content without growing it (width 0, min-width
           100%: Vuetify's alert content is a flex item that would otherwise widen to the
           whole sentence), reads from the start whatever the platform's inherited
           centring, and puts the icon on the FIRST line of the text: the icon sits in a
           box one text line high, aligned to the top of the text. -->
      <div class="d-flex align-start justify-start text-start" style="width: 0; min-width: 100%;">
        <!-- The access level's own icon, and in its tooltip what that access lets the
             user do here -- the read-state consequence included, said once, there,
             rather than repeated in the line (the developer's decision; plan 7.5 has the
             cue say it, a divergence to record). Focusable, so the tooltip opens from the
             keyboard too. -->
        <span class="d-flex align-center flex-shrink-0 me-3" :style="LINE_BOX">
          <v-tooltip bottom>
            <template #activator="{ on, attrs }">
              <v-icon
                :aria-label="levelLabel"
                :title="levelLabel"
                v-bind="attrs"
                tabindex="0"
                role="img"
                size="16"
                color="warning"
                v-on="on">
                {{ levelIcon }}
              </v-icon>
            </template>
            <span>{{ levelLabel }} · {{ rightsSummary }}</span>
          </v-tooltip>
        </span>
        <slot v-if="$slots.default"></slot>
        <!-- One line, the owner's name ellipsized rather than wrapped. -->
        <span
          v-else
          class="text--primary text-start"
          style="flex: 1 1 0; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis;">
          <template v-for="(part, index) in messageParts">
            <b v-if="part.bold" :key="index">{{ part.text }}</b>
            <template v-else>{{ part.text }}</template>
          </template>
        </span>
      </div>
    </v-alert>
  </div>
</template>

<script>
import { sharedMailboxCapabilities } from '../../js/EmailConnectorSharedMailboxRules.js';

// Each access level's icon: an eye for read-only, a pen for a share that can change
// the mailbox, a key for letters no preset names.
const LEVEL_ICONS = {
  READER: 'fa-eye',
  EDITOR: 'fa-pen',
  CUSTOM: 'fa-key',
};

// Two markers no translation contains, standing for the bold parts of the sentence.
const OWNER_MARK = '\u0001';
const LEVEL_MARK = '\u0002';

export default {
  props: {
    // The switcher entry of the mailbox the user is in.
    entry: { type: Object, default: null },
    // An icon of the caller's own (the composer's reply or new-mail icon); the access
    // level's icon when not given.
    icon: { type: String, default: null },
    // Pinned to the top of the scrolling list it sits in.
    sticky: { type: Boolean, default: false },
  },
  data: () => ({
    // Pinned to the top of the scrolling list; the opaque surface is the `white` class.
    STICKY_STYLE: { position: 'sticky', top: 0, zIndex: 3 },
    // One line of the band's text (text-body-2's line height): the box a leading icon
    // is centred in, so it sits on the first line whatever the text does below it.
    LINE_BOX: { height: '1.25rem' },
  }),
  computed: {
    /**
     * The access level: the preset, or CUSTOM for letters no preset expresses.
     *
     * @returns {String} READER, EDITOR or CUSTOM
     */
    level() {
      return this.entry?.preset || 'CUSTOM';
    },
    /**
     * The icon shown: the caller's, or the access level's.
     *
     * @returns {String} the icon class
     */
    levelIcon() {
      return this.icon || LEVEL_ICONS[this.level] || LEVEL_ICONS.CUSTOM;
    },
    /**
     * The access level in words, the switcher's words.
     *
     * @returns {String} Reader, Editor or Custom rights
     */
    levelLabel() {
      return this.level === 'CUSTOM'
        ? this.$t('emailConnector.mailBox.sharedMailbox.preset.CUSTOM')
        : this.$t(`UserSettings.emailConnector.sharing.preset.${this.level}`);
    },
    /**
     * What the access lets the user do here, from the rule the mailbox's controls
     * follow -- the settings' own sentence, so the two never say different things.
     * Where read state can be changed it says that the change is the owner's too.
     *
     * @returns {String} the sentence
     */
    rightsSummary() {
      const capabilities = sharedMailboxCapabilities(this.entry?.affordances);
      if (capabilities.markRead && capabilities.moveOut) {
        return this.$t('UserSettings.emailConnector.sharedWithMe.rights.markReadMoveOut');
      }
      if (capabilities.moveOut) {
        return this.$t('UserSettings.emailConnector.sharedWithMe.rights.moveOut');
      }
      return capabilities.markRead
        ? this.$t('UserSettings.emailConnector.sharedWithMe.rights.markRead')
        : this.$t('UserSettings.emailConnector.sharedWithMe.rights.read');
    },
    /**
     * "You are in Alice's mailbox · Reader" in pieces, the owner's name and the level
     * in bold. Built from the one translated sentence, placeholders marked, so a
     * translation may put them wherever its language wants them.
     *
     * @returns {Array} [{text, bold}]
     */
    messageParts() {
      if (!this.entry) {
        return [];
      }
      const sentence = this.$t('emailConnector.mailBox.sharedMailbox.band', { 0: OWNER_MARK, 1: LEVEL_MARK });
      const values = { [OWNER_MARK]: this.entry.ownerFullName, [LEVEL_MARK]: this.levelLabel };
      return sentence.split(new RegExp(`(${OWNER_MARK}|${LEVEL_MARK})`))
        .filter(part => part)
        .map(part => (values[part] ? { text: values[part], bold: true } : { text: part, bold: false }));
    },
  },
};
</script>
