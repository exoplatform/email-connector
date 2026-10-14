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
package org.exoplatform.emailConnector.service.acl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;

/**
 * The engine of a preset without sharing: the probe says unsupported with the
 * provider's reason, and every operation refuses with that same code, never touching
 * the session.
 */
class NoopAclEngineTest {

  private final NoopAclEngine engine = new NoopAclEngine();

  /**
   * The engine is named {@code none} and probes as unsupported by the provider.
   */
  @Test
  void itProbesAsUnsupported() {
    assertEquals(NoopAclEngine.NAME, engine.getName());
    MailboxAclCapabilities capabilities = engine.probe(null);
    assertFalse(capabilities.supported());
    assertEquals(MailboxAclException.UNSUPPORTED_PROVIDER, capabilities.reasonCode());
  }

  /**
   * Every read and write refuses with {@link MailboxAclException#UNSUPPORTED_PROVIDER}.
   */
  @Test
  void everyOperationRefuses() {
    assertUnsupported(() -> engine.myRights(null, "INBOX"));
    assertUnsupported(() -> engine.listAcl(null, "INBOX"));
    assertUnsupported(() -> engine.grant(null, "INBOX", "bob", DelegationPreset.READER, null));
    assertUnsupported(() -> engine.revoke(null, "INBOX", "bob"));
    assertUnsupported(() -> engine.listSharedMailboxes(null));
    assertUnsupported(() -> engine.findSharedMailbox(null, "alice"));
  }

  /**
   * @param operation the engine call expected to refuse
   */
  private void assertUnsupported(Executable operation) {
    MailboxAclException e = assertThrows(MailboxAclException.class, operation);
    assertEquals(MailboxAclException.UNSUPPORTED_PROVIDER, e.getCode());
  }
}
