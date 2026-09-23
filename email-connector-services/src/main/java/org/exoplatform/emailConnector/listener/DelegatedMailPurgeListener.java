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

import org.exoplatform.emailConnector.event.DelegatedFoldersDroppedEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;

/**
 * Glue: the mail of a shared mailbox's dropped folders goes when the folders go (stack
 * review #437-1). Synchronous, so a revoke or a leave has taken the owner's mail out of
 * the delegate's database by the time it answers.
 */
@Component
public class DelegatedMailPurgeListener {

  @Autowired
  private EmailBoxService emailBoxService;

  /**
   * Purges the mirrored mail of the dropped folders.
   *
   * @param event the dropped folders
   */
  @EventListener
  public void onDelegatedFoldersDropped(DelegatedFoldersDroppedEvent event) {
    emailBoxService.purgeDelegatedMirror(event.username(), event.folderKeys());
  }
}
