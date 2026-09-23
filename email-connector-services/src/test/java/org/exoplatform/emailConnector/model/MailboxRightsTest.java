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
package org.exoplatform.emailConnector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.sun.mail.imap.Rights;

/**
 * The letter-by-letter reading of IMAP rights, pinned against the mail library the
 * add-on resolves: {@code com.sun.mail:javax.mail:1.6.2} exposes the RFC 2086 constant
 * set, and a check written through {@link Rights.Right#DELETE} ({@code d}) is false on a
 * server that answers RFC 4314's {@code t}. Every affordance here is read from the
 * letters, and the two legacy letters fold into their 4314 pairs -- from an RFC 2086
 * server only; from an RFC 4314 one they are virtual and dropped.
 */
class MailboxRightsTest {

  /**
   * The trap itself, documented as a fact about the library rather than assumed: the
   * constants are {@code c} and {@code d}, and {@code contains(DELETE)} does not see
   * {@code t}. If a future library upgrade changes this, the test says so and the
   * mapping can be revisited.
   */
  @Test
  void theLibraryConstantsAreTheRfc2086Letters() {
    assertEquals("c", Rights.Right.CREATE.toString());
    assertEquals("d", Rights.Right.DELETE.toString());
    Rights modernServerAnswer = new Rights("lrst");
    assertFalse(modernServerAnswer.contains(Rights.Right.DELETE), "the library reads a 4314 't' as not-'d'");
    assertEquals("lrst", modernServerAnswer.toString(), "but the letters survive on the wire");
  }

  /**
   * THE regression pin: a server answering {@code t} (BlueMind's Cyrus, Stalwart) is read
   * as "can delete messages", which a mapping through {@code Rights.Right.DELETE} would
   * deny. Fails if anyone reintroduces the constants in {@link MailboxRights#fromRights}.
   */
  @Test
  void aServerAnsweringTheRfc4314LettersIsReadByLetter() {
    MailboxRights rights = MailboxRights.fromRights(new Rights("lrswit"));
    assertTrue(rights.canDeleteMessages(), "t is delete-messages, whatever the library's DELETE constant says");
    assertTrue(rights.canInsert());
    assertTrue(rights.canWriteFlags());
    assertFalse(rights.canExpunge(), "t without e: no expunge");
    assertFalse(rights.canDeleteMailbox());
    assertFalse(rights.canCreateMailbox());
    assertFalse(rights.canAdminister());

    MailboxRights modern = MailboxRights.fromRights(new Rights("lrkxte"));
    assertTrue(modern.canCreateMailbox(), "k");
    assertTrue(modern.canDeleteMailbox(), "x");
    assertTrue(modern.canDeleteMessages(), "t");
    assertTrue(modern.canExpunge(), "e");
  }

  /**
   * A server still answering the RFC 2086 letters is read as the pairs RFC 4314 section
   * 2.1.1 defines: {@code c} is {@code k}+{@code x}, {@code d} is {@code t}+{@code e}.
   */
  @Test
  void theLegacyLettersFoldIntoTheirRfc4314Pairs() {
    MailboxRights legacy = MailboxRights.fromRights(new Rights("lrcd"));
    assertTrue(legacy.canCreateMailbox());
    assertTrue(legacy.canDeleteMailbox());
    assertTrue(legacy.canDeleteMessages());
    assertTrue(legacy.canExpunge());
    assertEquals("lrkxte", legacy.letters(), "rendered in 4314 letters, canonical order");
    assertTrue(legacy.has('c'), "and asked the old way, both halves answer");
    assertTrue(legacy.has('d'));
    assertFalse(MailboxRights.of("t").has('d'), "t alone is not d");
  }

  /**
   * THE Dovecot pin (EXO-90552): a server speaking RFC 4314 reports {@code c} and
   * {@code d} as section 2.1.1's virtual rights, present as soon as <b>one</b> member is
   * set. Dovecot 2.3.21 answers an Editor granted {@code lrswit} as {@code ilrwtsd}
   * (GETACL) and {@code lrwstid} (MYRIGHTS), and {@code lrswitek} as {@code keilrwtscd}
   * (all observed on the rig, 2026-09-23). Those virtual letters say nothing the members
   * do not, and are dropped -- folded, the {@code d} read as an {@code e} nobody granted.
   */
  @Test
  void theVirtualLettersOfAnRfc4314ServerAreDroppedNotFolded() {
    MailboxRights editor = MailboxRights.fromRights(new Rights("ilrwtsd"));
    assertEquals("lrswit", editor.letters());
    assertFalse(editor.canExpunge(), "Dovecot's d beside t alone is not an e");
    assertEquals("lrswit", MailboxRights.fromRights(new Rights("lrwstid")).letters(), "MYRIGHTS of that Editor");
    assertEquals("lrswite", MailboxRights.fromRights(new Rights("eilrwtsd")).letters(), "an Editor with e");
    MailboxRights createOnly = MailboxRights.fromRights(new Rights("keilrwtscd"));
    assertEquals("lrswikte", createOnly.letters());
    assertFalse(createOnly.canDeleteMailbox(), "Dovecot's c beside k alone is not an x");
    assertEquals("lrswipkxtea", MailboxRights.fromRights(new Rights("lrwstipekxacd")).letters(), "the owner");
    assertEquals("lrs", MailboxRights.of("lrs").letters(), "a Reader carries no virtual letter");
    assertEquals("lrsx", MailboxRights.of("lrsxd").letters(), "x is a member of d (RFC 4314's t+e+x grouping)");
    assertEquals("lrsx", MailboxRights.of("lrsxc").letters(), "x is a member of c");
  }

  /**
   * Letters render in one order whatever order they came in, so equal sets compare
   * equal and the stored column is stable.
   */
  @Test
  void lettersAreNormalisedToOneCanonicalOrder() {
    assertEquals("lrswit", MailboxRights.of("tiwsrl").letters());
    assertEquals("lrswit", MailboxRights.of("l r s w i t").letters());
    assertEquals("lrswit", MailboxRights.of("llrrsswwiitt").letters());
    assertEquals(MailboxRights.of("tiwsrl"), MailboxRights.of("lrswit"));
    assertEquals("", MailboxRights.of(null).letters());
    assertEquals("", MailboxRights.fromRights(null).letters());
  }

  /**
   * A letter this class does not know (RFC 4314 lets a server define digits) is kept
   * for display and unlocks nothing.
   */
  @Test
  void unknownLettersAreKeptButGrantNothing() {
    MailboxRights rights = MailboxRights.of("lr0");
    assertEquals("lr0", rights.letters());
    assertTrue(rights.has('0'));
    Map<String, Boolean> affordances = rights.affordances();
    assertEquals(2, affordances.values().stream().filter(Boolean::booleanValue).count(), "browse and read only");
  }

  /**
   * The grant pipeline's two caps: the allowlist never lets {@code a x e p k} through,
   * and the owner's own rights cap what remains.
   */
  @Test
  void theAllowlistAndTheIntersectionCapAGrant() {
    MailboxRights everything = MailboxRights.of("lrswipkxtea");
    assertEquals("lrswit", everything.intersect(MailboxRights.GRANTABLE).letters(), "never a, x, e, p, k");
    assertEquals("lrs", DelegationPreset.EDITOR.rights().intersect(MailboxRights.of("lrsa")).letters(),
                 "an owner who cannot insert or delete cannot grant it");
    assertEquals("", DelegationPreset.READER.rights().intersect(MailboxRights.NONE).letters());
    assertTrue(everything.covers(MailboxRights.GRANTABLE));
    assertFalse(MailboxRights.of("lrs").covers(DelegationPreset.EDITOR.rights()));
  }

  /**
   * The affordance table of the plan (section 7.5), letter by letter.
   */
  @Test
  void eachLetterUnlocksItsControl() {
    Map<String, Boolean> reader = DelegationPreset.READER.rights().affordances();
    assertTrue(reader.get("browse"));
    assertTrue(reader.get("read"));
    assertTrue(reader.get("markRead"));
    assertFalse(reader.get("star"));
    assertFalse(reader.get("moveTarget"));
    assertFalse(reader.get("delete"));
    assertFalse(reader.get("expunge"));
    assertFalse(reader.get("administer"));

    Map<String, Boolean> editor = DelegationPreset.EDITOR.rights().affordances();
    assertTrue(editor.get("star"));
    assertTrue(editor.get("moveTarget"));
    assertTrue(editor.get("delete"));
    assertFalse(editor.get("expunge"));
    assertFalse(editor.get("createFolder"));
    assertFalse(editor.get("deleteFolder"));
    assertFalse(editor.get("administer"));
  }

  /**
   * Observed letters map back to a preset only when they match exactly; anything else
   * is CUSTOM and not grantable from eXo.
   */
  @Test
  void presetsAreRecognisedLetterForLetter() {
    assertEquals(DelegationPreset.READER, DelegationPreset.fromRights(MailboxRights.of("srl")));
    assertEquals(DelegationPreset.EDITOR, DelegationPreset.fromRights(MailboxRights.of("lrswit")));
    assertEquals(DelegationPreset.CUSTOM, DelegationPreset.fromRights(MailboxRights.of("lrswite")));
    assertEquals(DelegationPreset.CUSTOM, DelegationPreset.fromRights(MailboxRights.of("lr")));
    assertEquals(DelegationPreset.CUSTOM, DelegationPreset.fromRights(null));
    assertFalse(DelegationPreset.CUSTOM.isGrantable());
    assertTrue(DelegationPreset.READER.isGrantable());
  }

  /**
   * EXO-90548 -- taking mail out of a folder is offered with t AND e: without e a server
   * keeps the original (silently, on Dovecot).
   */
  @Test
  void movingMailOutNeedsDeleteAndExpunge() {
    assertTrue(MailboxRights.of("lrswite").affordances().get("moveOut"));
    assertFalse(MailboxRights.of("lrswit").affordances().get("moveOut"));
    assertTrue(MailboxRights.of("lrswit").affordances().get("delete"), "the flag alone is still said as it is");
  }

  /**
   * EXO-90556, live on Stalwart 0.11.8 -- a Reader granted {@code lrs} reads back
   * {@code wsrl} (GETACL) and {@code rlsw} (MYRIGHTS): the letters stay the server's, and
   * the view eXo acts on for a delegate drops that coupled {@code w}, so such a Reader
   * never stars; a {@code w} beside another write right (an Editor, {@code rlitesw}) or
   * without {@code s} is a real one.
   */
  @Test
  void aWriteCoupledWithKeepSeenAloneIsNeverActedOnAsAWrite() {
    assertEquals("lrsw", MailboxRights.of("wsrl").letters(), "the server's letters, faithfully");
    assertTrue(MailboxRights.of("rlsw").hasCoupledWrite());
    assertEquals("lrs", MailboxRights.of("rlsw").withoutCoupledWrite().letters());
    assertFalse(MailboxRights.of("rlsw").affordances().get("star"));
    assertTrue(MailboxRights.of("rlsw").withoutCoupledWrite().canKeepSeen());
    assertFalse(MailboxRights.of("rlitesw").hasCoupledWrite());
    assertEquals("lrswite", MailboxRights.of("rlitesw").withoutCoupledWrite().letters());
    assertTrue(MailboxRights.of("rlitesw").affordances().get("star"));
    assertTrue(MailboxRights.of("lrw").affordances().get("star"), "no s: a real w");
    assertFalse(MailboxRights.of("lrswi").hasCoupledWrite());
  }
}
