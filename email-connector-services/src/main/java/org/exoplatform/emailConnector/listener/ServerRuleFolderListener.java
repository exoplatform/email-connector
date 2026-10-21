/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.event.CustomFoldersRelocatedEvent;
import org.exoplatform.emailConnector.service.EmailServerRuleService;

/**
 * Hands a renamed or moved folder to the server rules that file into it, so the mail
 * server's script follows the new name (EXO-90839). An event rather than a call: the
 * rule service already depends on the mailbox service that raises it.
 */
@Component
public class ServerRuleFolderListener {

  @Autowired
  private EmailServerRuleService emailServerRuleService;

  /**
   * Re-resolves the server rules that file into the relocated folders.
   *
   * @param event the relocated folders
   */
  @EventListener
  public void onFoldersRelocated(CustomFoldersRelocatedEvent event) {
    emailServerRuleService.followRelocatedFolders(event.getUsername(), event.getFolderKeys());
  }
}
