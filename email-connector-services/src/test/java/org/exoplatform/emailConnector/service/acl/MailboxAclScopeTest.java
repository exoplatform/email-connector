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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.AclScope;
import org.exoplatform.emailConnector.model.DelegationGrantee;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.GrantGranularity;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SendMode;

/**
 * The mailbox-or-folder scope the sharing SPI gained for a server that keeps both
 * (EXO-90816), and how it stays out of the way of every engine written before it: an
 * entry that names no scope stands on its folder, a server that names no scope keeps
 * none, and the whole-mailbox writes of an engine that does not override them are its
 * writes on INBOX.
 */
class MailboxAclScopeTest {

  /**
   * An engine that does not override the whole-mailbox grant writes on INBOX -- the name
   * of the mailbox as a whole on a per-mailbox server -- and answers that write.
   */
  @Test
  void aWholeMailboxGrantIsTheInboxGrantByDefault() {
    MailboxAclEngine engine = mock(MailboxAclEngine.class, CALLS_REAL_METHODS);
    MailboxAclSession session = mock(MailboxAclSession.class);
    MailboxRights owner = MailboxRights.of("lrswipkxtea");
    MailboxAce written = MailboxAce.ofLetters("bob@acme.com", MailboxRights.of("lrs"));
    doReturn(written).when(engine).grant(any(), anyString(), anyString(), any(), any());

    assertSame(written, engine.grantWholeMailbox(session, "bob@acme.com", DelegationPreset.READER, owner));
    verify(engine).grant(session, "INBOX", "bob@acme.com", DelegationPreset.READER, owner);
  }

  /**
   * An engine that does not override the whole-mailbox removal removes on INBOX.
   */
  @Test
  void aWholeMailboxRemovalIsTheInboxRemovalByDefault() {
    MailboxAclEngine engine = mock(MailboxAclEngine.class, CALLS_REAL_METHODS);
    MailboxAclSession session = mock(MailboxAclSession.class);
    doNothing().when(engine).revoke(any(), anyString(), anyString());

    engine.revokeWholeMailbox(session, "bob@acme.com");

    verify(engine).revoke(session, "INBOX", "bob@acme.com");
  }

  /**
   * An engine that does not override the bulk read asks each folder in turn: a folder
   * whose list is refused is left out of the answer, and a lost connection fails the
   * whole read.
   */
  @Test
  void theBulkReadAsksEachFolderByDefault() {
    MailboxAclEngine engine = mock(MailboxAclEngine.class, CALLS_REAL_METHODS);
    MailboxAclSession session = mock(MailboxAclSession.class);
    List<MailboxAce> inbox = List.of(MailboxAce.ofLetters("bob", MailboxRights.of("lrs")));
    doReturn(inbox).when(engine).listAcl(session, "INBOX");
    doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO")).when(engine).listAcl(session, "Sent");
    doThrow(new MailboxAclException(MailboxAclException.UNREACHABLE, "down")).when(engine).listAcl(session, "Trash");

    Map<String, List<MailboxAce>> acls = engine.listAcls(session, List.of("INBOX", "Sent"));

    assertEquals(Map.of("INBOX", inbox), acls);
    assertEquals(MailboxAclException.UNREACHABLE,
                 assertThrows(MailboxAclException.class, () -> engine.listAcls(session, List.of("INBOX", "Trash"))).getCode());
  }

  /**
   * An entry built without a scope, or with none, stands on its folder: every RFC 4314
   * entry does.
   */
  @Test
  void anEntryNamingNoScopeStandsOnItsFolder() {
    MailboxRights read = MailboxRights.of("lrs");
    assertEquals(AclScope.FOLDER, new MailboxAce("bob", read, "lrs", DelegationPreset.READER).scope());
    assertEquals(AclScope.FOLDER, new MailboxAce("bob", read, "lrs", DelegationPreset.READER, null).scope());
    assertEquals(AclScope.FOLDER, MailboxAce.ofLetters("bob", read).scope());
    assertEquals(AclScope.MAILBOX, new MailboxAce("bob", read, "Read", DelegationPreset.READER, AclScope.MAILBOX).scope());
  }

  /**
   * A server built the way every engine before EXO-90816 builds it keeps no
   * whole-mailbox entry and never lets an Editor delete for good in the owner's Trash;
   * one that says otherwise is heard.
   */
  @Test
  void aServerNamingNoScopeKeepsNone() {
    MailboxAclCapabilities seven = new MailboxAclCapabilities(true, true, true, GrantGranularity.MAILBOX, true, true, null);
    MailboxAclCapabilities nine = new MailboxAclCapabilities(true,
                                                             false,
                                                             false,
                                                             GrantGranularity.MAILBOX,
                                                             true,
                                                             true,
                                                             null,
                                                             Set.of(SendMode.ON_BEHALF),
                                                             true);
    for (MailboxAclCapabilities capabilities : List.of(seven,
                                                       nine,
                                                       MailboxAclCapabilities.imap(true, true),
                                                       MailboxAclCapabilities.unsupported("code"))) {
      assertFalse(capabilities.mailboxScope());
      assertFalse(capabilities.editorExpungesTrash());
    }
    assertEquals(Set.of(SendMode.ON_BEHALF), nine.sendModes());
    assertTrue(nine.sendModeOnServer());
    MailboxAclCapabilities both = new MailboxAclCapabilities(true,
                                                             false,
                                                             false,
                                                             GrantGranularity.FOLDER,
                                                             true,
                                                             true,
                                                             null,
                                                             Set.of(),
                                                             true,
                                                             true,
                                                             false);
    assertTrue(both.mailboxScope());
    assertFalse(both.editorExpungesTrash());
    MailboxAclCapabilities trash = new MailboxAclCapabilities(true,
                                                              false,
                                                              false,
                                                              GrantGranularity.FOLDER,
                                                              true,
                                                              true,
                                                              null,
                                                              Set.of(),
                                                              true,
                                                              false,
                                                              true);
    assertFalse(trash.mailboxScope());
    assertTrue(trash.editorExpungesTrash());
  }

  /**
   * The owner's list entry carries its entry's scope, keeps it through what the list adds
   * to it, and stands on its folders when it names none.
   */
  @Test
  void theOwnersListSaysWhereEachEntryStands() {
    MailboxAce whole = new MailboxAce("bob", MailboxRights.of("lrp"), "Read", DelegationPreset.READER, AclScope.MAILBOX);

    DelegationGrantee grantee = DelegationGrantee.of(whole, "bob", null);

    assertEquals(AclScope.MAILBOX, grantee.scope());
    assertEquals(AclScope.MAILBOX, grantee.withExtendableRoles(List.of(FolderRole.SENT)).scope());
    assertEquals(AclScope.MAILBOX, grantee.withConsentContext("Bob", true).scope());
    assertEquals(AclScope.FOLDER,
                 new DelegationGrantee("bob", "bob", null, null, "lrp", "Read", null, List.of(), null, false, null).scope());
  }
}
