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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Properties;

import javax.mail.Folder;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import com.sun.mail.imap.ACL;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.Rights;

import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SharedMailbox;

/**
 * {@link ImapAclEngine} against a <b>real Dovecot</b> -- the slice 2.4 certification
 * (EXO-90552), not part of the unit suite. It runs only with {@code -Ddovecot.it=true},
 * against the rig documented in {@code ~/eXo/phase2-dovecot/README.md} (Dovecot 2.3.21,
 * ACL plugin, {@code shared/%%u/} namespace, users {@code alice@dovecot.local} /
 * {@code bob@dovecot.local}):
 *
 * <pre>
 * set -a; . &lt;passwords file&gt;; set +a   # ALICE_PASSWORD, BOB_PASSWORD
 * mvn -o test -pl email-connector-services -Ddovecot.it=true -Dtest=ImapAclEngineDovecotLiveTest
 * </pre>
 *
 * Host and port default to {@code 127.0.0.1:10993} ({@code -Ddovecot.host},
 * {@code -Ddovecot.port}); passwords are read from the environment only. Every step
 * opens a <b>fresh</b> session: Dovecot's vfile backend caches a session's ACL for up to
 * 30 seconds once a mailbox was opened, so a grant change is only certain to be seen by a
 * session opened after it (observed on the rig). The test leaves alice's ACLs as it
 * found them (no entry for bob).
 */
@EnabledIfSystemProperty(named = "dovecot.it", matches = "true")
class ImapAclEngineDovecotLiveTest {

  private static final String ALICE  = "alice@dovecot.local";

  private static final String BOB    = "bob@dovecot.local";

  /** The owner's INBOX as Dovecot lists it to a delegate: the owner's root itself. */
  private static final String SHARED = "shared/" + ALICE;

  private final ImapAclEngine engine = new ImapAclEngine();

  @BeforeEach
  @AfterEach
  void revokeEverythingBobHolds() {
    try (MailboxAclSession alice = session(ALICE, "ALICE_PASSWORD")) {
      for (String folder : List.of("INBOX", "Sent", "Archive", "Trash", "Junk")) {
        engine.revoke(alice, folder, BOB);
      }
    }
  }

  /**
   * Dovecot advertises both capabilities, answers NAMESPACE with {@code shared/}, and the
   * owner holds every right, {@code a} included: {@code lrwstipekxacd} on the wire.
   */
  @Test
  void probeAndTheOwnersRights() {
    try (MailboxAclSession alice = session(ALICE, "ALICE_PASSWORD")) {
      MailboxAclCapabilities capabilities = engine.probe(alice);
      assertTrue(capabilities.supported());
      MailboxRights owner = engine.myRights(alice, "INBOX");
      assertEquals("lrswipkxtea", owner.letters(), "lrwstipekxacd read by letter, c and d dropped");
      assertTrue(owner.canAdminister());
    }
  }

  /**
   * The Reader and the Editor, granted from eXo, read back on both sides as what was
   * granted -- in particular an Editor is {@code lrswit} and holds no {@code e}, although
   * Dovecot answers {@code ilrwtsd} (GETACL) and {@code lrwstid} (MYRIGHTS): its
   * {@code d} is RFC 4314's virtual right, present because {@code t} is.
   */
  @Test
  void readerThenEditorGrantedFromExoReadBackAsGranted() throws MessagingException {
    MailboxRights ownerRights;
    try (MailboxAclSession alice = session(ALICE, "ALICE_PASSWORD")) {
      ownerRights = engine.myRights(alice, "INBOX");
      MailboxAce written = engine.grant(alice, "INBOX", BOB, DelegationPreset.READER, ownerRights);
      assertEquals("lrs", written.rights().letters());
      MailboxAce read = entryOf(engine.listAcl(alice, "INBOX"), BOB);
      assertEquals("lrs", read.rights().letters());
      assertEquals(DelegationPreset.READER, read.preset());
    }
    try (MailboxAclSession bob = session(BOB, "BOB_PASSWORD")) {
      SharedMailbox shared = engine.findSharedMailbox(bob, ALICE);
      assertNotNull(shared, "alice's mailbox is found under the shared/ namespace");
      assertEquals(ALICE, shared.ownerIdentifier(), "the namespace segment is the owner's full address");
      assertEquals(SHARED, shared.remoteRoot());
      assertEquals(SHARED, shared.inboxName(), "Dovecot lists no INBOX child: the root is the INBOX");
      assertEquals("/", shared.delimiter());
      assertEquals(shared, engine.findSharedMailbox(bob, "ALICE@dovecot.local"), "matched case-insensitively");
      MailboxRights mine = engine.myRights(bob, shared.inboxName());
      assertEquals("lrs", mine.letters());
      assertEquals(DelegationPreset.READER, engine.presetOf(mine));
      Folder inbox = bob.store().getFolder(shared.inboxName());
      inbox.open(Folder.READ_WRITE);
      assertTrue(inbox.getMessageCount() > 0, "the root name opens alice's INBOX for the sync");
      inbox.close(false);
    }
    try (MailboxAclSession alice = session(ALICE, "ALICE_PASSWORD")) {
      engine.grant(alice, "INBOX", BOB, DelegationPreset.EDITOR, ownerRights);
      MailboxAce read = entryOf(engine.listAcl(alice, "INBOX"), BOB);
      assertEquals("lrswit", read.rights().letters(), "GETACL ilrwtsd is lrswit, not lrswite");
      assertFalse(read.rights().canExpunge());
      assertEquals(DelegationPreset.EDITOR, read.preset());
    }
    try (MailboxAclSession bob = session(BOB, "BOB_PASSWORD")) {
      MailboxRights mine = engine.myRights(bob, SHARED);
      assertEquals("lrswit", mine.letters(), "MYRIGHTS lrwstid is lrswit, not lrswite");
      assertFalse(mine.canExpunge(), "an Editor without e cannot finish a move on Dovecot");
      assertEquals(DelegationPreset.EDITOR, engine.presetOf(mine));
    }
  }

  /**
   * Phase 2's per-folder letters (Q-1): the owner's session writes {@code lrswite} on
   * INBOX and {@code lrswit} on Trash; both read back as an Editor, with {@code e}
   * exactly where it was written. Written through the library, since
   * {@link MailboxRights#GRANTABLE} does not hold {@code e} in phase 1.
   */
  @Test
  void perFolderEditorLettersWithAndWithoutExpunge() throws MessagingException {
    try (MailboxAclSession alice = session(ALICE, "ALICE_PASSWORD")) {
      ((IMAPFolder) alice.store().getFolder("INBOX")).addACL(new ACL(BOB, new Rights("lrswite")));
      ((IMAPFolder) alice.store().getFolder("Trash")).addACL(new ACL(BOB, new Rights("lrswit")));
      MailboxAce inbox = entryOf(engine.listAcl(alice, "INBOX"), BOB);
      MailboxAce trash = entryOf(engine.listAcl(alice, "Trash"), BOB);
      assertEquals("lrswite", inbox.rights().letters());
      assertEquals(DelegationPreset.EDITOR, inbox.preset(), "e beside t is coupling, still an Editor");
      assertEquals("lrswit", trash.rights().letters());
    }
    try (MailboxAclSession bob = session(BOB, "BOB_PASSWORD")) {
      assertTrue(engine.myRights(bob, SHARED).canExpunge());
      assertFalse(engine.myRights(bob, SHARED + "/Trash").canExpunge());
      List<SharedMailbox> all = engine.listSharedMailboxes(bob);
      assertEquals(1, all.size());
      assertEquals(SHARED, all.get(0).inboxName());
    }
  }

  /**
   * Revocation removes the entry; a fresh delegate session then finds no shared mailbox
   * at all (Dovecot drops the owner from the shared dictionary).
   */
  @Test
  void revokeLeavesNothingForTheDelegate() {
    try (MailboxAclSession alice = session(ALICE, "ALICE_PASSWORD")) {
      engine.grant(alice, "INBOX", BOB, DelegationPreset.READER, engine.myRights(alice, "INBOX"));
      engine.revoke(alice, "INBOX", BOB);
      assertNull(entryOf(engine.listAcl(alice, "INBOX"), BOB));
    }
    try (MailboxAclSession bob = session(BOB, "BOB_PASSWORD")) {
      assertNull(engine.findSharedMailbox(bob, ALICE));
    }
  }

  /**
   * @param entries the entries
   * @param identifier the identifier
   * @return the identifier's entry, or null
   */
  private static MailboxAce entryOf(List<MailboxAce> entries, String identifier) {
    return entries.stream().filter(entry -> identifier.equals(entry.identifier())).findFirst().orElse(null);
  }

  /**
   * A session on the rig, opened on demand, trusting the rig's self-signed certificate.
   *
   * @param user the login
   * @param passwordVariable the environment variable holding the password
   * @return the session
   */
  private static MailboxAclSession session(String user, String passwordVariable) {
    String password = System.getenv(passwordVariable);
    assertNotNull(password, passwordVariable + " must be set (see the rig README)");
    String host = System.getProperty("dovecot.host", "127.0.0.1");
    int port = Integer.parseInt(System.getProperty("dovecot.port", "10993"));
    return new MailboxAclSession(null, user, user, () -> {
      Properties properties = new Properties();
      properties.put("mail.imaps.ssl.trust", "*");
      properties.put("mail.imaps.ssl.checkserveridentity", "false");
      Store store = Session.getInstance(properties).getStore("imaps");
      store.connect(host, port, user, password);
      return store;
    }, null);
  }
}
