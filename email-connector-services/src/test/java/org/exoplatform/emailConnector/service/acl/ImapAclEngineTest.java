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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import javax.mail.Folder;
import javax.mail.FolderNotFoundException;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.StoreClosedException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.sun.mail.iap.BadCommandException;
import com.sun.mail.iap.CommandFailedException;
import com.sun.mail.iap.ConnectionException;
import com.sun.mail.imap.ACL;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.Rights;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DiscoveredFolder;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.GrantGranularity;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.OwnFolder;
import org.exoplatform.emailConnector.model.SharedMailbox;

/**
 * The RFC 4314 / 2342 engine over a mocked IMAP store handed through a
 * {@link MailboxAclSession}: support is decided by the command's answer and never by
 * the CAPABILITY advertisement (the two servers phase 0 met advertised nothing and
 * answered everything), the Other Users prefix is read from NAMESPACE or, failing
 * that, from the session's own LIST, letters cross by string, a preset is expanded and
 * capped here, and refusals are typed with the server's text kept out of the message.
 */
class ImapAclEngineTest {

  private static final String IDENTIFIER = "bob@acme.com";

  private IMAPStore           store;

  private IMAPFolder          inbox;

  private ImapAclEngine       engine;

  /**
   * A connected IMAP store whose INBOX is a mocked folder.
   */
  @BeforeEach
  void setUp() throws MessagingException {
    store = mock(IMAPStore.class);
    inbox = mock(IMAPFolder.class);
    when(store.getFolder("INBOX")).thenReturn(inbox);
    engine = new ImapAclEngine();
  }

  // ---------------------------------------------------------------------------------
  // Probe: the command is the test
  // ---------------------------------------------------------------------------------

  /**
   * THE phase-0 pin (plan, sections 13.B.9 and 13.C): a server advertising neither
   * {@code ACL} nor {@code NAMESPACE} whose MYRIGHTS answers is SUPPORTED -- this is
   * BlueMind ({@code lrswipkxtea}, no ACL in CAPABILITY) and Stalwart v0.11.8 (neither
   * capability, every command answered). The committed gate reported both as unable
   * to share; reverting it fails this test on {@code supported}.
   */
  @Test
  void probeTreatsAnAnsweredMyRightsAsSupportWithoutTheAclCapability() throws MessagingException {
    when(store.hasCapability("ACL")).thenReturn(false);
    when(store.hasCapability("NAMESPACE")).thenReturn(false);
    when(inbox.myRights()).thenReturn(new Rights("lrswipkxtea"));

    MailboxAclCapabilities capabilities = engine.probe(session());

    assertTrue(capabilities.supported(), "MYRIGHTS answered: the server does ACLs, whatever it advertises");
    assertNull(capabilities.reasonCode());
    assertFalse(capabilities.aclAdvertised(), "the hint records what CAPABILITY said");
    assertFalse(capabilities.namespaceAdvertised());
    assertEquals(GrantGranularity.FOLDER, capabilities.grantGranularity());
    assertFalse(capabilities.serverNotifiesOwner(), "an IMAP server tells the owner nothing");
    assertFalse(capabilities.subscriptionRequired(), "and has no acceptance step");
    verify(inbox).myRights();
  }

  /**
   * ACL advertised is a fast positive: supported without a MYRIGHTS round-trip. A
   * missing NAMESPACE is a hint, not a refusal.
   */
  @Test
  void probeUsesTheAdvertisementOnlyAsAFastPositive() throws MessagingException {
    when(store.hasCapability("ACL")).thenReturn(true);
    when(store.hasCapability("NAMESPACE")).thenReturn(false);

    MailboxAclCapabilities capabilities = engine.probe(session());

    assertTrue(capabilities.supported());
    assertTrue(capabilities.aclAdvertised());
    assertFalse(capabilities.namespaceAdvertised());
    assertNull(capabilities.reasonCode());
    verify(inbox, never()).myRights();
  }

  /**
   * No ACL advertised AND MYRIGHTS refused ({@code BAD} unknown command, or {@code NO}):
   * unsupported, with the reason -- so a preset marked imap on a server without ACLs
   * degrades to the message instead of failing on SETACL.
   */
  @Test
  void probeReportsUnsupportedWhenMyRightsIsRefused() throws MessagingException {
    when(store.hasCapability("ACL")).thenReturn(false);
    when(store.hasCapability("NAMESPACE")).thenReturn(true);
    doThrow(new MessagingException("BAD Unknown command", new BadCommandException("Unknown command"))).when(inbox).myRights();

    MailboxAclCapabilities capabilities = engine.probe(session());

    assertFalse(capabilities.supported());
    assertEquals(MailboxAclException.UNSUPPORTED, capabilities.reasonCode());

    doThrow(new MessagingException("NO", new CommandFailedException("NO MYRIGHTS not permitted"))).when(inbox).myRights();
    assertEquals(MailboxAclException.UNSUPPORTED, engine.probe(session()).reasonCode(), "a NO is a refusal too");
  }

  /**
   * A server that cannot be reached, or drops the line on the command (BlueMind's proxy
   * does that on SETACL), is UNREACHABLE -- never "cannot share".
   */
  @Test
  void probeKeepsAConnectionFailureApartFromARefusal() throws MessagingException {
    when(store.hasCapability("ACL")).thenReturn(false);
    when(store.hasCapability("NAMESPACE")).thenReturn(false);
    doThrow(new StoreClosedException(store)).when(inbox).myRights();
    assertEquals(MailboxAclException.UNREACHABLE, engine.probe(session()).reasonCode());

    doThrow(new MessagingException("dropped", new ConnectionException("* BYE"))).when(inbox).myRights();
    assertEquals(MailboxAclException.UNREACHABLE, engine.probe(session()).reasonCode());

    MailboxAclSession unreachable = new MailboxAclSession(connector(), "bob", IDENTIFIER, () -> {
      throw new MessagingException("Connection refused: imap.acme.com");
    }, null);
    MailboxAclCapabilities capabilities = engine.probe(unreachable);
    assertFalse(capabilities.supported());
    assertEquals(MailboxAclException.UNREACHABLE, capabilities.reasonCode());
  }

  /**
   * A store that is not IMAP has no ACL command to run.
   */
  @Test
  void probeRefusesANonImapStore() {
    Store plain = mock(Store.class);
    MailboxAclCapabilities capabilities = engine.probe(session(plain));

    assertFalse(capabilities.supported());
    assertEquals(MailboxAclException.NOT_IMAP, capabilities.reasonCode());
    assertEquals(MailboxAclException.NOT_IMAP,
                 assertThrows(MailboxAclException.class, () -> engine.myRights(session(plain), "INBOX")).getCode());
  }

  // ---------------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------------

  /**
   * MYRIGHTS is read by letter: a server answering RFC 4314's {@code t} is "can delete
   * messages" here, where the library's DELETE constant would say no.
   */
  @Test
  void myRightsReadsTheLettersTheServerAnswered() throws MessagingException {
    when(inbox.myRights()).thenReturn(new Rights("lrswit"));

    MailboxRights rights = engine.myRights(session(), "INBOX");

    assertEquals("lrswit", rights.letters());
    assertTrue(rights.canDeleteMessages());
    assertFalse(rights.canAdminister());
  }

  /**
   * EXO-90557 -- a MYRIGHTS the connection dropped under is "could not ask", not "no":
   * the periodic re-read revokes a share on a refusal only, and a network hiccup must
   * not cost a grantee their share. A real NO stays a refusal.
   */
  @Test
  void aDroppedConnectionIsNotARefusal() throws MessagingException {
    doThrow(new StoreClosedException(store, "BYE")).when(inbox).myRights();
    assertEquals(MailboxAclException.UNREACHABLE,
                 assertThrows(MailboxAclException.class, () -> engine.myRights(session(), "INBOX")).getCode());

    org.mockito.Mockito.reset(inbox);
    doThrow(new MessagingException("NO", new CommandFailedException("NO"))).when(inbox).myRights();
    assertEquals(MailboxAclException.SERVER_REFUSED,
                 assertThrows(MailboxAclException.class, () -> engine.myRights(session(), "INBOX")).getCode());
  }

  /**
   * Stalwart stores an Editor granted from eXo ({@code lrswit}) with {@code e} beside
   * {@code t}: its GETACL answers {@code tewsirl}, the delegate's MYRIGHTS
   * {@code rlitesw} (both observed on the rig, 2026-09-23). Both read as an Editor, not
   * as custom rights.
   */
  @Test
  void anEditorStoredWithItsCoupledExpungeStillReadsAsAnEditor() throws MessagingException {
    when(inbox.getACL()).thenReturn(new ACL[] { new ACL(IDENTIFIER, new Rights("tewsirl")) });

    assertEquals(DelegationPreset.EDITOR, engine.listAcl(session(), "INBOX").get(0).preset());
    assertEquals(DelegationPreset.EDITOR, engine.presetOf(MailboxRights.of("rlitesw")));
    assertEquals(DelegationPreset.EDITOR, engine.presetOf(MailboxRights.of("lrswit")));
    assertEquals(DelegationPreset.READER, engine.presetOf(MailboxRights.of("lrs")));
  }

  /**
   * Dovecot 2.3.21 answers an Editor granted from eXo ({@code lrswit}) with RFC 4314's
   * virtual {@code d} beside {@code t}: GETACL {@code ilrwtsd}, the delegate's MYRIGHTS
   * {@code lrwstid} (observed on the rig, EXO-90552). Both read as exactly
   * {@code lrswit} -- an Editor without {@code e} -- never as {@code lrswite}.
   */
  @Test
  void aDovecotEditorReadsAsTheLettersGrantedWithoutAnExpunge() throws MessagingException {
    when(inbox.getACL()).thenReturn(new ACL[] { new ACL(IDENTIFIER, new Rights("ilrwtsd")) });
    when(inbox.myRights()).thenReturn(new Rights("lrwstid"));

    MailboxAce entry = engine.listAcl(session(), "INBOX").get(0);
    assertEquals("lrswit", entry.rights().letters());
    assertEquals("lrswit", entry.nativeRights());
    assertEquals(DelegationPreset.EDITOR, entry.preset());
    MailboxRights mine = engine.myRights(session(), "INBOX");
    assertEquals("lrswit", mine.letters());
    assertFalse(mine.canExpunge());
  }

  /**
   * Only what coupling implies is forgiven: a set granting more -- administer, delete
   * the mailbox, expunge without delete-messages -- is never a preset.
   */
  @Test
  void aSetGrantingMoreThanCouplingImpliesIsNeverAPreset() {
    assertEquals(DelegationPreset.CUSTOM, engine.presetOf(MailboxRights.of("lrse")), "e without t");
    assertEquals(DelegationPreset.CUSTOM, engine.presetOf(MailboxRights.of("lrswitea")), "a");
    assertEquals(DelegationPreset.CUSTOM, engine.presetOf(MailboxRights.of("lrswitex")), "x");
    assertEquals(DelegationPreset.CUSTOM, engine.presetOf(MailboxRights.of("lrsw")), "a Reader plus star is neither");
    assertEquals(DelegationPreset.CUSTOM, engine.presetOf(null));
  }

  /**
   * GETACL, every entry by letter, legacy letters folded; the native form is the letters
   * and the preset the exact reading.
   */
  @Test
  void listAclReadsEveryEntry() throws MessagingException {
    when(inbox.getACL()).thenReturn(new ACL[] { new ACL("alice@acme.com", new Rights("lrswipcda")),
        new ACL(IDENTIFIER, new Rights("lrs")) });

    List<MailboxAce> acl = engine.listAcl(session(), "INBOX");

    assertEquals(2, acl.size());
    assertEquals("alice@acme.com", acl.get(0).identifier());
    assertEquals("lrswipkxtea", acl.get(0).rights().letters());
    assertEquals("lrswipkxtea", acl.get(0).nativeRights(), "on IMAP the native form is the letters");
    assertEquals(DelegationPreset.CUSTOM, acl.get(0).preset());
    assertEquals(IDENTIFIER, acl.get(1).identifier());
    assertEquals("lrs", acl.get(1).rights().letters());
    assertEquals(DelegationPreset.READER, acl.get(1).preset());
  }

  /**
   * The exact IMAP reading of a preset, and nothing more tolerant: {@code lrp} is
   * BlueMind's Reader, not this engine's.
   */
  @Test
  void presetOfIsTheExactImapReading() {
    assertEquals(DelegationPreset.READER, engine.presetOf(MailboxRights.of("srl")));
    assertEquals(DelegationPreset.EDITOR, engine.presetOf(MailboxRights.of("lrswit")));
    assertEquals(DelegationPreset.CUSTOM, engine.presetOf(MailboxRights.of("lrp")));
    assertEquals(DelegationPreset.CUSTOM, engine.presetOf(null));
  }

  // ---------------------------------------------------------------------------------
  // Grant and revoke
  // ---------------------------------------------------------------------------------

  /**
   * SETACL carries the preset's letters, capped by the allowlist -- never {@code a},
   * {@code x}, {@code k} or {@code p}, though the owner holds them -- no widening, no
   * constant; the answer is the entry as written. An Editor on INBOX holds {@code e},
   * the right to expunge, since EXO-90548 (PO decision Q-1).
   */
  @Test
  void grantExpandsThePresetAndCapsItByTheAllowlist() throws MessagingException {
    MailboxAce written = engine.grant(session(), "INBOX", IDENTIFIER, DelegationPreset.EDITOR, MailboxRights.of("lrswipkxtea"));

    ArgumentCaptor<ACL> acl = ArgumentCaptor.forClass(ACL.class);
    verify(inbox).addACL(acl.capture());
    assertEquals(IDENTIFIER, acl.getValue().getName());
    // The library renders a Rights alphabetically ("eilrstw"); the letters are what matter.
    assertEquals(MailboxRights.of("lrswite"), MailboxRights.fromRights(acl.getValue().getRights()));
    assertEquals(7, acl.getValue().getRights().getRights().length, "exactly the seven, nothing widened");
    assertEquals("lrswite", written.rights().letters());
    assertEquals("lrswite", written.nativeRights());
    assertEquals(DelegationPreset.EDITOR, written.preset(), "an explicit e reads as Editor");
  }

  /**
   * eXo never grants a right the owner does not hold: an Editor asked of an owner who
   * holds {@code lrsa} is written as {@code lrs}, and read back as a Reader.
   */
  @Test
  void grantNeverGrantsARightTheOwnerDoesNotHold() throws MessagingException {
    MailboxAce written = engine.grant(session(), "INBOX", IDENTIFIER, DelegationPreset.EDITOR, MailboxRights.of("lrsa"));

    ArgumentCaptor<ACL> acl = ArgumentCaptor.forClass(ACL.class);
    verify(inbox).addACL(acl.capture());
    assertEquals(MailboxRights.of("lrs"), MailboxRights.fromRights(acl.getValue().getRights()));
    assertEquals(DelegationPreset.READER, written.preset(), "what was granted, not what was asked");
  }

  /**
   * Nothing readable left after the caps: refused with its code, nothing written.
   */
  @Test
  void grantRefusesWhenNothingReadableIsLeft() throws MessagingException {
    MailboxAclException thrown = assertThrows(MailboxAclException.class,
                                              () -> engine.grant(session(), "INBOX", IDENTIFIER, DelegationPreset.READER, MailboxRights.of("la")));

    assertEquals(MailboxAclException.NOTHING_TO_GRANT, thrown.getCode());
    verify(inbox, never()).addACL(any());
    assertEquals(MailboxAclException.NOTHING_TO_GRANT,
                 assertThrows(MailboxAclException.class,
                              () -> engine.grant(session(), "INBOX", IDENTIFIER, DelegationPreset.CUSTOM, MailboxRights.of("lrswipkxtea"))).getCode(),
                 "CUSTOM expands to nothing");
  }

  /**
   * DELETEACL for the identifier.
   */
  @Test
  void revokeRemovesTheIdentifiersEntry() throws MessagingException {
    engine.revoke(session(), "INBOX", IDENTIFIER);

    verify(inbox).removeACL(IDENTIFIER);
  }

  /**
   * A NO from the server becomes a fixed code; the server's own text -- which names
   * users and paths -- is the exception's detail, never its message.
   */
  @Test
  void aServerRefusalIsTypedAndKeepsTheServerTextOutOfTheMessage() throws MessagingException {
    doThrow(new MessagingException("NO [NOPERM] user bob@acme.com has no admin right on /var/spool/imap/user/alice"))
                                                                                                                       .when(inbox)
                                                                                                                       .addACL(any(ACL.class));

    MailboxAclException thrown = assertThrows(MailboxAclException.class,
                                              () -> engine.grant(session(), "INBOX", IDENTIFIER, DelegationPreset.READER, MailboxRights.of("lrsa")));

    assertEquals(MailboxAclException.SERVER_REFUSED, thrown.getCode());
    assertEquals(MailboxAclException.SERVER_REFUSED, thrown.getMessage());
    assertFalse(thrown.getMessage().contains("bob"), "the message is the code, nothing of the server's text");
    assertNotNull(thrown.getDetail());
    assertTrue(thrown.getDetail().contains("NOPERM"), "the server's text is kept for the DEBUG log");
  }

  /**
   * No acceptance step on IMAP: the two hooks do nothing and open nothing -- a session
   * whose opener would fail is never asked.
   */
  @Test
  void subscribeAndUnsubscribeAreNoOpsThatOpenNothing() {
    MailboxAclSession session = new MailboxAclSession(connector(), "bob", IDENTIFIER, () -> {
      throw new MessagingException("must not be opened");
    }, null);

    engine.subscribe(session, "alice@acme.com");
    engine.unsubscribe(session, "alice@acme.com");

    assertFalse(session.hasOpenStore());
  }

  // ---------------------------------------------------------------------------------
  // Discovery: NAMESPACE first, the session's own LIST second
  // ---------------------------------------------------------------------------------

  /**
   * The Other Users namespace walked: each namespace root lists owners, each owner
   * lists an INBOX child where the server has one.
   */
  @Test
  void listSharedMailboxesWalksTheOtherUsersNamespace() throws MessagingException {
    Folder namespace = folder("Other Users", "Other Users", '/');
    Folder alice = folder("alice", "Other Users/alice", '/');
    Folder aliceInbox = folder("INBOX", "Other Users/alice/INBOX", '/');
    Folder aliceSent = folder("Sent", "Other Users/alice/Sent", '/');
    Folder carol = folder("carol", "Other Users/carol", '/');
    Folder root = folder("", "", '/');
    when(store.getUserNamespaces(isNull())).thenReturn(new Folder[] { namespace });
    when(store.getDefaultFolder()).thenReturn(root);
    when(root.list("Other Users/%")).thenReturn(new Folder[] { alice, carol });
    when(alice.list("%")).thenReturn(new Folder[] { aliceSent, aliceInbox });
    when(carol.list("%")).thenReturn(new Folder[0]);

    List<SharedMailbox> shared = engine.listSharedMailboxes(session());

    assertEquals(2, shared.size());
    assertEquals(new SharedMailbox("alice", "Other Users/alice", "Other Users/alice/INBOX", "/"), shared.get(0));
    assertEquals(new SharedMailbox("carol", "Other Users/carol", "Other Users/carol", "/"), shared.get(1),
                 "no INBOX child: the owner's root is the INBOX (Cyrus without altnamespace)");
    verify(namespace, never()).list(anyString());
  }

  /**
   * THE second phase-0 pin (plan, section 13.C): NAMESPACE answers nothing -- Stalwart
   * v0.11.8 advertised none -- yet the session's own LIST shows
   * {@code Shared Folders/alice@stalwart.local/INBOX}. The prefix is read from that
   * LIST and the share is discovered; the user's own folders ({@code INBOX},
   * {@code Archive}, a {@code Customers/Acme} tree) are not mistaken for one. Reverting
   * the fallback fails this test on the count.
   */
  /**
   * The pin for the defect this replaced, reproduced from the Stalwart rig
   * (2026-09-22). That server does not advertise NAMESPACE and answers the NAMESPACE
   * command anyway -- {@code (("" "/")) (("Shared Folders" "/")) NIL} -- so
   * {@code getUserNamespaces} returns a folder flagged as a namespace and the LIST
   * fallback never runs. Listing THAT folder made the mail library first probe whether
   * it exists, and for a namespace the library appends the separator:
   * {@code LIST "" "Shared Folders/"}, which Stalwart answers with nothing while
   * answering {@code LIST "" "Shared Folders/%"} perfectly. The probe therefore said
   * the namespace did not exist and discovery died on FolderNotFoundException before a
   * single real listing -- the accept button reported "the server refused" on a server
   * that was answering every command correctly.
   * <p>
   * So: the namespace folder is never listed through, and a folder that throws on
   * {@code list} does not stop discovery.
   */
  @Test
  void listSharedMailboxesNeverListsThroughTheNamespaceFolderItself() throws MessagingException {
    Folder namespace = folder("Shared Folders", "Shared Folders", '/');
    Folder root = folder("", "", '/');
    Folder alice = folder("alice@stalwart.local", "Shared Folders/alice@stalwart.local", '/');
    Folder aliceInbox = folder("Inbox", "Shared Folders/alice@stalwart.local/Inbox", '/');
    when(store.getUserNamespaces(isNull())).thenReturn(new Folder[] { namespace });
    when(namespace.list(anyString())).thenThrow(new FolderNotFoundException(namespace, "Shared Folders not found"));
    when(store.getDefaultFolder()).thenReturn(root);
    when(root.list("Shared Folders/%")).thenReturn(new Folder[] { alice });
    when(alice.list("%")).thenReturn(new Folder[] { aliceInbox });

    List<SharedMailbox> shared = engine.listSharedMailboxes(session());

    assertEquals(1, shared.size(), "the share is found although the namespace folder itself refuses to be listed");
    assertEquals(new SharedMailbox("alice@stalwart.local",
                                   "Shared Folders/alice@stalwart.local",
                                   "Shared Folders/alice@stalwart.local/Inbox",
                                   "/"),
                 shared.get(0),
                 "and the owner's INBOX is matched case-insensitively -- Stalwart names it Inbox");
  }

  @Test
  void listSharedMailboxesFallsBackToTheSessionsListWithoutNamespace() throws MessagingException {
    when(store.getUserNamespaces(isNull())).thenReturn(new Folder[0]);
    Folder root = folder("", "", '/');
    Folder ownInbox = folder("INBOX", "INBOX", '/', Folder.HOLDS_MESSAGES | Folder.HOLDS_FOLDERS);
    Folder archive = folder("Archive", "Archive", '/', Folder.HOLDS_MESSAGES);
    Folder customers = folder("Customers", "Customers", '/', Folder.HOLDS_FOLDERS);
    Folder acme = folder("Acme", "Customers/Acme", '/', Folder.HOLDS_MESSAGES);
    Folder sharedRoot = folder("Shared Folders", "Shared Folders", '/', Folder.HOLDS_FOLDERS);
    Folder alice = folder("alice@stalwart.local", "Shared Folders/alice@stalwart.local", '/', Folder.HOLDS_FOLDERS);
    Folder aliceInbox = folder("INBOX", "Shared Folders/alice@stalwart.local/INBOX", '/', Folder.HOLDS_MESSAGES);
    when(store.getDefaultFolder()).thenReturn(root);
    when(root.list("%")).thenReturn(new Folder[] { ownInbox, archive, customers, sharedRoot });
    when(customers.list("%")).thenReturn(new Folder[] { acme });
    when(acme.list("%")).thenReturn(new Folder[0]);
    when(root.list("Shared Folders/%")).thenReturn(new Folder[] { alice });
    when(sharedRoot.list("%")).thenReturn(new Folder[] { alice });
    when(alice.list("%")).thenReturn(new Folder[] { aliceInbox });

    List<SharedMailbox> shared = engine.listSharedMailboxes(session());

    assertEquals(1, shared.size(), "the share is found through the LIST, nothing of the user's own is");
    assertEquals(new SharedMailbox("alice@stalwart.local",
                                   "Shared Folders/alice@stalwart.local",
                                   "Shared Folders/alice@stalwart.local/INBOX",
                                   "/"),
                 shared.get(0));
    verify(ownInbox, never()).list(any());
    verify(archive, never()).list(any());
  }

  /**
   * No Other Users namespace advertised and nothing in the LIST that looks like one:
   * nothing shared, no failure.
   */
  @Test
  void listSharedMailboxesIsEmptyWithoutANamespace() throws MessagingException {
    when(store.getUserNamespaces(isNull())).thenReturn(new Folder[0]);
    Folder root = folder("", "", '/');
    Folder ownInbox = folder("INBOX", "INBOX", '/', Folder.HOLDS_MESSAGES);
    when(store.getDefaultFolder()).thenReturn(root);
    when(root.list("%")).thenReturn(new Folder[] { ownInbox });

    assertTrue(engine.listSharedMailboxes(session()).isEmpty());
  }

  /**
   * The owner is found by the whole identifier first, then by its local part -- the
   * two spellings phase 0 did not record side by side.
   */
  @Test
  void findSharedMailboxMatchesTheWholeIdentifierThenItsLocalPart() throws MessagingException {
    Folder namespace = folder("Other Users", "Other Users", '/');
    Folder alice = folder("alice", "Other Users/alice", '/');
    Folder aliceAtAcme = folder("alice@acme.com", "Other Users/alice@acme.com", '/');
    Folder listRoot = folder("", "", '/');
    when(store.getUserNamespaces(isNull())).thenReturn(new Folder[] { namespace });
    when(store.getDefaultFolder()).thenReturn(listRoot);
    when(listRoot.list("Other Users/%")).thenReturn(new Folder[] { alice, aliceAtAcme });
    when(alice.list("%")).thenReturn(new Folder[0]);
    when(aliceAtAcme.list("%")).thenReturn(new Folder[0]);

    assertEquals("Other Users/alice@acme.com", engine.findSharedMailbox(session(), "Alice@Acme.com").remoteRoot(),
                 "the whole identifier wins over the local part");
    assertEquals("Other Users/alice", engine.findSharedMailbox(session(), "alice@other.org").remoteRoot(),
                 "then the local part");
    assertNull(engine.findSharedMailbox(session(), "nobody@acme.com"));
    assertNull(engine.findSharedMailbox(session(), " "));
  }

  // ---------------------------------------------------------------------------------
  // A shared mailbox's folders (EXO-90548)
  // ---------------------------------------------------------------------------------

  /**
   * The rule a reviewer checks (PO decision Q-1): an Editor holds {@code e} on every
   * folder mail leaves from and never on Trash, where it would be permanent deletion; a
   * Reader holds {@code lrs} everywhere; the owner's own rights cap every one.
   */
  @Test
  void anEditorNeverHoldsExpungeOnTrash() throws MessagingException {
    IMAPFolder trash = mock(IMAPFolder.class);
    IMAPFolder sent = mock(IMAPFolder.class);
    when(store.getFolder("Trash")).thenReturn(trash);
    when(store.getFolder("Sent")).thenReturn(sent);
    MailboxRights owner = MailboxRights.of("lrswipkxtea");

    assertEquals("lrswit", engine.grant(session(), "Trash", IDENTIFIER, DelegationPreset.EDITOR, owner, FolderRole.TRASH).rights().letters());
    assertEquals("lrswite", engine.grant(session(), "Sent", IDENTIFIER, DelegationPreset.EDITOR, owner, FolderRole.SENT).rights().letters());
    assertEquals("lrs", engine.grant(session(), "Trash", IDENTIFIER, DelegationPreset.READER, owner, FolderRole.TRASH).rights().letters());
    assertEquals("lrswit",
                 engine.grant(session(), "Sent", IDENTIFIER, DelegationPreset.EDITOR, MailboxRights.of("lrswit"), FolderRole.SENT)
                       .rights()
                       .letters(),
                 "never more than the owner holds");
    ArgumentCaptor<ACL> onTrash = ArgumentCaptor.forClass(ACL.class);
    verify(trash, times(2)).addACL(onTrash.capture());
    assertFalse(onTrash.getAllValues().stream().anyMatch(acl -> MailboxRights.fromRights(acl.getRights()).letters().contains("e")),
                "no SETACL on Trash ever carries e");
  }

  /**
   * Two locks on {@code e} over Trash, each pinned on its own: the letters an Editor
   * stands for on Trash, and the allowlist a grant on Trash is capped by -- which still
   * holds for an engine whose letters would carry {@code e} there.
   */
  @Test
  void trashIsCappedWithoutExpungeWhateverTheLettersSay() throws MessagingException {
    assertEquals("lrswit", engine.lettersFor(DelegationPreset.EDITOR, FolderRole.TRASH).letters());
    assertEquals("lrswite", engine.lettersFor(DelegationPreset.EDITOR, null).letters());
    assertEquals("lrs", engine.lettersFor(DelegationPreset.READER, FolderRole.SENT).letters());
    ImapAclEngine generous = new ImapAclEngine() {
      @Override
      public MailboxRights lettersFor(DelegationPreset preset, FolderRole role) {
        return MailboxRights.of("lrswite");
      }
    };
    IMAPFolder trash = mock(IMAPFolder.class);
    when(store.getFolder("Trash")).thenReturn(trash);

    assertEquals("lrswit",
                 generous.grant(session(), "Trash", IDENTIFIER, DelegationPreset.EDITOR, MailboxRights.of("lrswipkxtea"), FolderRole.TRASH)
                         .rights()
                         .letters());
  }

  /**
   * The owner's folders by role, on the owner's session: the special-use attribute
   * first, the usual name for a role no attribute names -- exactly, never by substring
   * -- and never a folder under another user's namespace or one that cannot hold mail.
   */
  @Test
  void theOwnersRoleFoldersComeFromTheirAttributesThenTheirNames() throws MessagingException {
    Folder otherUsers = mock(Folder.class);
    when(otherUsers.getFullName()).thenReturn("shared/");
    when(store.getUserNamespaces(null)).thenReturn(new Folder[] { otherUsers });
    when(store.getSharedNamespaces()).thenReturn(new Folder[0]);
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    Folder sent = listed("Sent", "Sent", "\\Sent");
    Folder corbeille = listed("Corbeille", "Corbeille", "\\Trash");
    Folder namedTrash = listed("Trash", "Trash");
    Folder archives = listed("Archives", "Archives");
    Folder spam = listed("Spam", "Spam");
    Folder trashNotes = listed("Trash notes", "Trash notes");
    Folder alicesTrash = listed("Trash", "shared/alice@dovecot.local/Trash", "\\Trash");
    Folder container = folder("Junk", "Junk", '/');
    when(root.list("*")).thenReturn(new Folder[] { alicesTrash, container, namedTrash, sent, corbeille, archives, trashNotes, spam });

    Map<FolderRole, String> roles = engine.findRoleFolders(session());

    assertEquals(Map.of(FolderRole.SENT, "Sent", FolderRole.TRASH, "Corbeille", FolderRole.ARCHIVE, "Archives", FolderRole.JUNK, "Spam"),
                 roles);
  }

  /**
   * EXO-90548, live on Stalwart 0.11.8 -- alice's folders are listed with no
   * SPECIAL-USE attribute at all ({@code Deleted Items}, {@code Drafts}, {@code INBOX},
   * {@code Junk Mail}, {@code Sent Items}): each role is still found by the names the
   * user's own mailbox recognises, "Junk Mail" included (it was missed, so her Spam was
   * never shared), and no Archive is invented where there is none.
   */
  @Test
  void stalwartsUnattributedFoldersResolveToTheirRoles() throws MessagingException {
    when(store.getUserNamespaces(null)).thenReturn(new Folder[0]);
    when(store.getSharedNamespaces()).thenReturn(new Folder[0]);
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    Folder[] alicesFolders = new Folder[] { listed("Deleted Items", "Deleted Items"), listed("Drafts", "Drafts"), listed("INBOX", "INBOX"),
        listed("Junk Mail", "Junk Mail"), listed("Sent Items", "Sent Items") };
    when(root.list("*")).thenReturn(alicesFolders);

    Map<FolderRole, String> roles = engine.findRoleFolders(session());

    assertEquals(Map.of(FolderRole.SENT, "Sent Items", FolderRole.TRASH, "Deleted Items", FolderRole.JUNK, "Junk Mail",
                        FolderRole.DRAFTS, "Drafts"),
                 roles);
  }

  /**
   * EXO-90548 review -- a role is recognised by name only at the top of the owner's
   * mailbox or directly under INBOX: a nested "Clients/Deleted" or "Old/Junk Mail",
   * listed first, is never taken for the Trash or the Spam and shared.
   */
  @Test
  void aNestedFolderNamedLikeARoleIsNeverTakenForIt() throws MessagingException {
    when(store.getUserNamespaces(null)).thenReturn(new Folder[0]);
    when(store.getSharedNamespaces()).thenReturn(new Folder[0]);
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    Folder[] listing = new Folder[] { listed("Deleted", "Clients/Deleted"), listed("Junk Mail", "Old/Junk Mail"),
        listed("Deleted Items", "Deleted Items"), listed("Spam", "INBOX/Spam") };
    when(root.list("*")).thenReturn(listing);

    assertEquals(Map.of(FolderRole.TRASH, "Deleted Items", FolderRole.JUNK, "INBOX/Spam"), engine.findRoleFolders(session()));
  }

  /**
   * EXO-90548 review -- a top-level role folder wins over a same-role child of INBOX,
   * whatever the LIST order: a user's "INBOX/Spam" listed first never beats the server's
   * own "Junk Mail".
   */
  @Test
  void aTopLevelRoleFolderWinsOverAnInboxChild() throws MessagingException {
    when(store.getUserNamespaces(null)).thenReturn(new Folder[0]);
    when(store.getSharedNamespaces()).thenReturn(new Folder[0]);
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    Folder[] listing = new Folder[] { listed("Spam", "INBOX/Spam"), listed("Junk Mail", "Junk Mail") };
    when(root.list("*")).thenReturn(listing);

    assertEquals(Map.of(FolderRole.JUNK, "Junk Mail"), engine.findRoleFolders(session()));
  }

  /**
   * EXO-90556 -- the owner's own folders for the per-folder list, from the one listing
   * the roles are read from: each with its separator and the role {@code findRoleFolders}
   * gives it -- the attribute, else the usual name at the top -- a nested folder named
   * like a role with none; never a folder under another user's namespace or one that
   * cannot hold mail.
   */
  @Test
  void theOwnersOwnFoldersAreListedWithTheRolesTheGrantGivesThem() throws MessagingException {
    Folder otherUsers = mock(Folder.class);
    when(otherUsers.getFullName()).thenReturn("shared/");
    when(store.getUserNamespaces(null)).thenReturn(new Folder[] { otherUsers });
    when(store.getSharedNamespaces()).thenReturn(new Folder[0]);
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    Folder[] listing = new Folder[] { listed("INBOX", "INBOX"), listed("Corbeille", "Corbeille", "\\Trash"), listed("Spam", "Spam"),
        listed("Deleted", "Clients/Deleted"), listed("Clients", "Clients"), listed("Trash", "shared/alice@dovecot.local/Trash", "\\Trash"),
        folder("Junk", "Junk", '/') };
    when(root.list("*")).thenReturn(listing);

    List<OwnFolder> own = engine.listOwnFolders(session());

    assertEquals(List.of(new OwnFolder("INBOX", "INBOX", "/", null),
                         new OwnFolder("Corbeille", "Corbeille", "/", FolderRole.TRASH),
                         new OwnFolder("Spam", "Spam", "/", FolderRole.JUNK),
                         new OwnFolder("Clients/Deleted", "Deleted", "/", null),
                         new OwnFolder("Clients", "Clients", "/", null)),
                 own);
    verify(root, times(1)).list("*");
  }

  /**
   * EXO-90556 -- every folder the server calls Drafts is Drafts in the owner's list, not
   * only the one the role goes to, so none is ever offered to share.
   */
  @Test
  void everyDraftsFolderIsDraftsNotOnlyTheFirst() throws MessagingException {
    when(store.getUserNamespaces(null)).thenReturn(new Folder[0]);
    when(store.getSharedNamespaces()).thenReturn(new Folder[0]);
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    when(root.list("%")).thenReturn(new Folder[0]);
    Folder[] listing = new Folder[] { listed("Drafts", "Drafts", "\\Drafts"), listed("Brouillons", "Old/Brouillons", "\\Drafts"),
        listed("Drafts", "Clients/Drafts") };
    when(root.list("*")).thenReturn(listing);

    List<OwnFolder> own = engine.listOwnFolders(session());

    assertEquals(List.of(FolderRole.DRAFTS, FolderRole.DRAFTS), own.stream().limit(2).map(OwnFolder::role).toList());
    assertNull(own.get(2).role(), "a folder named so, nested, is the owner's own");
  }

  /**
   * "Remove access" finds every folder of the owner whose ACL names the grantee, however
   * the identifier is cased, and skips a folder whose ACL cannot be read.
   */
  @Test
  void foldersHoldingNamesEveryFolderWhoseAclListsTheGrantee() throws MessagingException {
    when(store.getUserNamespaces(null)).thenReturn(new Folder[0]);
    when(store.getSharedNamespaces()).thenReturn(new Folder[0]);
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    IMAPFolder listedInbox = (IMAPFolder) listed("INBOX", "INBOX");
    IMAPFolder sent = (IMAPFolder) listed("Sent", "Sent");
    IMAPFolder projects = (IMAPFolder) listed("Projects", "Projects");
    IMAPFolder locked = (IMAPFolder) listed("Locked", "Locked");
    when(root.list("%")).thenReturn(new Folder[0]);
    when(root.list("*")).thenReturn(new Folder[] { listedInbox, sent, projects, locked });
    when(listedInbox.getACL()).thenReturn(new ACL[] { new ACL("BOB@acme.com", new Rights("lrs")) });
    when(sent.getACL()).thenReturn(new ACL[] { new ACL("carol@acme.com", new Rights("lrs")) });
    when(projects.getACL()).thenReturn(new ACL[] { new ACL(IDENTIFIER, new Rights("lr")) });
    when(locked.getACL()).thenThrow(new MessagingException("NO"));

    assertEquals(List.of("INBOX", "Projects"), engine.foldersHolding(session(), IDENTIFIER));
  }

  /**
   * EXO-90548 -- a shared mailbox's folders are listed by pattern from the default folder
   * under its root, each with the attributes and selectability the listing carries; a
   * lost connection is UNREACHABLE, not a refusal.
   */
  @Test
  void theFoldersUnderASharedRootAreListedByPattern() throws MessagingException {
    Folder root = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(root);
    Folder sent = listed("Sent", "shared/alice/Sent");
    Folder container = listed("Projects", "shared/alice/Projects", "\\Noselect");
    when(root.list("shared/alice/*")).thenReturn(new Folder[] { sent, container });

    List<DiscoveredFolder> folders = engine.listFoldersUnder(session(), "shared/alice/", "/");

    assertEquals(List.of("shared/alice/Sent", "shared/alice/Projects"), folders.stream().map(DiscoveredFolder::fullName).toList());
    assertTrue(folders.get(0).selectable());
    assertFalse(folders.get(1).selectable());
    assertEquals("Sent", folders.get(0).displayName());

    when(root.list("shared/alice/*")).thenThrow(new StoreClosedException(store, "gone"));
    assertEquals(MailboxAclException.UNREACHABLE,
                 assertThrows(MailboxAclException.class, () -> engine.listFoldersUnder(session(), "shared/alice", "/")).getCode());
  }

  /**
   * A mocked folder the listing shows, that holds mail, with its LIST attributes.
   *
   * @param name the last segment
   * @param fullName the full name
   * @param attributes the attributes
   * @return the folder
   * @throws MessagingException never
   */
  private Folder listed(String name, String fullName, String... attributes) throws MessagingException {
    Folder folder = folder(name, fullName, '/', Folder.HOLDS_MESSAGES);
    lenient().when(((IMAPFolder) folder).getAttributes()).thenReturn(attributes);
    return folder;
  }

  /**
   * A session over the mocked IMAP store.
   *
   * @return the session
   */
  private MailboxAclSession session() {
    return session(store);
  }

  /**
   * A session over a store.
   *
   * @param over the store the opener answers
   * @return the session
   */
  private MailboxAclSession session(Store over) {
    return new MailboxAclSession(connector(), "bob", IDENTIFIER, () -> over, null);
  }

  /**
   * @return a connector preset
   */
  private EmailConnector connector() {
    EmailConnector connector = new EmailConnector();
    connector.setId(7L);
    return connector;
  }

  /**
   * A mocked listed folder that can hold nothing (a container).
   *
   * @param name the last segment
   * @param fullName the full name
   * @param separator the delimiter
   * @return the folder
   * @throws MessagingException never
   */
  private Folder folder(String name, String fullName, char separator) throws MessagingException {
    return folder(name, fullName, separator, Folder.HOLDS_FOLDERS);
  }

  /**
   * A mocked listed folder.
   *
   * @param name the last segment
   * @param fullName the full name
   * @param separator the delimiter
   * @param type the LIST type bits
   * @return the folder
   * @throws MessagingException never
   */
  private Folder folder(String name, String fullName, char separator, int type) throws MessagingException {
    Folder folder = mock(IMAPFolder.class);
    when(folder.getName()).thenReturn(name);
    when(folder.getFullName()).thenReturn(fullName);
    when(folder.getSeparator()).thenReturn(separator);
    when(folder.getType()).thenReturn(type);
    return folder;
  }
}
