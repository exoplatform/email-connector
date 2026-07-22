/*
 * Copyright (C) 2025 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

/*
 * Dedicated CKEditor "attach file" button for the email compose editor. It mirrors
 * the activity/notes ecms "attachFile" plugin but dispatches its OWN dedicated DOM
 * event (open-email-attachments) so it never collides with a co-mounted activity or
 * notes composer that listens for open-activity-attachments / open-notes-attachments.
 */
CKEDITOR.plugins.add('attachEmailFile', {

  // Register the icons. They must match command names.
  icons: 'attachEmailFile',
  lang: ['en', 'fr'],

  init: function(editor) {
    editor.addCommand('attachEmailFile', {
      exec: function() {
        document.dispatchEvent(new CustomEvent('open-email-attachments'));
      }
    });

    // Create the toolbar button that executes the above command.
    editor.ui.addButton('attachEmailFile', {
      label: editor.lang.attachEmailFile.buttonTooltip,
      command: 'attachEmailFile',
      toolbar: 'insert'
    });

    // Keep the icon within the standard toolbar icon box (other icons render at
    // ~16px), so it does not look bigger than its siblings.
    editor.on('instanceReady', function() {
      try {
        const container = editor.container && editor.container.$;
        const ourIcon = container && container.querySelector('.cke_button__attachemailfile_icon');
        if (ourIcon) {
          ourIcon.style.setProperty('background-size', 'contain', 'important');
          ourIcon.style.setProperty('background-repeat', 'no-repeat', 'important');
          ourIcon.style.setProperty('background-position', 'center', 'important');
        }
      } catch (e) {
        // Non-fatal: the button still works, only its icon sizing is skipped.
      }
    });
  }
});
