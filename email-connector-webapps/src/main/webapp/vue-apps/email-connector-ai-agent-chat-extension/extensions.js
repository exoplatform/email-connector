/*
 * Copyright (C) 2026 eXo Platform SAS.
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

/**
 * The approval card of send_email_draft, which names the files the draft carries: a
 * draft may carry documents the assistant attached, and the user approves every file
 * that leaves. Registered through the AI add-on's extension point, app 'ai-agent', type
 * 'tool-confirm'.
 */
extensionRegistry.registerExtension('ai-agent', 'tool-confirm', {
  id: 'send_email_draft',
  componentName: 'email-connector-ai-send-draft-confirm',
});
