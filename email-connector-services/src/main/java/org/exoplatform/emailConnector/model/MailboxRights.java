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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.sun.mail.imap.Rights;

/**
 * The rights a user holds on one IMAP mailbox, as RFC 4314 spells them: one letter per
 * right, and one affordance per letter for the interface (the table of the delegation
 * plan, section 7.5). This class is the ONE place in the add-on that reads an IMAP
 * rights string, and it reads it <b>by letter</b>, never through the mail library's
 * {@link Rights.Right} constants.
 * <p>
 * Why by letter. The library this add-on resolves ({@code com.sun.mail:javax.mail:1.6.2})
 * exposes the <b>RFC 2086</b> constant set: {@code CREATE} is {@code c}, {@code DELETE}
 * is {@code d}, and there is no constant at all for the RFC 4314 letters {@code k x t e}
 * that split them. {@link Rights.Right#getInstance(char)} accepts any letter, so a
 * server's 4314 answer parses into a {@link Rights} without complaint, but a check
 * written as {@code rights.contains(Rights.Right.DELETE)} asks for {@code d} and is
 * <b>false</b> against a server that answers {@code t} -- which is what BlueMind's Cyrus
 * and Stalwart answer. Reading the letters directly, and folding the two RFC 2086
 * letters into their RFC 4314 pairs ({@code c} is {@code k}+{@code x}, {@code d} is
 * {@code t}+{@code e}, RFC 4314 section 2.1.1), is what makes the same code right on a
 * server of either generation. {@code MailboxRightsTest} pins this against the library.
 * <p>
 * Immutable. Letters this class does not know (RFC 4314 lets a server define digits)
 * are kept for display and grant nothing.
 */
public final class MailboxRights {

  /** {@code l} -- the mailbox is visible to LIST and SUBSCRIBE. */
  public static final char           LOOKUP          = 'l';

  /** {@code r} -- SELECT the mailbox, FETCH and SEARCH its messages. */
  public static final char           READ            = 'r';

  /**
   * {@code s} -- {@code \Seen} changes are kept by the server. Kept for the
   * <b>mailbox</b>, not per user: on both servers observed (BlueMind, Stalwart) one
   * {@code \Seen} per message is shared by the owner and every delegate, and a
   * delegate's read lowers the owner's unread count (delegation plan, sections 2.1,
   * 4.1 and 13.B.13). eXo mirrors that one shared bit by decision.
   */
  public static final char           KEEP_SEEN       = 's';

  /** {@code w} -- set every flag and keyword other than {@code \Seen} and {@code \Deleted}. */
  public static final char           WRITE           = 'w';

  /** {@code i} -- APPEND and COPY into the mailbox. */
  public static final char           INSERT          = 'i';

  /** {@code p} -- POST to the mailbox's submission address (not used by this add-on). */
  public static final char           POST            = 'p';

  /** {@code k} -- CREATE a child mailbox (RFC 4314 half of RFC 2086 {@code c}). */
  public static final char           CREATE_MAILBOX  = 'k';

  /** {@code x} -- DELETE or RENAME the mailbox itself (the other half of {@code c}). */
  public static final char           DELETE_MAILBOX  = 'x';

  /** {@code t} -- set the {@code \Deleted} flag (RFC 4314 half of RFC 2086 {@code d}). */
  public static final char           DELETE_MESSAGES = 't';

  /** {@code e} -- EXPUNGE (the other half of {@code d}). */
  public static final char           EXPUNGE         = 'e';

  /** {@code a} -- administer: SETACL, DELETEACL, GETACL. */
  public static final char           ADMINISTER      = 'a';

  /** RFC 2086 {@code c}, which RFC 4314 reads as {@code k} + {@code x}. */
  static final char                  LEGACY_CREATE   = 'c';

  /** RFC 2086 {@code d}, which RFC 4314 reads as {@code t} + {@code e}. */
  static final char                  LEGACY_DELETE   = 'd';

  /**
   * The order the letters are rendered in, which is the order RFC 4314 lists them, so
   * that two equal right sets always render the same string.
   */
  static final String                CANONICAL_ORDER = "lrswipkxtea";

  /**
   * The rights this add-on will ever write to a server on a user's behalf: what a
   * Reader or an Editor needs and nothing that lets a delegate re-share ({@code a}),
   * destroy the mailbox ({@code x}), create mailboxes ({@code k}), expunge irreversibly
   * ({@code e}) or post ({@code p}). A preset is intersected with this before anything
   * else, whatever the client asked for.
   */
  public static final MailboxRights  GRANTABLE       = of("lrswit");

  /** No right at all. */
  public static final MailboxRights  NONE            = of("");

  private final Set<Character>       letters;

  /**
   * @param letters the normalised letters, in canonical order
   */
  private MailboxRights(Set<Character> letters) {
    this.letters = letters;
  }

  /**
   * Parses a rights string as a server or a preset spells it. The RFC 2086 letters are
   * folded into their RFC 4314 pairs, repeats are dropped, and the known letters are
   * put in canonical order, followed by any letter this class does not know, in the
   * order met.
   *
   * @param rights the letters, possibly null or blank
   * @return the rights, never null
   */
  public static MailboxRights of(String rights) {
    Set<Character> parsed = new LinkedHashSet<>();
    if (rights != null) {
      for (char letter : rights.toCharArray()) {
        if (Character.isWhitespace(letter)) {
          continue;
        }
        if (letter == LEGACY_CREATE) {
          parsed.add(CREATE_MAILBOX);
          parsed.add(DELETE_MAILBOX);
        } else if (letter == LEGACY_DELETE) {
          parsed.add(DELETE_MESSAGES);
          parsed.add(EXPUNGE);
        } else {
          parsed.add(letter);
        }
      }
    }
    Set<Character> ordered = new LinkedHashSet<>();
    for (char letter : CANONICAL_ORDER.toCharArray()) {
      if (parsed.contains(letter)) {
        ordered.add(letter);
      }
    }
    ordered.addAll(parsed);
    return new MailboxRights(ordered);
  }

  /**
   * Reads a {@link Rights} the mail library parsed off the wire -- through its letters,
   * which is the one reading that survives the library's RFC 2086 constants (see the
   * class comment). {@link Rights#toString()} is the concatenation of every letter the
   * server sent, 4314 ones included.
   *
   * @param rights the parsed rights, possibly null
   * @return the rights, never null
   */
  public static MailboxRights fromRights(Rights rights) {
    return rights == null ? NONE : of(rights.toString());
  }

  /**
   * Whether one letter is held. The RFC 2086 letters are answered through their pairs:
   * {@code has('c')} is true when both {@code k} and {@code x} are, {@code has('d')}
   * when both {@code t} and {@code e} are.
   *
   * @param letter the right
   * @return true when held
   */
  public boolean has(char letter) {
    if (letter == LEGACY_CREATE) {
      return letters.contains(CREATE_MAILBOX) && letters.contains(DELETE_MAILBOX);
    }
    if (letter == LEGACY_DELETE) {
      return letters.contains(DELETE_MESSAGES) && letters.contains(EXPUNGE);
    }
    return letters.contains(letter);
  }

  /**
   * @return whether the mailbox shows up in a folder listing ({@code l})
   */
  public boolean canLookup() {
    return has(LOOKUP);
  }

  /**
   * @return whether the mailbox can be opened and its messages read ({@code r})
   */
  public boolean canRead() {
    return has(READ);
  }

  /**
   * Whether read/unread changes made through this session persist on the server
   * ({@code s}). They persist for everyone on the mailbox -- there is no per-user
   * seen state on a shared folder -- so a delegate holding {@code s} who marks a mail
   * read marks it read for the owner too; a delegate without it (every BlueMind
   * Reader: its {@code Read} verb pushes {@code lrp}) has no mark-read control and
   * contributes nothing to a badge (plan, sections 3.4 and 7.5).
   *
   * @return whether {@code \Seen} changes are kept by the server
   */
  public boolean canKeepSeen() {
    return has(KEEP_SEEN);
  }

  /**
   * @return whether flags and keywords such as star can be set ({@code w})
   */
  public boolean canWriteFlags() {
    return has(WRITE);
  }

  /**
   * @return whether messages can be copied or moved into the mailbox ({@code i})
   */
  public boolean canInsert() {
    return has(INSERT);
  }

  /**
   * @return whether the mailbox accepts POST ({@code p})
   */
  public boolean canPost() {
    return has(POST);
  }

  /**
   * @return whether a child mailbox can be created ({@code k}, or legacy {@code c})
   */
  public boolean canCreateMailbox() {
    return has(CREATE_MAILBOX);
  }

  /**
   * @return whether the mailbox itself can be deleted or renamed ({@code x}, or legacy
   *         {@code c})
   */
  public boolean canDeleteMailbox() {
    return has(DELETE_MAILBOX);
  }

  /**
   * @return whether messages can be marked deleted, hence moved out or trashed
   *         ({@code t}, or legacy {@code d})
   */
  public boolean canDeleteMessages() {
    return has(DELETE_MESSAGES);
  }

  /**
   * @return whether deleted messages can be expunged for good ({@code e}, or legacy
   *         {@code d})
   */
  public boolean canExpunge() {
    return has(EXPUNGE);
  }

  /**
   * @return whether the mailbox's ACL can be changed ({@code a})
   */
  public boolean canAdminister() {
    return has(ADMINISTER);
  }

  /**
   * The rights held here AND in another set -- what a grant becomes once capped by the
   * owner's own rights and by the allowlist.
   *
   * @param other the other set
   * @return the intersection, never null
   */
  public MailboxRights intersect(MailboxRights other) {
    StringBuilder kept = new StringBuilder();
    for (char letter : letters) {
      if (other != null && other.letters.contains(letter)) {
        kept.append(letter);
      }
    }
    return of(kept.toString());
  }

  /**
   * Whether every right of another set is held here.
   *
   * @param other the other set
   * @return true when this set covers it
   */
  public boolean covers(MailboxRights other) {
    return other == null || letters.containsAll(other.letters);
  }

  /**
   * @return true when no right is held
   */
  public boolean isEmpty() {
    return letters.isEmpty();
  }

  /**
   * The letters, normalised and in canonical order -- what is stored in
   * {@code EMAIL_DELEGATION.RIGHTS} and sent to the server on a grant.
   *
   * @return the letters, possibly empty, never null
   */
  public String letters() {
    StringBuilder rendered = new StringBuilder();
    for (char letter : letters) {
      rendered.append(letter);
    }
    return rendered.toString();
  }

  /**
   * The affordances the interface derives from the letters -- one boolean per control
   * of the delegation plan's table (section 7.5), so a client renders what the rights
   * allow and never has to know a letter. An absent right is an absent control.
   *
   * @return the affordances, keyed by control name, never null
   */
  public Map<String, Boolean> affordances() {
    Map<String, Boolean> affordances = new LinkedHashMap<>();
    affordances.put("browse", canLookup());
    affordances.put("read", canRead());
    affordances.put("markRead", canKeepSeen());
    affordances.put("star", canWriteFlags());
    affordances.put("moveTarget", canInsert());
    affordances.put("createFolder", canCreateMailbox());
    affordances.put("deleteFolder", canDeleteMailbox());
    affordances.put("delete", canDeleteMessages());
    affordances.put("expunge", canExpunge());
    affordances.put("administer", canAdminister());
    return affordances;
  }

  /**
   * @return the letters, as {@link #letters()}
   */
  @Override
  public String toString() {
    return letters();
  }

  /**
   * @param other the other object
   * @return true when both hold the same letters
   */
  @Override
  public boolean equals(Object other) {
    return other instanceof MailboxRights rights && letters.equals(rights.letters);
  }

  /**
   * @return a hash of the letters
   */
  @Override
  public int hashCode() {
    return letters.hashCode();
  }
}
