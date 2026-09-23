/**
 * Copyright (C) 2025 eXo Platform SAS
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

import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.event.DelegatedFoldersDroppedEvent;
import org.exoplatform.emailConnector.service.EmailBoxService;

/**
 * Stack review #437-1 -- the glue between a share's dropped folders and their mail.
 */
@ExtendWith(MockitoExtension.class)
class DelegatedMailPurgeListenerTest {

  @Mock
  private EmailBoxService            emailBoxService;

  @InjectMocks
  private DelegatedMailPurgeListener listener;

  /**
   * The dropped folders' mail is purged, for their delegate.
   */
  @Test
  void theDroppedFoldersMailIsPurged() {
    listener.onDelegatedFoldersDropped(new DelegatedFoldersDroppedEvent("bob", List.of("CUSTOM:12")));

    verify(emailBoxService).purgeDelegatedMirror("bob", List.of("CUSTOM:12"));
  }
}
