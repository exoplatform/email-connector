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

import java.util.List;
import java.util.Map;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DiscoveredFolder;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SharedMailbox;

/**
 * How a mail server's mailbox access-control lists are read and written -- the one
 * seam between the delegation lifecycle and the protocol a server speaks. One
 * implementation per mechanism, chosen per connector preset by
 * {@link MailboxAclEngineRegistry}: {@link ImapAclEngine} for RFC 4314 over the IMAP
 * session (Stalwart, Cyrus, Dovecot's ACL plugin), a vendor REST engine where a server
 * keeps its ACL writes to itself (BlueMind -- see {@code package-info} for what that
 * engine implements and why it is mandatory there), {@link NoopAclEngine} where no ACL
 * is reachable at all (Gmail, Exchange).
 * <p>
 * Every method takes the <b>caller's own</b> {@link MailboxAclSession}: the owner's to
 * list, grant and revoke, the grantee's to read their rights, discover shares and
 * subscribe. The session resolves the caller's own transports lazily and nothing
 * else; no method opens a session of its own, holds a credential beyond the call, or
 * acts as anyone but the user the session was built for -- an implementation that
 * needed to would be the unattended identity switch the design refuses
 * ({@link MailboxAclSession} says why, and why the type must not be widened to allow
 * it). The engine owns the transport it uses: an IMAP engine opens the store, a REST
 * engine never does.
 * <p>
 * Rights cross this seam as {@link MailboxRights} letters, read by letter, never as the
 * mail library's constants. Where a server's own vocabulary is not letters, the engine
 * translates and keeps the original in {@link MailboxAce#nativeRights()}. A preset is
 * expanded by the engine ({@link #grant}) and recognised by the engine
 * ({@link #presetOf}), because the letters one preset pushes differ per server. Note
 * that {@code s} means seen changes are <i>kept</i> -- for the whole mailbox, not per
 * user: {@code \Seen} is one shared flag on every server observed.
 * <p>
 * An engine reports what a server refuses as a {@link MailboxAclException} with a
 * fixed code; the server's own text goes to the DEBUG log.
 */
public interface MailboxAclEngine {

  /**
   * The name a connector preset selects this engine by.
   *
   * @return the name ({@code imap}, {@code none}, ...)
   */
  String getName();

  /**
   * What this server can do about sharing, decided by <b>attempting</b> what the
   * engine relies on (MYRIGHTS on the caller's INBOX for IMAP) and never by the
   * CAPABILITY advertisement alone, which both servers this feature met left empty
   * while answering the commands (plan, sections 3.2, 13.B.9, 13.C). Also where the
   * engine states its server's traits: grant granularity, whether the server notifies
   * the owner itself, whether the grantee must accept on the server.
   *
   * @param session the caller's session
   * @return the capabilities, never null; a genuine failure to reach the server is
   *         {@code UNREACHABLE}, a server that refuses the command is {@code UNSUPPORTED}
   *         -- the two are kept apart so the interface never says "your server cannot
   *         share" about a server that is merely down
   */
  MailboxAclCapabilities probe(MailboxAclSession session);

  /**
   * The rights the session's user holds on one mailbox (MYRIGHTS).
   *
   * @param session the caller's session
   * @param mailbox the mailbox's full name on this session
   * @return the rights, never null
   * @throws MailboxAclException when the server refuses or cannot be asked
   */
  MailboxRights myRights(MailboxAclSession session, String mailbox);

  /**
   * Who holds which rights on one mailbox (GETACL, or the vendor's rows). Needs
   * {@code a} on the mailbox. Each entry carries the letters, the server's own
   * vocabulary and the preset this engine recognises.
   *
   * @param session the owner's session
   * @param mailbox the mailbox's full name
   * @return the entries, never null
   * @throws MailboxAclException when the server refuses or cannot be asked
   */
  List<MailboxAce> listAcl(MailboxAclSession session, String mailbox);

  /**
   * Grants one preset to one identifier on one mailbox, as this server expresses it,
   * and answers what was written. The engine expands the preset into its server's
   * vocabulary (letters {@code lrs}/{@code lrswit} on IMAP, a verb on BlueMind), caps
   * it by its allowlist and by the owner's own rights -- eXo never grants a right the
   * owner does not hold, nor {@code a}, {@code x}, {@code e}, {@code k}, {@code p} on an
   * IMAP engine -- and refuses with {@code NOTHING_TO_GRANT} when what remains does
   * not even read (plan, sections 3.4, 5.1, 8). On a per-mailbox server the mailbox
   * argument names the mailbox as a whole. An existing entry for the identifier is
   * replaced, not widened.
   *
   * @param session the owner's session
   * @param mailbox the mailbox's full name
   * @param identifier the grantee as the server names them
   * @param preset the preset the owner chose, READER or EDITOR
   * @param ownerRights the owner's own rights on the mailbox, read by the caller and
   *          already checked to carry {@code a}
   * @return the entry as written: the letters the server will hold, the native form,
   *         and the preset it corresponds to on this server
   * @throws MailboxAclException when nothing is left to grant or the server refuses
   */
  MailboxAce grant(MailboxAclSession session, String mailbox, String identifier, DelegationPreset preset, MailboxRights ownerRights);

  /**
   * Removes one identifier's entry from one mailbox (DELETEACL, or the vendor's
   * equivalent). "Remove access" on every server: the whole entry goes, whatever
   * created it.
   *
   * @param session the owner's session
   * @param mailbox the mailbox's full name
   * @param identifier the grantee as the server names them
   * @throws MailboxAclException when the server refuses
   */
  void revoke(MailboxAclSession session, String mailbox, String identifier);

  /**
   * The letters a preset stands for on one folder of the owner's mailbox, by the
   * folder's role (EXO-90548). The default is the preset's own letters whatever the
   * folder: a per-mailbox engine never sees a role.
   *
   * @param preset READER or EDITOR
   * @param role the folder's role, null for INBOX
   * @return the letters, before any cap
   */
  default MailboxRights lettersFor(DelegationPreset preset, FolderRole role) {
    return preset == null ? MailboxRights.NONE : preset.rights();
  }

  /**
   * {@link #grant(MailboxAclSession, String, String, DelegationPreset, MailboxRights)} on
   * one folder of a given role, whose letters may differ by role -- an Editor holds
   * {@code e} where mail leaves, never on Trash (EXO-90548). The default ignores the
   * role, as a per-mailbox engine does.
   *
   * @param session the owner's session
   * @param mailbox the folder's full name
   * @param identifier the grantee as the server names them
   * @param preset READER or EDITOR
   * @param ownerRights the owner's own rights on that folder
   * @param role the folder's role, null for INBOX
   * @return the entry as written
   * @throws MailboxAclException when nothing is left to grant or the server refuses
   */
  default MailboxAce grant(MailboxAclSession session,
                           String mailbox,
                           String identifier,
                           DelegationPreset preset,
                           MailboxRights ownerRights,
                           FolderRole role) {
    return grant(session, mailbox, identifier, preset, ownerRights);
  }

  /**
   * The owner's own folders by role, read on the OWNER's session, where the server
   * shows their special-use attributes -- it may show a delegate none (Dovecot,
   * EXO-90552). Only on a per-folder engine; the default knows none.
   *
   * @param session the owner's session
   * @return the folder full name of each role found, possibly empty, never null
   * @throws MailboxAclException when the server cannot be asked
   */
  default Map<FolderRole, String> findRoleFolders(MailboxAclSession session) {
    return Map.of();
  }

  /**
   * The owner's own folders whose ACL names an identifier, for "Remove access" to remove
   * every entry of that person -- including one written in another mail application
   * (EXO-90548). Only on a per-folder engine; the default knows none.
   *
   * @param session the owner's session
   * @param identifier the grantee as the server names them
   * @return the folder full names, possibly empty, never null
   * @throws MailboxAclException when the server cannot be asked
   */
  default List<String> foldersHolding(MailboxAclSession session, String identifier) {
    return List.of();
  }

  /**
   * The folders of a mailbox shared with the session's user, as that session lists them
   * under the shared mailbox's root -- the root itself excluded (EXO-90548). Each with the
   * LIST attributes the server shows the delegate (possibly no special-use at all:
   * Dovecot shows none on a shared folder) and whether it can hold mail. The default
   * lists nothing, as a per-mailbox engine's discovery is its own.
   *
   * @param session the grantee's session
   * @param root the shared mailbox's root in the grantee's listing
   * @param delimiter the hierarchy delimiter, "/" when unknown
   * @return the folders, possibly empty, never null
   * @throws MailboxAclException when the server refuses or cannot be reached
   */
  default List<DiscoveredFolder> listFoldersUnder(MailboxAclSession session, String root, String delimiter) {
    return List.of();
  }

  /**
   * The mailboxes other users shared with the session's user, as this session sees
   * them -- whoever granted them. The prefix under which they appear is read from the
   * server (NAMESPACE, else the session's own LIST) and never assumed: it is
   * localised and modified-UTF-7 on BlueMind ({@code Autres utilisateurs/}), and
   * {@code Shared Folders/<owner-email>/} on Stalwart with no NAMESPACE advertised
   * (plan, sections 2.1, 13.B.11, 13.C).
   *
   * @param session the grantee's session
   * @return the shared mailboxes, empty when the session sees none, never null
   * @throws MailboxAclException when the server refuses or cannot be asked
   */
  List<SharedMailbox> listSharedMailboxes(MailboxAclSession session);

  /**
   * One owner's mailbox among the shared ones, by the identifier the ACL names them by.
   *
   * @param session the grantee's session
   * @param ownerIdentifier the owner's mailbox identifier
   * @return the shared mailbox, or null when the session does not see it
   * @throws MailboxAclException when the server refuses or cannot be asked
   */
  SharedMailbox findSharedMailbox(MailboxAclSession session, String ownerIdentifier);

  /**
   * The preset a set of observed letters corresponds to <b>on this server</b>. The
   * default is the exact IMAP reading ({@code lrs} Reader, {@code lrswit} Editor); an
   * engine whose server pushes other letters for a preset overrides it -- BlueMind's
   * {@code Read} verb pushes {@code lrp}, so a read share made in BlueMind's own
   * interface must still read as a Reader here, or the "same list rendered twice"
   * goal of the plan (section 5.5) is lost to a CUSTOM label (section 3.4).
   *
   * @param rights the letters a server answered
   * @return the preset, CUSTOM when none matches
   */
  default DelegationPreset presetOf(MailboxRights rights) {
    return DelegationPreset.fromRights(rights);
  }

  /**
   * The grantee's server-side acceptance of a share, where the server has one -- run
   * as the grantee, on the grantee's session, before the shared mailbox is looked for.
   * Called by the lifecycle only when {@link MailboxAclCapabilities#subscriptionRequired()}
   * is true. On BlueMind this is the user subscription its webmail also reads
   * ({@code POST /api/users/{domainUid}/subscriptions/{shareeUid}/_subscribe}, the
   * call the CalDAV add-on already makes for calendars), and it is what makes the
   * share visible at all: the share was invisible to the delegate until accepted
   * (plan, sections 5.2 and 13.B.14). On IMAP there is no such step and this is a
   * no-op: the folder is in the delegate's LIST the moment the ACL is set.
   *
   * @param session the grantee's session
   * @param ownerIdentifier the owner's mailbox identifier
   * @throws MailboxAclException when the server refuses
   */
  default void subscribe(MailboxAclSession session, String ownerIdentifier) {
    // No server-side acceptance step on this engine.
  }

  /**
   * The reverse of {@link #subscribe}: withdraws the grantee's server-side
   * subscription when they leave the share in eXo, so their other clients stop
   * showing it too. The ACL itself is untouched -- only the owner removes that. A
   * no-op where the server has no subscription.
   *
   * @param session the grantee's session
   * @param ownerIdentifier the owner's mailbox identifier
   * @throws MailboxAclException when the server refuses
   */
  default void unsubscribe(MailboxAclSession session, String ownerIdentifier) {
    // No server-side subscription on this engine.
  }

  /**
   * Whether this server tells the mailbox owner about a rights change of its own
   * accord, so that eXo must not tell them again. BlueMind e-mails the owner on every
   * grant and every revoke -- four such mails were observed over two phase-0 rounds,
   * "MEYER a modifie vos droits d'acces" -- while a plain IMAP server says nothing
   * (plan, sections 5.1 and 13.F).
   * <p>
   * Read <b>without a session</b>, on purpose: it is a trait of the server product,
   * not of one connection, and the party eXo would notify is the owner while the
   * action that triggers it is often the grantee's. Opening the owner's mailbox from
   * the grantee's request thread to answer a notification question would be both
   * expensive and an identity switch this code makes nowhere.
   * <p>
   * It must agree with the {@link MailboxAclCapabilities#serverNotifiesOwner()} bit
   * this engine's {@link #probe} reports -- they are the same fact reached two ways,
   * one for the lifecycle and one for the interface. An engine that overrides this to
   * true builds its capabilities with the same value.
   *
   * @return true when the server notifies the owner itself
   */
  default boolean serverNotifiesOwner() {
    return false;
  }
}
