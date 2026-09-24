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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import javax.mail.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import org.exoplatform.emailConnector.event.DelegatedFoldersDroppedEvent;
import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.DelegationFolder;
import org.exoplatform.emailConnector.model.DelegationFolders;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderAccess;
import org.exoplatform.emailConnector.model.FolderAccessChange;
import org.exoplatform.emailConnector.model.FolderAccessResult;
import org.exoplatform.emailConnector.model.FolderAccessResult.Outcome;
import org.exoplatform.emailConnector.model.FolderAccessUpdate;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.GrantGranularity;
import org.exoplatform.emailConnector.model.MailFolderView;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.OwnFolder;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngine;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailDelegationStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * EXO-90556 -- the owner chooses what to share, folder by folder: the owner's folder list
 * read from the server, the per-folder save and what it leaves on both sides, the role
 * folders set apart at invitation, "Change access" and "Extend access" with per-folder
 * exceptions, and the owner's rename or delete of a shared folder.
 */
@ExtendWith(MockitoExtension.class)
class EmailDelegationFolderAccessTest {

  private static final String                 OWNER           = "alice";

  private static final String                 OWNER_MAILBOX   = "alice@acme.com";

  private static final String                 GRANTEE         = "bob";

  private static final String                 GRANTEE_MAILBOX = "bob@acme.com";

  private static final long                   CONNECTOR_ID    = 7L;

  private static final String                 INBOX           = "INBOX";

  private static final String                 ROOT            = "Other Users/alice";

  private static final MailboxAclCapabilities SUPPORTED       = MailboxAclCapabilities.imap(true, true);

  private static final MailboxAclCapabilities PER_MAILBOX     = new MailboxAclCapabilities(true,
                                                                                           true,
                                                                                           true,
                                                                                           GrantGranularity.MAILBOX,
                                                                                           false,
                                                                                           false,
                                                                                           null);

  private static final MailboxRights          OWNER_RIGHTS    = MailboxRights.of("lrswipkxtea");

  @Mock
  private UserEmailSettingService             userEmailSettingService;

  @Mock
  private EmailConnectorService               emailConnectorService;

  @Mock
  private MailboxAclEngineRegistry            aclEngineRegistry;

  @Mock
  private EmailDelegationStorage              emailDelegationStorage;

  @Mock
  private EmailFolderStorage                  emailFolderStorage;

  @Mock
  private EmailBoxStorage                     emailBoxStorage;

  @Mock
  private IdentityManager                     identityManager;

  @Mock
  private ApplicationEventPublisher           eventPublisher;

  @Mock
  private MailboxAclEngine                    engine;

  @Mock
  private Store                               store;

  @InjectMocks
  private EmailDelegationService              service;

  /**
   * Alice and Bob connected on connector 7; the registry answers the mocked engine, the
   * owner holds every right, and the owner's writes are answered as the storage does.
   */
  @BeforeEach
  void setUp() throws Exception {
    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    lenient().when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    lenient().when(aclEngineRegistry.engineFor(connector)).thenReturn(engine);
    lenient().when(userEmailSettingService.getUserEmailSetting(OWNER)).thenReturn(setting(OWNER_MAILBOX));
    lenient().when(userEmailSettingService.getUserEmailSetting(GRANTEE)).thenReturn(setting(GRANTEE_MAILBOX));
    lenient().when(userEmailSettingService.connect(anyString(), anyString())).thenReturn(store);
    lenient().when(engine.probe(any())).thenReturn(SUPPORTED);
    lenient().when(engine.myRights(any(), anyString())).thenReturn(OWNER_RIGHTS);
    lenient().when(engine.presetOf(any())).thenAnswer(invocation -> DelegationPreset.fromRights(invocation.getArgument(0)));
    lenient().when(engine.listOwnFolders(any())).thenReturn(ownFolders());
    // The IMAP engine's letters: an Editor holds e where mail leaves, never on Trash.
    lenient().when(engine.lettersFor(any(), any())).thenAnswer(invocation -> {
      DelegationPreset preset = invocation.getArgument(0);
      if (preset == DelegationPreset.EDITOR) {
        return invocation.getArgument(1) == FolderRole.TRASH ? MailboxRights.of("lrswit") : MailboxRights.of("lrswite");
      }
      return preset == null ? MailboxRights.NONE : preset.rights();
    });
    lenient().when(engine.grant(any(), anyString(), anyString(), any(), any(), any())).thenAnswer(invocation -> {
      DelegationPreset preset = invocation.getArgument(3);
      return MailboxAce.ofLetters(GRANTEE_MAILBOX, preset.rights());
    });
    lenient().when(emailDelegationStorage.create(any())).thenAnswer(invocation -> {
      EmailDelegation created = invocation.getArgument(0);
      created.setId(100L);
      return created;
    });
    lenient().when(engine.listAcl(any(), eq(INBOX))).thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "GETACL"));
    Identity bob = new Identity("organization", GRANTEE);
    bob.setEnable(true);
    lenient().when(identityManager.getOrCreateUserIdentity(GRANTEE)).thenReturn(bob);
  }

  /**
   * Puts the cap back the way the JVM had it.
   */
  @AfterEach
  void clearTheTunables() {
    System.clearProperty(EmailDelegationService.MAX_FOLDERS_PROPERTY);
  }

  // ---------------------------------------------------------------------------------
  // The owner's list
  // ---------------------------------------------------------------------------------

  /**
   * The list is the server's: each folder's access read with GETACL on that folder --
   * Reader, Editor, not shared, the raw letters of an entry that reads as no preset, and
   * "could not be read" rather than a guess. INBOX first and not editable, the role
   * folders next, then the owner's folders as a tree; never Drafts. Nothing is written.
   */
  @Test
  void theOwnersListIsReadFromTheServerFolderByFolder() throws Exception {
    EmailDelegation share = accepted();
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(share);
    // INBOX's own GETACL is refused in setUp: it reads as "could not be read".
    when(engine.listAcl(any(), eq("Sent"))).thenReturn(List.of(ace(GRANTEE_MAILBOX.toUpperCase(), "lrs")));
    when(engine.listAcl(any(), eq("Corbeille"))).thenReturn(List.of(ace(OWNER_MAILBOX, "lrswipkxtea")));
    when(engine.listAcl(any(), eq("Archive"))).thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO"));
    when(engine.listAcl(any(), eq("Spam"))).thenReturn(List.of(ace(GRANTEE_MAILBOX, "lrswit")));
    when(engine.listAcl(any(), eq("Projects"))).thenReturn(List.of(ace(GRANTEE_MAILBOX, "lrk")));
    when(engine.listAcl(any(), eq("Projects/2024"))).thenReturn(List.of());
    when(engine.listAcl(any(), eq("Projects-old"))).thenReturn(List.of());

    DelegationFolders list = service.getFolderAccess(OWNER, 100L);

    assertEquals(List.of(INBOX, "Sent", "Archive", "Corbeille", "Spam", "Projects", "Projects/2024", "Projects-old"),
                 list.folders().stream().map(DelegationFolder::folder).toList(),
                 "INBOX, the roles in grant order, then the tree -- a folder's children right after it; never Drafts");
    assertFalse(list.truncated());
    DelegationFolder inbox = list.folders().get(0);
    assertFalse(inbox.editable(), "INBOX is the share itself");
    assertEquals(FolderAccess.READER, byName(list, "Sent").access(), "an identifier compared ignoring case");
    assertEquals(FolderAccess.NONE, byName(list, "Corbeille").access());
    assertFalse(byName(list, "Archive").readable(), "an ACL that cannot be read is said so");
    assertNull(byName(list, "Archive").access());
    assertEquals(FolderAccess.EDITOR, byName(list, "Spam").access());
    DelegationFolder custom = byName(list, "Projects");
    assertNull(custom.access(), "letters no preset names");
    assertEquals("lrk", custom.rights(), "are shown raw");
    DelegationFolder child = byName(list, "Projects/2024");
    assertEquals("Projects", child.parent());
    assertEquals(1, child.depth());
    assertEquals("2024", child.displayName());
    verify(engine, never()).grant(any(), anyString(), anyString(), any(), any(), any());
    verify(engine, never()).revoke(any(), anyString(), anyString());
  }

  /**
   * At most the cap beside INBOX -- the delegate's discovery registers no more -- the
   * role folders first, and the list says when some are left out.
   */
  @Test
  void theOwnersListStopsAtTheCapRoleFoldersFirst() throws Exception {
    System.setProperty(EmailDelegationService.MAX_FOLDERS_PROPERTY, "2");

    DelegationFolders list = service.getOwnFolders(OWNER);

    assertEquals(List.of(INBOX, "Sent", "Archive"), list.folders().stream().map(DelegationFolder::folder).toList());
    assertTrue(list.truncated());
    assertTrue(list.folders().stream().allMatch(folder -> folder.access() == null), "no ACL read for the invitation's list");
    verify(engine, never()).listAcl(any(), anyString());
  }

  /**
   * On a server that grants a whole mailbox at once there is nothing to choose folder by
   * folder: said, not shown.
   */
  @Test
  void aPerMailboxServerHasNoFolderList() throws Exception {
    when(engine.probe(any())).thenReturn(PER_MAILBOX);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());

    assertEquals(EmailDelegationService.PER_FOLDER_UNSUPPORTED_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.getFolderAccess(OWNER, 100L)).getMessage());
    assertEquals(EmailDelegationService.PER_FOLDER_UNSUPPORTED_MESSAGE,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.setFolderAccess(OWNER, 100L, List.of(change("Sent", FolderAccess.READER)))).getMessage());
    verify(engine, never()).listOwnFolders(any());
  }

  // ---------------------------------------------------------------------------------
  // The per-folder save
  // ---------------------------------------------------------------------------------

  /**
   * Only the owner's own folders, checked before anything is written: a folder under
   * another user's namespace -- where the owner's own administer right would write
   * someone else's ACL -- or one that does not exist is unknown; INBOX and Drafts are
   * not changed from here. Nothing reaches the server for a request that fails.
   */
  @Test
  void onlyTheOwnersOwnFoldersCanBeChanged() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());

    for (String foreign : List.of("Other Users/carol/INBOX", "Shared Folders/team", "sent", "Nowhere")) {
      assertEquals(EmailDelegationService.FOLDER_UNKNOWN_MESSAGE,
                   assertThrows(IllegalArgumentException.class,
                                () -> service.setFolderAccess(OWNER,
                                                              100L,
                                                              List.of(change("Sent", FolderAccess.READER),
                                                                      change(foreign, FolderAccess.EDITOR)))).getMessage(),
                   foreign);
    }
    for (String notEditable : List.of("INBOX", "inbox", "Drafts")) {
      assertEquals(EmailDelegationService.FOLDER_NOT_EDITABLE_MESSAGE,
                   assertThrows(IllegalArgumentException.class,
                                () -> service.setFolderAccess(OWNER, 100L, List.of(change(notEditable, FolderAccess.NONE)))).getMessage(),
                   notEditable);
    }
    verify(engine, never()).grant(any(), anyString(), anyString(), any(), any(), any());
    verify(engine, never()).revoke(any(), anyString(), anyString());
  }

  /**
   * A request checked before the row is even read: empty, over the cap, a folder named
   * twice, or a change without a folder or an access.
   */
  @Test
  void anInvalidRequestIsRefusedBeforeAnything() throws Exception {
    System.setProperty(EmailDelegationService.MAX_FOLDERS_PROPERTY, "2");
    List<List<FolderAccessChange>> invalid = new ArrayList<>();
    invalid.add(List.of());
    invalid.add(List.of(change("Sent", FolderAccess.READER), change("Archive", FolderAccess.READER), change("Spam", FolderAccess.READER)));
    invalid.add(List.of(change("Sent", FolderAccess.READER), change("Sent", FolderAccess.NONE)));
    invalid.add(List.of(change(" ", FolderAccess.READER)));
    invalid.add(List.of(change("Sent", null)));
    for (List<FolderAccessChange> changes : invalid) {
      assertEquals(EmailDelegationService.FOLDER_ACCESS_INVALID_MESSAGE,
                   assertThrows(IllegalArgumentException.class, () -> service.setFolderAccess(OWNER, 100L, changes)).getMessage());
    }
    assertEquals(EmailDelegationService.FOLDER_ACCESS_INVALID_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.setFolderAccess(OWNER, 100L, null)).getMessage());
    verifyNoInteractions(emailDelegationStorage);
  }

  /**
   * The role is the owner's session's, never the request's: Editor on the folder that is
   * the owner's Trash is written as the Trash -- where the engine never gives
   * {@code e} (PO decision Q-1) -- and Editor on a folder of the owner's own as one mail
   * leaves (PO decision P-1).
   */
  @Test
  void theRoleAFolderIsSharedWithIsReadOnTheOwnersSession() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    answerTheFolderGrantsWrite();

    service.setFolderAccess(OWNER, 100L, List.of(change("Corbeille", FolderAccess.EDITOR), change("Projects", FolderAccess.EDITOR)));

    verify(engine).grant(any(), eq("Corbeille"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), eq(OWNER_RIGHTS), eq(FolderRole.TRASH));
    verify(engine).grant(any(), eq("Projects"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), eq(OWNER_RIGHTS), isNull());
  }

  /**
   * Each folder is its own write: one refused undoes nothing, and each says what became
   * of it. A Reader the server refuses removes the entry rather than leave a wider one;
   * the row records what the server now holds on the role folders -- the ones granted,
   * and the owner's exceptions, a role no longer shared among them.
   */
  @Test
  void eachFolderIsItsOwnWriteAndSaysWhatBecameOfIt() throws Exception {
    EmailDelegation share = accepted();
    share.setGrantedRoles("INBOX,SENT,ARCHIVE,TRASH");
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(share);
    when(engine.grant(any(), eq("Corbeille"), anyString(), eq(DelegationPreset.EDITOR), any(), any()))
                                                                                                    .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                                       "NO"));
    when(engine.grant(any(), eq("Archive"), anyString(), eq(DelegationPreset.READER), any(), any()))
                                                                                                  .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                                     "NO"));
    when(engine.grant(any(), eq("Spam"), anyString(), eq(DelegationPreset.READER), any(), any()))
                                                                                               .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                                  "NO"));
    lenient().doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO")).when(engine).revoke(any(), eq("Spam"), anyString());
    when(engine.grant(any(), eq("Projects-old"), anyString(), any(), any(), any()))
                                                                                  .thenThrow(new MailboxAclException(MailboxAclException.NOTHING_TO_GRANT,
                                                                                                                     "NO"));
    Map<String, Object> written = answerTheFolderGrantsWrite();

    FolderAccessUpdate update = service.setFolderAccess(OWNER,
                                                        100L,
                                                        List.of(change("Sent", FolderAccess.READER),
                                                                change("Corbeille", FolderAccess.EDITOR),
                                                                change("Archive", FolderAccess.READER),
                                                                change("Spam", FolderAccess.READER),
                                                                change("Projects", FolderAccess.NONE),
                                                                change("Projects-old", FolderAccess.READER)));

    assertEquals(List.of(Outcome.DONE, Outcome.REFUSED, Outcome.REMOVED, Outcome.NOT_NARROWED, Outcome.DONE, Outcome.NOTHING_TO_GRANT),
                 update.results().stream().map(FolderAccessResult::outcome).toList());
    verify(engine).revoke(any(), eq("Projects"), eq(GRANTEE_MAILBOX));
    assertEquals("INBOX,SENT,TRASH", written.get("grantedRoles"), "Archive no longer shared; Trash as it was");
    Map<FolderRole, FolderAccess> exceptions = new EnumMap<>(FolderRole.class);
    exceptions.put(FolderRole.SENT, FolderAccess.READER);
    exceptions.put(FolderRole.ARCHIVE, FolderAccess.NONE);
    assertEquals(exceptions, written.get("folderAccess"));
    verify(emailFolderStorage).markDiscoveryDue(100L);
    verify(emailDelegationStorage, never()).update(any());
  }

  /**
   * A lost connection stops the loop: the folders after it are not attempted, and say
   * so; what was written before it is recorded.
   */
  @Test
  void aLostConnectionStopsTheLoopAndSaysWhatWasNotReached() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    when(engine.grant(any(), eq("Archive"), anyString(), any(), any(), any()))
                                                                             .thenThrow(new MailboxAclException(MailboxAclException.UNREACHABLE,
                                                                                                                "down"));
    Map<String, Object> written = answerTheFolderGrantsWrite();

    FolderAccessUpdate update = service.setFolderAccess(OWNER,
                                                        100L,
                                                        List.of(change("Sent", FolderAccess.EDITOR),
                                                                change("Archive", FolderAccess.EDITOR),
                                                                change("Spam", FolderAccess.EDITOR)));

    assertEquals(List.of(Outcome.DONE, Outcome.NOT_REACHED, Outcome.NOT_REACHED),
                 update.results().stream().map(FolderAccessResult::outcome).toList());
    verify(engine, never()).grant(any(), eq("Spam"), anyString(), any(), any(), any());
    assertEquals("INBOX,SENT", written.get("grantedRoles"));
  }

  /**
   * A folder no longer shared leaves the delegate's screens at once: that folder's
   * registered copy -- and only that one, compared exactly, not a sibling whose name
   * begins the same, not a folder inside it, which keeps its own ACL -- is dropped with
   * the mail mirrored under it.
   */
  @Test
  void aFolderNoLongerSharedLeavesTheDelegatesScreensAtOnce() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    answerTheFolderGrantsWrite();
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(granteeFolders());

    service.setFolderAccess(OWNER, 100L, List.of(change("Projects", FolderAccess.NONE)));

    verify(emailFolderStorage).deleteFolder(GRANTEE, 11L);
    verify(emailFolderStorage, never()).deleteFolder(GRANTEE, 10L);
    verify(emailFolderStorage, never()).deleteFolder(GRANTEE, 12L);
    verify(emailFolderStorage, never()).deleteFolder(GRANTEE, 13L);
    ArgumentCaptor<DelegatedFoldersDroppedEvent> dropped = ArgumentCaptor.forClass(DelegatedFoldersDroppedEvent.class);
    verify(eventPublisher).publishEvent(dropped.capture());
    assertEquals(GRANTEE, dropped.getValue().username());
    assertEquals(List.of("CUSTOM:11"), dropped.getValue().folderKeys(), "its mirrored mail goes with it");
  }

  /**
   * The delegate's copy of a folder under INBOX is found by the name discovery
   * registered: on a Dovecot-style shared namespace {@code INBOX/Sub} is listed as
   * {@code <root>/INBOX/Sub}, and a top-level {@code Sub} beside it is never taken for
   * it; where only the name without INBOX is registered (a server that names the owner's
   * folders under INBOX), that one is.
   */
  @Test
  void aFolderUnderInboxIsFoundByTheNameDiscoveryRegistered() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    answerTheFolderGrantsWrite();
    when(engine.listOwnFolders(any())).thenReturn(List.of(own(INBOX, null), own("INBOX/ZZRev", null), own("ZZRev", null), own("INBOX/Legacy", null)));
    List<EmailFolder> rows = granteeFolders();
    rows.add(granteeFolder(17L, ROOT + "/INBOX/ZZRev", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    rows.add(granteeFolder(18L, ROOT + "/ZZRev", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    rows.add(granteeFolder(19L, ROOT + "/Legacy", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(rows);

    service.setFolderAccess(OWNER, 100L, List.of(change("INBOX/ZZRev", FolderAccess.NONE), change("INBOX/Legacy", FolderAccess.NONE)));

    verify(emailFolderStorage).deleteFolder(GRANTEE, 17L);
    verify(emailFolderStorage, never()).deleteFolder(GRANTEE, 18L);
    verify(emailFolderStorage).deleteFolder(GRANTEE, 19L);
  }

  /**
   * The name without INBOX is only a fallback for a folder of the owner's that no other
   * folder of hers goes by: with {@code INBOX/Sub} not registered yet and the owner's own
   * top-level {@code Sub} shared and registered, "Not shared" on {@code INBOX/Sub} never
   * drops the copy of {@code Sub}; and a hook that has not listed the owner's folders
   * names nothing it cannot be sure of.
   */
  @Test
  void theFallbackNeverTakesAnotherFolderOfTheOwners() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    answerTheFolderGrantsWrite();
    when(engine.listOwnFolders(any())).thenReturn(List.of(own(INBOX, null), own("INBOX/Sub", null), own("Sub", null)));
    List<EmailFolder> rows = granteeFolders();
    rows.add(granteeFolder(21L, ROOT + "/Sub", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(rows);

    service.setFolderAccess(OWNER, 100L, List.of(change("INBOX/Sub", FolderAccess.NONE)));
    service.dropDelegatedFolderTree(accepted(), "INBOX/Other", false, null);

    verify(emailFolderStorage, never()).deleteFolder(anyString(), anyLong());
  }

  /**
   * "Not shared" is recorded when it takes something away: a role folder the server
   * refused at the grant, sent back as it was shown, stays said refused and offered by
   * "Extend access" -- not turned into the owner's choice by an unrelated save.
   */
  @Test
  void aRoleFolderNobodyTouchedIsNotTurnedIntoTheOwnersChoice() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    Map<String, Object> written = answerTheFolderGrantsWrite();

    service.setFolderAccess(OWNER, 100L, List.of(change("Corbeille", FolderAccess.NONE), change("Sent", FolderAccess.NONE)));

    assertEquals(Map.of(FolderRole.SENT, FolderAccess.NONE), written.get("folderAccess"), "Sent was shared: not sharing it is a choice");
    assertEquals("INBOX", written.get("grantedRoles"));
  }

  /**
   * A folder narrowed to Reader narrows what the delegate's screens read at once, the
   * letters they held intersected with the ones written -- never widened from the
   * owner's side: a delegate who held less than Reader keeps less.
   */
  @Test
  void aReaderNarrowsTheDelegatesLettersAtOnceAndNeverWidensThem() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    answerTheFolderGrantsWrite();
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(granteeFolders());

    service.setFolderAccess(OWNER,
                            100L,
                            List.of(change("Sent", FolderAccess.READER), change("Archive", FolderAccess.READER), change("Spam", FolderAccess.EDITOR)));

    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(14L), eq(100L), eq(FolderRole.SENT), eq("lrs"), any(Date.class));
    verify(emailFolderStorage, never()).updateDelegatedRights(eq(GRANTEE), eq(15L), anyLong(), any(), anyString(), any());
    verify(emailFolderStorage, never()).updateDelegatedRights(eq(GRANTEE), eq(16L), anyLong(), any(), anyString(), any());
  }

  /**
   * An Editor chosen over a wider entry narrows too: on the owner's Trash the grant
   * writes no {@code e}, and the delegate's recorded letters lose it at once.
   */
  @Test
  void anEditorOnTrashTakesThePermanentDeletionAwayAtOnce() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    answerTheFolderGrantsWrite();
    List<EmailFolder> rows = granteeFolders();
    rows.add(granteeFolder(20L, ROOT + "/Corbeille", MailFolderView.TYPE_DELEGATED, FolderRole.TRASH, "lrswite"));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(rows);
    when(engine.grant(any(), eq("Corbeille"), anyString(), eq(DelegationPreset.EDITOR), any(), eq(FolderRole.TRASH)))
                                                                                                                   .thenReturn(ace(GRANTEE_MAILBOX, "lrswit"));

    service.setFolderAccess(OWNER, 100L, List.of(change("Corbeille", FolderAccess.EDITOR)));

    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(20L), eq(100L), eq(FolderRole.TRASH), eq("lrswit"), any(Date.class));
  }

  /**
   * On a share written before folders were shared, the save is the owner's consent to
   * share more (PO decision P-4): INBOX is granted again to the preset's letters of
   * today, read back, and the roles are recorded from then on.
   */
  @Test
  void onAnInboxOnlyShareTheSaveIsTheConsentToShareMore() throws Exception {
    EmailDelegation inboxOnly = accepted();
    inboxOnly.setGrantedRoles(null);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(inboxOnly);
    lenient().when(engine.listAcl(any(), eq(INBOX))).thenReturn(List.of(ace(GRANTEE_MAILBOX, "lrswite")));
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any()))
                                                                                                 .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                  MailboxRights.of("lrswite")));
    when(emailDelegationStorage.updateGrantedRights(eq(OWNER), eq(100L), any(), any(), any(), any(), any(), anyString(), any()))
                                                                                                                               .thenReturn(inboxOnly);
    Map<String, Object> written = answerTheFolderGrantsWrite();

    service.setFolderAccess(OWNER, 100L, List.of(change("Sent", FolderAccess.EDITOR)));

    verify(engine).grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any());
    verify(emailDelegationStorage).updateGrantedRights(eq(OWNER),
                                                       eq(100L),
                                                       eq(DelegationPreset.EDITOR),
                                                       eq("lrswite"),
                                                       any(),
                                                       eq(GRANTEE_MAILBOX),
                                                       any(),
                                                       eq("INBOX,SENT"),
                                                       any());
    assertEquals("INBOX,SENT", written.get("grantedRoles"));
  }

  /**
   * A share ended while the server was being asked is not written over: said as not
   * changeable, and the delegate's copies of the folders no longer shared still go.
   */
  @Test
  void aShareEndedMeanwhileIsNotWrittenOver() throws Exception {
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(accepted());
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(granteeFolders());
    when(emailDelegationStorage.updateFolderGrants(eq(OWNER), eq(100L), any(), any(), any())).thenReturn(null);

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.setFolderAccess(OWNER, 100L, List.of(change("Projects", FolderAccess.NONE)))).getMessage());
    verify(emailFolderStorage).deleteFolder(GRANTEE, 11L);
  }

  // ---------------------------------------------------------------------------------
  // Invitation, "Change access", "Extend access"
  // ---------------------------------------------------------------------------------

  /**
   * At invitation (PO decision P-2), a role folder set to NONE is never shared -- not
   * shared and removed a moment later -- and one set to the other preset is granted with
   * it; the choice is recorded as exceptions, and a NONE is not "refused by the server".
   */
  @Test
  void anInvitationLeavesOutARoleFolderSetToNone() throws Exception {
    when(engine.findRoleFolders(any())).thenReturn(roleFolders());
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any()))
                                                                                                 .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                                                  MailboxRights.of("lrswite")));
    Map<FolderRole, FolderAccess> choice = new EnumMap<>(FolderRole.class);
    choice.put(FolderRole.TRASH, FolderAccess.NONE);
    choice.put(FolderRole.SENT, FolderAccess.READER);
    choice.put(FolderRole.ARCHIVE, FolderAccess.EDITOR);

    EmailDelegation invited = service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR, choice);

    verify(engine, never()).grant(any(), eq("Corbeille"), anyString(), any(), any(), any());
    verify(engine).grant(any(), eq("Sent"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any(), eq(FolderRole.SENT));
    verify(engine).grant(any(), eq("Archive"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any(), eq(FolderRole.ARCHIVE));
    verify(engine).grant(any(), eq("Spam"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), any(), eq(FolderRole.JUNK));
    assertEquals("INBOX,SENT,ARCHIVE,JUNK", invited.getGrantedRoles());
    Map<FolderRole, FolderAccess> recorded = new EnumMap<>(FolderRole.class);
    recorded.put(FolderRole.SENT, FolderAccess.READER);
    recorded.put(FolderRole.TRASH, FolderAccess.NONE);
    assertEquals(recorded, invited.getFolderAccess(), "Archive at the preset follows it");
    assertEquals(List.of(), invited.getRolesNotShared(), "left out on purpose, not refused");
  }

  /**
   * A choice a per-mailbox server would ignore is refused before anything is written:
   * the owner asked for a folder to be left out, and this server would share it anyway.
   * Invalid choices are refused too.
   */
  @Test
  void anInvitationRefusesAChoiceTheServerWouldIgnore() throws Exception {
    when(engine.probe(any())).thenReturn(PER_MAILBOX);
    // A server that would take the grant: only the refusal stops it.
    lenient().when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), any(), any()))
             .thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX, MailboxRights.of("lrswit")));

    assertEquals(EmailDelegationService.PER_FOLDER_UNSUPPORTED_MESSAGE,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR, Map.of(FolderRole.TRASH, FolderAccess.NONE)))
                                                                                                                                         .getMessage());
    for (Map<FolderRole, FolderAccess> invalid : List.of(Map.of(FolderRole.DRAFTS, FolderAccess.READER),
                                                         Map.of(FolderRole.SENT, FolderAccess.NONE, FolderRole.DRAFTS, FolderAccess.NONE))) {
      assertEquals(EmailDelegationService.FOLDER_ACCESS_INVALID_MESSAGE,
                   assertThrows(IllegalArgumentException.class, () -> service.invite(OWNER, GRANTEE, DelegationPreset.EDITOR, invalid))
                                                                                                                                      .getMessage());
    }
    verify(engine, never()).grant(any(), anyString(), anyString(), any(), any());
    verify(engine, never()).grant(any(), anyString(), anyString(), any(), any(), any());
  }

  /**
   * "Change access" (PO decision P-5) moves the role folders that follow the preset and
   * leaves the owner's exceptions where they are; an exception the new preset now says
   * anyway follows it again.
   */
  @Test
  void changeAccessMovesTheFoldersThatFollowAndKeepsTheExceptions() throws Exception {
    EmailDelegation share = accepted();
    share.setGrantedRoles("INBOX,SENT,ARCHIVE,TRASH");
    share.setFolderAccess(new EnumMap<>(Map.of(FolderRole.TRASH, FolderAccess.READER, FolderRole.ARCHIVE, FolderAccess.EDITOR)));
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(share);
    when(engine.findRoleFolders(any())).thenReturn(roleFolders());
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                           MailboxRights.of("lrs")));
    when(emailDelegationStorage.updateGrantedRights(eq(OWNER), eq(100L), any(), any(), any(), any(), any(), anyString(), any()))
                                                                                                                               .thenReturn(share);
    Map<String, Object> written = answerTheFolderGrantsWrite();

    service.changePreset(OWNER, 100L, DelegationPreset.READER);

    verify(engine).grant(any(), eq("Sent"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), any(), eq(FolderRole.SENT));
    verify(engine, never()).grant(any(), eq("Corbeille"), anyString(), any(), any(), any());
    verify(engine, never()).grant(any(), eq("Archive"), anyString(), any(), any(), any());
    verify(engine, never()).grant(any(), eq("Projects"), anyString(), any(), any(), any());
    assertEquals(Map.of(FolderRole.ARCHIVE, FolderAccess.EDITOR),
                 written.get("folderAccess"),
                 "Trash at Reader now says what the preset says; Archive keeps its Editor");
  }

  /**
   * "Extend access" never offers again a role folder the owner chose not to share: with
   * nothing else to add there is nothing to extend, and nothing is written.
   */
  @Test
  void extendDoesNotOfferAgainAFolderSetToNone() throws Exception {
    EmailDelegation share = accepted();
    share.setGrantedRoles("INBOX,SENT,ARCHIVE,JUNK");
    share.setFolderAccess(new EnumMap<>(Map.of(FolderRole.TRASH, FolderAccess.NONE)));
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(share);
    when(engine.findRoleFolders(any())).thenReturn(roleFolders());

    assertEquals(EmailDelegationService.NOT_CHANGEABLE_MESSAGE,
                 assertThrows(IllegalArgumentException.class, () -> service.extend(OWNER, 100L)).getMessage());
    verify(engine, never()).grant(any(), anyString(), anyString(), any(), any());
    verify(engine, never()).grant(any(), anyString(), anyString(), any(), any(), any());
  }

  // ---------------------------------------------------------------------------------
  // The owner renames or deletes a shared folder
  // ---------------------------------------------------------------------------------

  /**
   * After a rename, the delegates' copies under the old name go at once, and each preset
   * access the renamed folder or a folder inside it still carries is granted again under
   * the new name (Dovecot hides a renamed folder from the delegate until then); an entry
   * that reads as no preset is never rewritten.
   */
  @Test
  void aRenamedFolderIsGrantedAgainUnderItsNewName() throws Exception {
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(accepted()));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(granteeFolders());
    when(engine.listOwnFolders(any())).thenReturn(List.of(own(INBOX, null),
                                                          own("Clients", null),
                                                          own("Clients/2024", null),
                                                          own("Clients-old", null)));
    when(engine.listAcl(any(), eq("Clients"))).thenReturn(List.of(ace(GRANTEE_MAILBOX, "lrs")));
    when(engine.listAcl(any(), eq("Clients/2024"))).thenReturn(List.of(ace(GRANTEE_MAILBOX, "lrk")));

    service.ownerFolderChanged(OWNER, "Projects", "Clients");

    verify(emailFolderStorage).deleteFolder(GRANTEE, 11L);
    verify(emailFolderStorage).deleteFolder(GRANTEE, 12L);
    verify(emailFolderStorage, never()).deleteFolder(GRANTEE, 13L);
    verify(engine).grant(any(), eq("Clients"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.READER), eq(OWNER_RIGHTS), isNull());
    verify(engine, never()).grant(any(), eq("Clients/2024"), anyString(), any(), any(), any());
    verify(engine, never()).listAcl(any(), eq("Clients-old"));
    verify(emailFolderStorage).markDiscoveryDue(100L);
  }

  /**
   * A rename never widens: an entry read as an Editor whose letters the Editor grant
   * would not write again exactly -- {@code lrswit} on a folder where the grant writes
   * {@code lrswite}, the {@code e} of permanent deletion -- is left as it is; the same
   * letters are written again; and a share made in the mail server's own interface is
   * never rewritten.
   */
  @Test
  void aRenameWritesTheSameLettersOrNothing() throws Exception {
    EmailDelegation serverMade = accepted();
    serverMade.setId(101L);
    serverMade.setGranteeId("carol");
    serverMade.setGranteeMailbox("carol@acme.com");
    serverMade.setOrigin(DelegationOrigin.SERVER);
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(accepted(), serverMade));
    when(engine.listOwnFolders(any())).thenReturn(List.of(own("Clients", null), own("Clients/2024", null)));
    when(engine.listAcl(any(), eq("Clients"))).thenReturn(List.of(ace(GRANTEE_MAILBOX, "lrswit"), ace("carol@acme.com", "lrs")));
    when(engine.listAcl(any(), eq("Clients/2024"))).thenReturn(List.of(ace(GRANTEE_MAILBOX, "lrswite")));
    // The IMAP reading: e beside t still reads as an Editor, lrswit too.
    when(engine.presetOf(any())).thenAnswer(invocation -> {
      String letters = ((MailboxRights) invocation.getArgument(0)).letters();
      return "lrswite".equals(letters) || "lrswit".equals(letters) ? DelegationPreset.EDITOR : DelegationPreset.fromRights(invocation.getArgument(0));
    });

    service.ownerFolderChanged(OWNER, "Projects", "Clients");

    verify(engine, never()).grant(any(), eq("Clients"), anyString(), any(), any(), any());
    verify(engine).grant(any(), eq("Clients/2024"), eq(GRANTEE_MAILBOX), eq(DelegationPreset.EDITOR), eq(OWNER_RIGHTS), isNull());
  }

  /**
   * "Change access" to Reader on a folder that refuses the narrower letters removes the
   * delegate's entry there -- and the delegate's copy of that folder leaves their
   * screens at once, as a folder set to Not shared does, rather than stay readable until
   * their next discovery.
   */
  @Test
  void changeAccessDropsTheCopyOfAFolderWhoseAccessItRemoved() throws Exception {
    EmailDelegation share = accepted();
    share.setGrantedRoles("INBOX,SENT,ARCHIVE");
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(share);
    when(engine.findRoleFolders(any())).thenReturn(roleFolders());
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                           MailboxRights.of("lrs")));
    when(engine.grant(any(), eq("Sent"), anyString(), eq(DelegationPreset.READER), any(), any()))
                                                                                               .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                                  "NO"));
    when(emailDelegationStorage.updateGrantedRights(eq(OWNER), eq(100L), any(), any(), any(), any(), any(), anyString(), any()))
                                                                                                                               .thenReturn(share);
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(granteeFolders());

    service.changePreset(OWNER, 100L, DelegationPreset.READER);

    verify(engine).revoke(any(), eq("Sent"), eq(GRANTEE_MAILBOX));
    verify(emailFolderStorage).deleteFolder(GRANTEE, 14L);
    verify(emailFolderStorage, never()).updateDelegatedRights(eq(GRANTEE), eq(14L), anyLong(), any(), anyString(), any());
    verify(emailFolderStorage, never()).deleteFolder(GRANTEE, 15L);
  }

  /**
   * On a server that lists the owner's folders under INBOX to the delegate without it,
   * the owner's listing -- read anyway after a rename, and by "Change access" when it had
   * to remove a folder's entry -- names the delegate's copy for sure, and it goes at once;
   * a server that cannot be asked still drops what needs no listing.
   */
  @Test
  void theOwnersListingNamesACopyUnderInboxForSure() throws Exception {
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(accepted()));
    List<EmailFolder> rows = granteeFolders();
    rows.add(granteeFolder(19L, ROOT + "/Legacy", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    rows.add(granteeFolder(22L, ROOT + "/Courier", MailFolderView.TYPE_DELEGATED, FolderRole.SENT, "lrswite"));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(rows);
    when(engine.listOwnFolders(any())).thenReturn(List.of(own(INBOX, null), own("INBOX/Kept", null), own("INBOX/Courier", FolderRole.SENT)));

    service.ownerFolderChanged(OWNER, "INBOX/Legacy", "INBOX/Kept");

    verify(emailFolderStorage).deleteFolder(GRANTEE, 19L);

    EmailDelegation share = accepted();
    share.setGrantedRoles("INBOX,SENT");
    Map<FolderRole, String> roles = new EnumMap<>(FolderRole.class);
    roles.put(FolderRole.SENT, "INBOX/Courier");
    share.setOwnerRoleFolders(roles);
    when(emailDelegationStorage.getAsOwner(OWNER, 100L)).thenReturn(share);
    when(engine.findRoleFolders(any())).thenReturn(roles);
    when(engine.grant(any(), eq(INBOX), eq(GRANTEE_MAILBOX), any(), any())).thenReturn(MailboxAce.ofLetters(GRANTEE_MAILBOX,
                                                                                                           MailboxRights.of("lrs")));
    when(engine.grant(any(), eq("INBOX/Courier"), anyString(), eq(DelegationPreset.READER), any(), any()))
                                                                                                        .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                                           "NO"));
    when(emailDelegationStorage.updateGrantedRights(eq(OWNER), eq(100L), any(), any(), any(), any(), any(), anyString(), any()))
                                                                                                                               .thenReturn(share);

    service.changePreset(OWNER, 100L, DelegationPreset.READER);

    verify(emailFolderStorage).deleteFolder(GRANTEE, 22L);

    // A folder that takes the narrower letters is narrowed by the same sure name.
    when(engine.grant(any(), eq("INBOX/Courier"), anyString(), eq(DelegationPreset.READER), any(), any()))
                                                                                                        .thenReturn(ace(GRANTEE_MAILBOX, "lrs"));
    service.changePreset(OWNER, 100L, DelegationPreset.READER);

    verify(emailFolderStorage).updateDelegatedRights(eq(GRANTEE), eq(22L), eq(100L), eq(FolderRole.SENT), eq("lrs"), any(Date.class));
  }

  /**
   * After a delete, the delegates' copies go -- one under INBOX too, named for sure by the
   * owner's listing; no grant is written.
   */
  @Test
  void aDeletedFolderLeavesTheDelegatesScreens() throws Exception {
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of(accepted()));
    List<EmailFolder> rows = granteeFolders();
    rows.add(granteeFolder(19L, ROOT + "/Legacy", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    when(emailFolderStorage.getDelegatedFolders(GRANTEE, 100L)).thenReturn(rows);
    when(engine.listOwnFolders(any())).thenReturn(List.of(own(INBOX, null)));

    service.ownerFolderChanged(OWNER, "Projects", null);
    service.ownerFolderChanged(OWNER, "INBOX/Legacy", null);

    verify(emailFolderStorage).deleteFolder(GRANTEE, 11L);
    verify(emailFolderStorage).deleteFolder(GRANTEE, 19L);
    verify(engine, never()).grant(any(), anyString(), anyString(), any(), any(), any());
    verify(emailFolderStorage, org.mockito.Mockito.atLeastOnce()).markDiscoveryDue(100L);
  }

  /**
   * A mailbox shared with nobody costs nothing -- no session, no server -- and a failure
   * never fails the owner's rename.
   */
  @Test
  void aRenameInAMailboxSharedWithNobodyCostsNothing() throws Exception {
    when(emailDelegationStorage.getGranted(OWNER)).thenReturn(List.of());

    service.ownerFolderChanged(OWNER, "Projects", "Clients");

    verifyNoInteractions(engine, userEmailSettingService);

    when(emailDelegationStorage.getGranted(OWNER)).thenThrow(new IllegalStateException("database down"));
    service.ownerFolderChanged(OWNER, "Projects", "Clients");
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  /**
   * Answers the owner's per-folder write as the storage does, and keeps what it wrote.
   *
   * @return what was written, by column
   */
  private Map<String, Object> answerTheFolderGrantsWrite() {
    Map<String, Object> written = new java.util.HashMap<>();
    lenient().when(emailDelegationStorage.updateFolderGrants(eq(OWNER), eq(100L), any(), any(), any())).thenAnswer(invocation -> {
      written.put("grantedRoles", invocation.getArgument(2));
      written.put("ownerRoleFolders", invocation.getArgument(3));
      written.put("folderAccess", invocation.getArgument(4));
      EmailDelegation row = accepted();
      row.setGrantedRoles(invocation.getArgument(2));
      row.setFolderAccess(invocation.getArgument(4));
      return row;
    });
    return written;
  }

  /**
   * Alice's own folders: INBOX, the four role folders (Trash named in French), Drafts,
   * and a small tree of her own.
   *
   * @return the folders
   */
  private static List<OwnFolder> ownFolders() {
    return List.of(own("Projects-old", null),
                   own("Projects/2024", null),
                   own(INBOX, null),
                   own("Drafts", FolderRole.DRAFTS),
                   own("Projects", null),
                   own("Spam", FolderRole.JUNK),
                   own("Corbeille", FolderRole.TRASH),
                   own("Archive", FolderRole.ARCHIVE),
                   own("Sent", FolderRole.SENT));
  }

  /**
   * The owner's role folders, as the engine finds them.
   *
   * @return the map
   */
  private static Map<FolderRole, String> roleFolders() {
    Map<FolderRole, String> roles = new EnumMap<>(FolderRole.class);
    roles.put(FolderRole.SENT, "Sent");
    roles.put(FolderRole.ARCHIVE, "Archive");
    roles.put(FolderRole.TRASH, "Corbeille");
    roles.put(FolderRole.JUNK, "Spam");
    return roles;
  }

  /**
   * One of the owner's folders.
   *
   * @param name its full name
   * @param role its role
   * @return the folder
   */
  private static OwnFolder own(String name, FolderRole role) {
    return new OwnFolder(name, name.substring(name.lastIndexOf('/') + 1), "/", role);
  }

  /**
   * Bob's registered copies of Alice's mailbox: INBOX, Projects, a folder inside it, a
   * sibling whose name begins the same, and Sent (Editor), Archive (less than Reader) and
   * Spam (Reader) with their recorded letters.
   *
   * @return the rows
   */
  private static List<EmailFolder> granteeFolders() {
    List<EmailFolder> rows = new ArrayList<>();
    rows.add(granteeFolder(10L, ROOT + "/INBOX", MailFolderView.TYPE_DELEGATED_INBOX, null, null));
    rows.add(granteeFolder(11L, ROOT + "/Projects", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    rows.add(granteeFolder(12L, ROOT + "/Projects/2024", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    rows.add(granteeFolder(13L, ROOT + "/Projects-old", MailFolderView.TYPE_DELEGATED, null, "lrs"));
    rows.add(granteeFolder(14L, ROOT + "/Sent", MailFolderView.TYPE_DELEGATED, FolderRole.SENT, "lrswite"));
    rows.add(granteeFolder(15L, ROOT + "/Archive", MailFolderView.TYPE_DELEGATED, FolderRole.ARCHIVE, "lr"));
    rows.add(granteeFolder(16L, ROOT + "/Spam", MailFolderView.TYPE_DELEGATED, FolderRole.JUNK, "lrs"));
    return rows;
  }

  /**
   * One of Bob's registered copies.
   *
   * @param id the row id
   * @param remoteName its name on Bob's session
   * @param type its type
   * @param role its role
   * @param rights Bob's recorded letters on it
   * @return the row
   */
  private static EmailFolder granteeFolder(long id, String remoteName, String type, FolderRole role, String rights) {
    EmailFolder folder = new EmailFolder();
    folder.setId(id);
    folder.setUserId(GRANTEE);
    folder.setRemoteName(remoteName);
    folder.setDelimiter("/");
    folder.setType(type);
    folder.setRole(role);
    folder.setRights(rights);
    folder.setRightsCheckDate(rights == null ? null : new Date(1_000L));
    folder.setDelegationId(100L);
    return folder;
  }

  /**
   * Bob's accepted Editor share of Alice's mailbox, covering INBOX and Sent.
   *
   * @return the row, id 100
   */
  private static EmailDelegation accepted() {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(100L);
    delegation.setGranteeId(GRANTEE);
    delegation.setOwnerId(OWNER);
    delegation.setOwnerMailbox(OWNER_MAILBOX);
    delegation.setGranteeMailbox(GRANTEE_MAILBOX);
    delegation.setConnectorId(CONNECTOR_ID);
    delegation.setRemoteRoot(ROOT);
    delegation.setPreset(DelegationPreset.EDITOR);
    delegation.setRights("lrswite");
    delegation.setStatus(DelegationStatus.ACCEPTED);
    delegation.setOrigin(DelegationOrigin.EXO);
    delegation.setGrantedRoles("INBOX,SENT");
    delegation.setOwnerRoleFolders(roleFolders());
    return delegation;
  }

  /**
   * One ACL entry.
   *
   * @param identifier who
   * @param letters their letters
   * @return the entry
   */
  private static MailboxAce ace(String identifier, String letters) {
    return MailboxAce.ofLetters(identifier, MailboxRights.of(letters));
  }

  /**
   * One change.
   *
   * @param folder the folder
   * @param access the access
   * @return the change
   */
  private static FolderAccessChange change(String folder, FolderAccess access) {
    return new FolderAccessChange(folder, access);
  }

  /**
   * One folder of a list, by name.
   *
   * @param list the list
   * @param name the name
   * @return the entry
   */
  private static DelegationFolder byName(DelegationFolders list, String name) {
    return list.folders().stream().filter(folder -> folder.folder().equals(name)).findFirst().orElseThrow();
  }

  /**
   * A connected setting on connector 7.
   *
   * @param address the mailbox address
   * @return the setting
   */
  private static UserEmailSetting setting(String address) {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    setting.setEmailAddress(address);
    setting.setEmailPassword("secret");
    return setting;
  }
}
