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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import org.exoplatform.emailConnector.event.CustomFoldersRelocatedEvent;
import org.exoplatform.emailConnector.service.EmailServerRuleService;

/**
 * The glue between a renamed or moved folder and the server rules that file into it
 * (EXO-90839).
 */
@ExtendWith(MockitoExtension.class)
public class ServerRuleFolderListenerTest {

  @Mock
  private EmailServerRuleService   emailServerRuleService;

  @InjectMocks
  private ServerRuleFolderListener listener;

  /**
   * The owner and the relocated folders' keys are handed to the rule service as they came.
   */
  @Test
  void theRelocatedFoldersAreHandedToTheRuleService() {
    listener.onFoldersRelocated(new CustomFoldersRelocatedEvent("alice", List.of("CUSTOM:5", "CUSTOM:6")));

    verify(emailServerRuleService).followRelocatedFolders("alice", List.of("CUSTOM:5", "CUSTOM:6"));
  }

  /**
   * Pins the wiring: the annotation-removed mutant (no {@code @EventListener}) leaves
   * every rule filing into the old name with every other test green, since the rule
   * service's own tests call it by hand; an {@code @Async} one would rewrite the rules
   * on another thread, after the request that renamed the folder answered.
   *
   * @throws Exception never
   */
  @Test
  void theListenerIsWiredAndSynchronous() throws Exception {
    var method = ServerRuleFolderListener.class.getMethod("onFoldersRelocated", CustomFoldersRelocatedEvent.class);
    assertTrue(method.isAnnotationPresent(EventListener.class));
    assertFalse(method.isAnnotationPresent(Async.class));
    assertFalse(ServerRuleFolderListener.class.isAnnotationPresent(Async.class));
  }
}
