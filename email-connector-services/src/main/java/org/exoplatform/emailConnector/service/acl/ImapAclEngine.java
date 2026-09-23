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

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.mail.Folder;
import javax.mail.FolderClosedException;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.StoreClosedException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.sun.mail.iap.ConnectionException;
import com.sun.mail.imap.ACL;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.Rights;

import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SharedMailbox;
import org.exoplatform.emailConnector.service.EmailFolderService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * RFC 4314 (ACL) and RFC 2342 (NAMESPACE) over the IMAP provider the add-on already
 * ships -- {@link IMAPFolder#myRights()}, {@link IMAPFolder#getACL()},
 * {@link IMAPFolder#addACL(ACL)}, {@link IMAPFolder#removeACL(String)},
 * {@link IMAPStore#getUserNamespaces(String)}. No new dependency. The store is the
 * caller's own, opened on demand through {@link MailboxAclSession#store()}.
 * <p>
 * <b>The command is the test, never the advertisement.</b> The first version of this
 * class read CAPABILITY and refused a server that did not list {@code ACL}; phase 0
 * of the delegation plan then found that both servers this feature has met -- the
 * BlueMind customer deployment and Stalwart v0.11.8 -- answer {@code MYRIGHTS},
 * {@code GETACL} and (where the proxy lets it through) {@code SETACL} while advertising
 * no {@code ACL} at all, and that Stalwart served its shared-folder prefix while
 * advertising no {@code NAMESPACE} (sections 3.2, 11, 13.B.9, 13.C: "100 % of the
 * sample, not an outlier"). Gated on the advertisement, the feature reported "your
 * server cannot share" on every server it was for. So {@link #probe} keeps
 * {@code hasCapability("ACL")} only as a fast positive and otherwise <b>attempts
 * MYRIGHTS on the caller's INBOX</b>; and {@link #listSharedMailboxes} tries
 * NAMESPACE and, when it is silent, reads the prefix out of the session's own LIST.
 * A server that answers {@code BAD} or {@code NO} to MYRIGHTS is still reported
 * unsupported with its reason, and a server that cannot be reached is reported
 * unreachable -- never confused with one that cannot share.
 * <p>
 * Rights cross this class as letters only ({@link MailboxRights#fromRights(Rights)} on
 * the way in, {@link MailboxRights#letters()} into {@code new Rights(String)} on the way
 * out). The library's {@link Rights.Right} constants are the RFC 2086 set and would read
 * a modern server's {@code t} as "no delete right" -- see {@link MailboxRights}. The
 * preset a set of letters means is the exact IMAP reading (Reader {@code lrs}, Editor
 * {@code lrswit}); a grant is those letters capped by {@link MailboxRights#GRANTABLE}
 * and by the owner's own rights, written per <b>folder</b> -- INBOX in this phase.
 * <p>
 * <b>What phase 0 left open for this class</b>: how a server spells the owner in the
 * namespace path against how the ACL names them (login, or address) --
 * {@link #findSharedMailbox} matches the whole identifier first and its local part
 * second, and says so; and Stalwart's behaviour on a current build, observed on
 * v0.11.8 only (section 13.C).
 */
@Service
public class ImapAclEngine implements MailboxAclEngine {

  private static final Log   LOG                  = ExoLogger.getLogger(ImapAclEngine.class);

  /** The engine name a preset selects. */
  public static final String NAME                 = "imap";

  /** How many folders "Remove access" asks for the grantee's entry, at most (plan, A-4). */
  static final int           MAX_FOLDERS_ASKED    = 200;

  /** The RFC 4314 capability -- a fast positive in {@link #probe}, never the gate. */
  static final String        ACL_CAPABILITY       = "ACL";

  /** The RFC 2342 capability -- a hint in {@link #probe}, never the gate. */
  static final String        NAMESPACE_CAPABILITY = "NAMESPACE";

  /**
   * @return {@value #NAME}
   */
  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Probes by attempting the command. Not IMAP: unsupported with the reason. Else
   * {@code ACL} advertised is enough; otherwise MYRIGHTS on the caller's INBOX is
   * attempted and a rights answer -- any letters, even none -- is support. A
   * {@code BAD}/{@code NO} is unsupported with the reason; a closed or dropped
   * connection is unreachable, kept apart so the interface never tells a user their
   * server cannot share when it is merely down. NAMESPACE is read as a hint only:
   * discovery works without it.
   *
   * @param session the caller's session
   * @return the capabilities
   */
  @Override
  public MailboxAclCapabilities probe(MailboxAclSession session) {
    Store store;
    try {
      store = session.store();
    } catch (MailboxAclException e) {
      return MailboxAclCapabilities.unsupported(e.getCode());
    }
    if (!(store instanceof IMAPStore imapStore)) {
      return MailboxAclCapabilities.unsupported(MailboxAclException.NOT_IMAP);
    }
    boolean aclAdvertised;
    boolean namespaceAdvertised;
    try {
      aclAdvertised = imapStore.hasCapability(ACL_CAPABILITY);
      namespaceAdvertised = imapStore.hasCapability(NAMESPACE_CAPABILITY);
    } catch (MessagingException e) {
      LOG.debug("CAPABILITY could not be read: {}", e.getMessage());
      return MailboxAclCapabilities.unsupported(MailboxAclException.UNREACHABLE);
    }
    if (aclAdvertised) {
      return MailboxAclCapabilities.imap(true, namespaceAdvertised);
    }
    try {
      folder(store, MailFolder.INBOX).myRights();
      return MailboxAclCapabilities.imap(false, namespaceAdvertised);
    } catch (MessagingException e) {
      String code = isConnectionFailure(e) ? MailboxAclException.UNREACHABLE : MailboxAclException.UNSUPPORTED;
      LOG.debug("MYRIGHTS INBOX did not answer on a server advertising no ACL ({}): {}", code, e.getMessage());
      return MailboxAclCapabilities.unsupported(code);
    }
  }

  /**
   * MYRIGHTS on one mailbox, read by letter.
   *
   * @param session the caller's session
   * @param mailbox the mailbox's full name
   * @return the rights
   * @throws MailboxAclException when the server refuses
   */
  @Override
  public MailboxRights myRights(MailboxAclSession session, String mailbox) {
    try {
      return MailboxRights.fromRights(folder(session.store(), mailbox).myRights());
    } catch (MessagingException e) {
      // A dropped line is not the server saying no (EXO-90557): a caller that revokes on
      // a refusal must not revoke on a network hiccup.
      if (isConnectionFailure(e)) {
        LOG.debug("MYRIGHTS on '{}' could not be asked, the connection failed: {}", mailbox, e.getMessage());
        throw new MailboxAclException(MailboxAclException.UNREACHABLE, e);
      }
      throw refused("MYRIGHTS", mailbox, e);
    }
  }

  /**
   * GETACL on one mailbox, every entry read by letter; the native form is the letters
   * themselves and the preset the exact reading.
   *
   * @param session the owner's session
   * @param mailbox the mailbox's full name
   * @return the entries
   * @throws MailboxAclException when the server refuses
   */
  @Override
  public List<MailboxAce> listAcl(MailboxAclSession session, String mailbox) {
    try {
      ACL[] acls = folder(session.store(), mailbox).getACL();
      List<MailboxAce> entries = new ArrayList<>();
      for (ACL acl : acls == null ? new ACL[0] : acls) {
        entries.add(ace(acl.getName(), MailboxRights.fromRights(acl.getRights())));
      }
      return entries;
    } catch (MessagingException e) {
      throw refused("GETACL", mailbox, e);
    }
  }

  /**
   * The preset's letters on INBOX: {@link #grant(MailboxAclSession, String, String,
   * DelegationPreset, MailboxRights, FolderRole)} with no role.
   *
   * @param session the owner's session
   * @param mailbox the folder's full name (per-folder server)
   * @param identifier the grantee as the server names them
   * @param preset READER or EDITOR
   * @param ownerRights the owner's own MYRIGHTS on the folder
   * @return the entry as written
   * @throws MailboxAclException when nothing is left to grant or the server refuses
   */
  @Override
  public MailboxAce grant(MailboxAclSession session,
                          String mailbox,
                          String identifier,
                          DelegationPreset preset,
                          MailboxRights ownerRights) {
    return grant(session, mailbox, identifier, preset, ownerRights, null);
  }

  /**
   * The preset's letters for the folder's role ({@link #lettersFor}), capped by the
   * role's allowlist -- {@link MailboxRights#GRANTABLE} on Trash,
   * {@link MailboxRights#GRANTABLE_WHERE_MAIL_LEAVES} everywhere else -- and by the
   * owner's own rights, then SETACL with exactly those: {@link IMAPFolder#addACL(ACL)}
   * sends the rights with no {@code +}/{@code -} modifier, so an entry the identifier
   * already had is replaced rather than widened. Nothing that reads is left: refused
   * with {@code NOTHING_TO_GRANT}, nothing written.
   *
   * @param session the owner's session
   * @param mailbox the folder's full name
   * @param identifier the grantee as the server names them
   * @param preset READER or EDITOR
   * @param ownerRights the owner's own MYRIGHTS on the folder
   * @param role the folder's role, null for INBOX
   * @return the entry as written: the letters, the letters again as native form, and
   *         the preset they read as -- READER when an Editor was capped to {@code lrs}
   * @throws MailboxAclException when nothing is left to grant or the server refuses
   */
  @Override
  public MailboxAce grant(MailboxAclSession session,
                          String mailbox,
                          String identifier,
                          DelegationPreset preset,
                          MailboxRights ownerRights,
                          FolderRole role) {
    MailboxRights cap = role == FolderRole.TRASH ? MailboxRights.GRANTABLE : MailboxRights.GRANTABLE_WHERE_MAIL_LEAVES;
    MailboxRights letters = lettersFor(preset, role).intersect(cap).intersect(ownerRights);
    if (!letters.canRead()) {
      throw new MailboxAclException(MailboxAclException.NOTHING_TO_GRANT,
                                    "preset " + preset + " against owner rights " + (ownerRights == null ? "" : ownerRights.letters()));
    }
    try {
      folder(session.store(), mailbox).addACL(new ACL(identifier, new Rights(letters.letters())));
    } catch (MessagingException e) {
      throw refused("SETACL", mailbox, e);
    }
    return ace(identifier, letters);
  }

  /**
   * A Reader reads ({@code lrs}) everywhere. An Editor also writes, and holds
   * {@code e} -- the right to expunge, without which a delete or a move leaves the
   * original behind -- on every folder mail leaves from, and never on Trash, where it
   * would be permanent deletion (EXO-90548, PO decision Q-1).
   *
   * @param preset READER or EDITOR
   * @param role the folder's role, null for INBOX
   * @return the letters, before the owner's cap
   */
  @Override
  public MailboxRights lettersFor(DelegationPreset preset, FolderRole role) {
    if (preset == DelegationPreset.EDITOR) {
      return role == FolderRole.TRASH ? MailboxRights.GRANTABLE : MailboxRights.GRANTABLE_WHERE_MAIL_LEAVES;
    }
    return expand(preset);
  }

  /**
   * The owner's folders by role, from one {@code LIST "*"} on the owner's session: the
   * special-use attribute first ({@code \Sent}, {@code \Archive}, {@code \Trash},
   * {@code \Junk}, {@code \Drafts}), then, for a role no attribute names, a folder whose
   * last segment is that role's usual English name. Never a folder under another user's
   * or a shared namespace, never one that cannot hold mail; the first folder found for a
   * role keeps it.
   *
   * @param session the owner's session
   * @return the folder full name of each role found, never null
   * @throws MailboxAclException when the server refuses
   */
  @Override
  public Map<FolderRole, String> findRoleFolders(MailboxAclSession session) {
    Map<FolderRole, String> byAttribute = new EnumMap<>(FolderRole.class);
    Map<FolderRole, String> byName = new EnumMap<>(FolderRole.class);
    for (IMAPFolder folder : ownFolders(session)) {
      FolderRole attributeRole = roleOfAttributes(folder);
      if (attributeRole != null) {
        byAttribute.putIfAbsent(attributeRole, folder.getFullName());
        continue;
      }
      FolderRole nameRole = roleOfName(folder.getName());
      if (nameRole != null) {
        byName.putIfAbsent(nameRole, folder.getFullName());
      }
    }
    byName.forEach(byAttribute::putIfAbsent);
    return byAttribute;
  }

  /**
   * The owner's folders whose GETACL names the identifier, INBOX included. At most
   * {@link #MAX_FOLDERS_ASKED} folders are asked -- "Remove access" on a mailbox of
   * hundreds of folders stops there and says so in the log (plan, assumption A-4). A
   * folder whose ACL cannot be read is skipped: its entry, if any, is the owner's to
   * remove in the mail server's own interface.
   *
   * @param session the owner's session
   * @param identifier the grantee as the server names them
   * @return the folder full names, never null
   * @throws MailboxAclException when the server cannot list folders
   */
  @Override
  public List<String> foldersHolding(MailboxAclSession session, String identifier) {
    List<String> holding = new ArrayList<>();
    if (StringUtils.isBlank(identifier)) {
      return holding;
    }
    List<IMAPFolder> folders = ownFolders(session);
    if (folders.size() > MAX_FOLDERS_ASKED) {
      LOG.warn("Removing an access asks only the first {} of {} folders; an entry beyond them stays", MAX_FOLDERS_ASKED, folders.size());
    }
    for (IMAPFolder folder : folders.subList(0, Math.min(folders.size(), MAX_FOLDERS_ASKED))) {
      try {
        for (ACL acl : folder.getACL()) {
          if (acl != null && identifier.equalsIgnoreCase(acl.getName())) {
            holding.add(folder.getFullName());
            break;
          }
        }
      } catch (MessagingException e) {
        LOG.debug("GETACL on '{}' could not be read while removing an access: {}", folder.getFullName(), e.getMessage());
      }
    }
    return holding;
  }

  /**
   * The folders of the session's own mailbox: {@code LIST "*"}, minus every folder that
   * cannot hold mail and every folder under another user's or a shared namespace.
   *
   * @param session the session
   * @return the folders, never null
   * @throws MailboxAclException when the server refuses
   */
  private List<IMAPFolder> ownFolders(MailboxAclSession session) {
    List<IMAPFolder> own = new ArrayList<>();
    try {
      Store store = session.store();
      List<String> otherRoots = new ArrayList<>();
      for (Folder[] namespaces : List.of(nonNull(store.getUserNamespaces(null)), nonNull(store.getSharedNamespaces()))) {
        for (Folder namespace : namespaces) {
          if (namespace != null && StringUtils.isNotBlank(namespace.getFullName())) {
            otherRoots.add(namespace.getFullName());
          }
        }
      }
      if (otherRoots.isEmpty()) {
        // A server that advertises no namespace (Stalwart): the shape of its listing.
        for (Folder root : namespaceRootsFromList(store)) {
          otherRoots.add(root.getFullName());
        }
      }
      Folder[] listed = store.getDefaultFolder().list("*");
      for (Folder folder : listed == null ? new Folder[0] : listed) {
        if (!(folder instanceof IMAPFolder imapFolder) || (imapFolder.getType() & Folder.HOLDS_MESSAGES) == 0) {
          continue;
        }
        if (!EmailFolderService.isUnderAnyRoot(imapFolder.getFullName(), String.valueOf(imapFolder.getSeparator()), otherRoots)) {
          own.add(imapFolder);
        }
      }
      return own;
    } catch (MessagingException e) {
      throw refused("LIST", "*", e);
    }
  }

  /**
   * An array the library may answer null for, as an empty one.
   *
   * @param folders the array
   * @return the array, never null
   */
  private static Folder[] nonNull(Folder[] folders) {
    return folders == null ? new Folder[0] : folders;
  }

  /**
   * The role a folder's special-use attributes name, if any.
   *
   * @param folder the folder
   * @return the role, or null
   * @throws MailboxAclException never: an unreadable attribute list is no role
   */
  private static FolderRole roleOfAttributes(IMAPFolder folder) {
    String[] attributes;
    try {
      attributes = folder.getAttributes();
    } catch (MessagingException e) {
      return null;
    }
    for (String attribute : attributes == null ? new String[0] : attributes) {
      FolderRole role = switch (attribute.toLowerCase(Locale.ROOT)) {
      case "\\sent" -> FolderRole.SENT;
      case "\\archive" -> FolderRole.ARCHIVE;
      case "\\trash" -> FolderRole.TRASH;
      case "\\junk" -> FolderRole.JUNK;
      case "\\drafts" -> FolderRole.DRAFTS;
      default -> null;
      };
      if (role != null) {
        return role;
      }
    }
    return null;
  }

  /**
   * The role a folder's last segment names by its usual English name, exactly (not a
   * substring: a folder named "Trash notes" is not the Trash).
   *
   * @param name the last segment
   * @return the role, or null
   */
  private static FolderRole roleOfName(String name) {
    if (name == null) {
      return null;
    }
    return switch (name.trim().toLowerCase(Locale.ROOT)) {
    case "sent", "sent items", "sent messages", "sent mail" -> FolderRole.SENT;
    case "archive", "archives" -> FolderRole.ARCHIVE;
    case "trash", "deleted items", "deleted messages" -> FolderRole.TRASH;
    case "junk", "spam", "junk e-mail", "junk email" -> FolderRole.JUNK;
    case "drafts" -> FolderRole.DRAFTS;
    default -> null;
    };
  }

  /**
   * The preset a set of observed letters reads as on an IMAP server: a preset's letters
   * exactly, or a preset's letters plus only what the server adds by itself because it
   * couples letters (RFC 4314 section 2.1.1). Stalwart answers {@code lrswit} as
   * {@code tewsirl}: it stores {@code e} with {@code t}, so an Editor granted from eXo
   * read back as CUSTOM (observed on the rig, 2026-09-23). The only coupling admitted is
   * {@code e} beside {@code t}, so a set granting more than coupling implies
   * ({@code a}, {@code x}, {@code e} without {@code t}) is never a preset. The virtual
   * {@code c}/{@code d} Dovecot adds never reach here: {@link MailboxRights#of(String)}
   * drops them (EXO-90552).
   *
   * @param rights the letters a server answered
   * @return READER or EDITOR, CUSTOM when none matches
   */
  @Override
  public DelegationPreset presetOf(MailboxRights rights) {
    if (rights == null) {
      return DelegationPreset.CUSTOM;
    }
    for (DelegationPreset preset : DelegationPreset.values()) {
      if (preset.isGrantable() && readsAs(rights.letters(), preset.rights().letters())) {
        return preset;
      }
    }
    return DelegationPreset.CUSTOM;
  }

  /**
   * Whether observed letters are a preset's letters, plus only letters the preset's own
   * letters make a server add by coupling.
   *
   * @param observed the letters the server answered
   * @param preset the preset's letters
   * @return true when the observed set reads as the preset
   */
  private static boolean readsAs(String observed, String preset) {
    for (char letter : preset.toCharArray()) {
      if (observed.indexOf(letter) < 0) {
        return false;
      }
    }
    for (char letter : observed.toCharArray()) {
      if (preset.indexOf(letter) < 0 && !impliedByCoupling(letter, preset)) {
        return false;
      }
    }
    return true;
  }

  /**
   * Whether a server may add a letter by itself because the granted letters couple it
   * (RFC 4314 section 2.1.1): {@code e} with {@code t}. The legacy {@code d} and
   * {@code c} are kept as a guard only: {@link MailboxRights#letters()} never holds
   * them (folded or dropped by {@link MailboxRights#of(String)}), so those two arms are
   * unreachable today and would only matter if a caller built rights another way.
   *
   * @param letter the extra letter observed
   * @param granted the letters granted
   * @return true when the letter is implied by one granted
   */
  private static boolean impliedByCoupling(char letter, String granted) {
    return switch (letter) {
    case 'e', 'd' -> granted.indexOf('t') >= 0;
    case 'c' -> granted.indexOf('k') >= 0;
    default -> false;
    };
  }

  /**
   * One entry as this engine reads it: the letters, the letters again as the native
   * form, and the preset they read as on an IMAP server ({@link #presetOf}).
   *
   * @param identifier the identifier
   * @param rights the letters
   * @return the entry
   */
  private MailboxAce ace(String identifier, MailboxRights rights) {
    MailboxRights safe = rights == null ? MailboxRights.NONE : rights;
    return new MailboxAce(identifier, safe, safe.letters(), presetOf(safe));
  }

  /**
   * DELETEACL for one identifier.
   *
   * @param session the owner's session
   * @param mailbox the mailbox's full name
   * @param identifier the grantee as the server names them
   * @throws MailboxAclException when the server refuses
   */
  @Override
  public void revoke(MailboxAclSession session, String mailbox, String identifier) {
    try {
      folder(session.store(), mailbox).removeACL(identifier);
    } catch (MessagingException e) {
      throw refused("DELETEACL", mailbox, e);
    }
  }

  /**
   * The Other Users namespace(s), from NAMESPACE first and from the session's own
   * LIST when NAMESPACE is absent or answers nothing (Stalwart v0.11.8 served
   * {@code Shared Folders/<owner-email>/} that way, section 13.C). Each namespace root
   * is listed one level down for the owners, each owner one level down for an INBOX
   * child; a server that lists the owner's mailbox as the root itself (Cyrus without
   * {@code altnamespace}: {@code user.anne} IS her INBOX) has no INBOX child, and the
   * root is taken as the INBOX. The prefix is whatever the server lists -- localised,
   * modified-UTF-7 -- and is never assumed.
   *
   * @param session the grantee's session
   * @return the shared mailboxes, never null
   * @throws MailboxAclException when the server refuses
   */
  @Override
  public List<SharedMailbox> listSharedMailboxes(MailboxAclSession session) {
    List<SharedMailbox> shared = new ArrayList<>();
    try {
      Store store = session.store();
      Folder[] namespaces = store.getUserNamespaces(null);
      if (namespaces == null || namespaces.length == 0) {
        namespaces = namespaceRootsFromList(store);
      }
      for (Folder namespace : namespaces) {
        for (Folder owner : ownersOf(store, namespace)) {
          shared.add(describe(owner));
        }
      }
      return shared;
    } catch (MessagingException e) {
      throw refused("LIST", "Other Users", e);
    }
  }

  /**
   * One owner among the shared mailboxes. Matches the namespace's last segment against
   * the identifier as a whole first ({@code anne.dupont} or {@code anne@acme.com}), then
   * against its local part -- a server that names the ACL identifier by address and
   * the namespace by login answers the second.
   * <p>
   * TODO: how the identifier a SETACL takes relates to the segment the Other Users
   * namespace lists is still not settled. Phase 0 ran on BlueMind and on Stalwart
   * (2026-09-21/22) but did not record either the SETACL identifier or the segment
   * letters side by side ("not in the record" -- plan, sections 13.C.1 and 13.D); the
   * Stalwart segment was the owner's full address. On Dovecot 2.3.21 (EXO-90552) the
   * segment is the owner's full address too, and SETACL was observed to accept any
   * identifier verbatim -- {@code bob}, and an unknown {@code nobody@dovecot.local} --
   * with no error. The rig's logins equal the addresses, so what follows is reasoned
   * from Dovecot's {@code shared/%%u/} semantics, not observed: the segment is the
   * owner's <b>login</b>, and a grantee named by an address that is not their login
   * would be granted nothing, silently. Until a server with login != address is
   * recorded, both spellings are tried here and neither is assumed.
   *
   * @param session the grantee's session
   * @param ownerIdentifier the owner's mailbox identifier
   * @return the shared mailbox, or null
   * @throws MailboxAclException when the server refuses
   */
  @Override
  public SharedMailbox findSharedMailbox(MailboxAclSession session, String ownerIdentifier) {
    if (StringUtils.isBlank(ownerIdentifier)) {
      return null;
    }
    String wanted = ownerIdentifier.trim().toLowerCase(Locale.ROOT);
    String localPart = wanted.contains("@") ? wanted.substring(0, wanted.indexOf('@')) : wanted;
    SharedMailbox byLocalPart = null;
    for (SharedMailbox mailbox : listSharedMailboxes(session)) {
      String segment = mailbox.ownerIdentifier() == null ? "" : mailbox.ownerIdentifier().toLowerCase(Locale.ROOT);
      if (segment.equals(wanted)) {
        return mailbox;
      }
      if (byLocalPart == null && segment.equals(localPart)) {
        byLocalPart = mailbox;
      }
    }
    return byLocalPart;
  }

  /**
   * The letters a preset stands for on an IMAP server -- the preset's own.
   *
   * @param preset the preset
   * @return the letters, empty for CUSTOM or null
   */
  private MailboxRights expand(DelegationPreset preset) {
    return preset == null ? MailboxRights.NONE : preset.rights();
  }

  /**
   * The owner folders inside one shared-mailbox namespace, listed <b>by pattern from
   * the default folder</b> rather than with {@code namespace.list("%")}.
   * <p>
   * The distinction is not cosmetic, and it cost a live debugging session. A namespace
   * {@code Folder} carries {@code isNamespace}, and the mail library appends the
   * separator to the name when it probes whether such a folder exists -- it asks
   * {@code LIST "" "Shared Folders/"}. Stalwart 0.11.8 answers <b>nothing</b> to that
   * form while answering {@code LIST "" "Shared Folders"} and
   * {@code LIST "" "Shared Folders/%"} perfectly well, so the probe concluded the
   * namespace did not exist and {@code list} threw {@code FolderNotFoundException}
   * ("Shared Folders not found") before any real listing was attempted. Every command
   * the walk needs worked; only the library's existence check did not.
   * <p>
   * Listing {@code <namespace><separator>%} from the default folder asks the one
   * question we want, in the one form both servers answer, and skips the probe
   * entirely.
   *
   * @param store the connected store
   * @param namespace the namespace root
   * @return the owner folders, possibly empty, never null
   * @throws MessagingException when the store cannot list
   */
  private Folder[] ownersOf(Store store, Folder namespace) throws MessagingException {
    String root = namespace.getFullName();
    if (StringUtils.isBlank(root)) {
      return new Folder[0];
    }
    char separator = namespace.getSeparator();
    String prefix = separator == 0 || separator == Character.MAX_VALUE ? root
                                                                       : StringUtils.removeEnd(root, String.valueOf(separator))
                                                                         + separator;
    Folder[] owners = store.getDefaultFolder().list(prefix + "%");
    return owners == null ? new Folder[0] : owners;
  }

  /**
   * The namespace roots as the session's own LIST shows them, for a server that does
   * not answer NAMESPACE. A top-level folder is taken as an Other Users root when it
   * cannot hold messages itself (a {@code \Noselect} container, not INBOX) and at least
   * one of its children lists an INBOX child of its own -- the shape of
   * {@code Shared Folders/<owner>/INBOX}. A user's own containers fail the second test
   * (their children hold no INBOX), so a plain {@code Customers/Acme} tree is not
   * mistaken for a share.
   *
   * @param store the connected store
   * @return the roots, possibly empty, never null
   * @throws MessagingException when the store cannot list
   */
  private Folder[] namespaceRootsFromList(Store store) throws MessagingException {
    List<Folder> roots = new ArrayList<>();
    Folder[] topLevel = store.getDefaultFolder().list("%");
    for (Folder candidate : topLevel == null ? new Folder[0] : topLevel) {
      if (MailFolder.INBOX.equalsIgnoreCase(candidate.getName()) || (candidate.getType() & Folder.HOLDS_MESSAGES) != 0) {
        continue;
      }
      if (hasAnOwnerWithAnInbox(candidate)) {
        roots.add(candidate);
      }
    }
    return roots.toArray(new Folder[0]);
  }

  /**
   * Whether one child of a container lists an INBOX of its own -- what tells a
   * shared-folder root from a user's own folder tree.
   *
   * @param container the top-level folder
   * @return true when a grandchild named INBOX exists
   * @throws MessagingException when a listing fails
   */
  private boolean hasAnOwnerWithAnInbox(Folder container) throws MessagingException {
    Folder[] owners = container.list("%");
    for (Folder owner : owners == null ? new Folder[0] : owners) {
      Folder[] children = owner.list("%");
      for (Folder child : children == null ? new Folder[0] : children) {
        if (MailFolder.INBOX.equalsIgnoreCase(child.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * One owner's root under the namespace, and where its INBOX is.
   *
   * @param owner the owner's folder as the namespace listed it
   * @return the shared mailbox
   * @throws MessagingException when the owner's children cannot be listed
   */
  private SharedMailbox describe(Folder owner) throws MessagingException {
    char separator = owner.getSeparator();
    String delimiter = separator == 0 || separator == Character.MAX_VALUE ? null : String.valueOf(separator);
    String inbox = owner.getFullName();
    Folder[] children = owner.list("%");
    for (Folder child : children == null ? new Folder[0] : children) {
      if (MailFolder.INBOX.equalsIgnoreCase(child.getName())) {
        inbox = child.getFullName();
        break;
      }
    }
    return new SharedMailbox(owner.getName(), owner.getFullName(), inbox, delimiter);
  }

  /**
   * The IMAP folder of a name on a session. No {@code exists()} probe and no open:
   * the ACL commands take a mailbox name and need neither.
   *
   * @param store the connected store
   * @param mailbox the full name
   * @return the folder
   * @throws MessagingException when the store cannot hand it out
   * @throws MailboxAclException when the session is not an IMAP one
   */
  private IMAPFolder folder(Store store, String mailbox) throws MessagingException {
    if (!(store instanceof IMAPStore)) {
      throw new MailboxAclException(MailboxAclException.NOT_IMAP, "store is " + (store == null ? "null" : store.getClass().getName()));
    }
    Folder folder = store.getFolder(mailbox);
    if (!(folder instanceof IMAPFolder imapFolder)) {
      throw new MailboxAclException(MailboxAclException.NOT_IMAP, "folder is " + (folder == null ? "null" : folder.getClass().getName()));
    }
    return imapFolder;
  }

  /**
   * Whether a failure of a command is the connection's rather than the server's
   * answer: a closed store or folder, or the library's connection exception (the
   * proxy that drops the line on a command it filters answers this way), or an I/O
   * error underneath. A {@code BAD} (unknown command) or {@code NO} (refused) is not.
   *
   * @param e what the library threw
   * @return true when the server was not reached or dropped the connection
   */
  private boolean isConnectionFailure(MessagingException e) {
    if (e instanceof StoreClosedException || e instanceof FolderClosedException) {
      return true;
    }
    Throwable cause = e.getCause();
    return cause instanceof ConnectionException || cause instanceof IOException;
  }

  /**
   * The typed failure for a command the server refused or could not run. The server's
   * text -- which names mailboxes and users -- is logged at DEBUG and carried as the
   * exception's detail, never as its message.
   *
   * @param command the IMAP command, for the log
   * @param mailbox the mailbox, for the log
   * @param e what the library threw
   * @return the exception to throw
   */
  private MailboxAclException refused(String command, String mailbox, MessagingException e) {
    LOG.debug("{} on '{}' was refused by the mail server: {}", command, mailbox, e.getMessage());
    return new MailboxAclException(MailboxAclException.SERVER_REFUSED, e);
  }
}
