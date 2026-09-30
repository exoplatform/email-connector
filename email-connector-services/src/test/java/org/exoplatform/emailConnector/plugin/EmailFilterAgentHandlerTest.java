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
package org.exoplatform.emailConnector.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.EmailFilterProposal;

/**
 * The declaration's own default: without an add-on that overrides
 * {@link EmailFilterAgentHandler#executeProposal}, every call is refused -- there is
 * nothing here to run a tool with.
 */
public class EmailFilterAgentHandlerTest {

  /**
   * A handler that overrides nothing refuses every call, with the code the owner reads as
   * "nothing runs tools here".
   */
  @Test
  void theDefaultHandlerRefusesEveryCall() {
    EmailFilterAgentHandler handler = new EmailFilterAgentHandler() {
    };

    UnsupportedOperationException refusal = assertThrows(UnsupportedOperationException.class,
                                                          () -> handler.executeProposal("alice", new EmailFilterProposal()));

    assertEquals("emailConnector.filters.proposal.unavailable", refusal.getMessage());
  }
}
