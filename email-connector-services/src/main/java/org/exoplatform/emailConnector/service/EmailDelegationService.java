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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import javax.mail.Store;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.event.DelegatedFoldersDroppedEvent;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.model.DelegationGrantee;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.DiscoveredFolder;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderMessageCounts;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.GrantGranularity;
import org.exoplatform.emailConnector.model.GrantedDelegations;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailFolderView;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SharedMailbox;
import org.exoplatform.emailConnector.model.SharedMailboxEntry;
import org.exoplatform.emailConnector.model.SharedMailboxFolder;
import org.exoplatform.emailConnector.model.SharedMailboxSearchFolders;
import org.exoplatform.emailConnector.model.SharedMailboxSearchScope;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngine;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailDelegationStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Mailbox delegation: an owner shares their mailbox with another eXo user, the share
 * becomes an ACL on the mail server, the grantee subscribes and reads it with their own
 * session. This service owns the lifecycle -- invite, accept, decline, leave, revoke --
 * and the reading of shares that already exist on the server; the protocol is the
 * {@link MailboxAclEngine}'s, the rows the storage's.
 * <p>
 * <b>The security contract, as code.</b>
 * <ul>
 * <li><b>The mailbox is always the caller's own.</b> Every server operation runs on a
 * {@link MailboxAclSession} built from the caller's own connected setting -- the
 * engine opens the caller's own store or REST session through it, lazily, and nobody
 * else's ({@link #session}); the owner's mailbox identifier comes from the owner's
 * stored setting, never from a request.</li>
 * <li><b>The grantee is resolved server-side.</b> The request names an eXo username;
 * the identifier the ACL receives is read from that user's own connected setting on
 * the same connector preset ({@link #resolveGranteeIdentifier}). A user not connected
 * there cannot be granted to: eXo would have to trust a typed login.</li>
 * <li><b>Allowlist, then intersection.</b> A preset is expanded by the engine into its
 * server's vocabulary, capped by the engine's allowlist ({@link MailboxRights#GRANTABLE},
 * {@code lrswit}, on IMAP: never {@code a x e p k}) and by the owner's own MYRIGHTS,
 * which this service reads -- eXo never grants a right the owner does not hold. The
 * owner holding {@code a} is not a precondition: the server's answer to SETACL decides
 * (Stalwart grants an owner's SETACL without {@code a} in MYRIGHTS). The row records
 * what the engine says it wrote, in letters and in the server's own words
 * ({@code NATIVE_RIGHTS}).</li>
 * <li><b>No unattended identity switch.</b> The ACL is written at <i>invite</i>, on the
 * owner's own session and consented action; it is not written at accept, which would
 * mean running SETACL as the owner on the grantee's request thread with nobody at the
 * screen. The corollary is deliberate and visible on both sides: <b>declining does not
 * revoke</b> ({@link #decline}); only the owner (or an administrator) removes the ACL.</li>
 * <li><b>Rows are read with their viewer.</b> A grantee's operation loads
 * {@code (id, granteeId)}, an owner's {@code (id, ownerId)}; someone else's id is "not
 * found", so ids enumerate nothing.</li>
 * <li><b>The server is the backstop.</b> Whatever eXo gets wrong, the server enforces
 * the rights it holds; this service exists so that a refusal is a fixed code and never
 * a stack trace or the server's own text.</li>
 * </ul>
 * <p>
 * <b>What phase 0 settled, and how this class honours it</b> (delegation plan, section
 * 13, observed live on 2026-09-21/22). Capability is probed per session by attempting
 * the command, never by the CAPABILITY advertisement, and discovery never depends on
 * NAMESPACE being advertised -- both servers tested answered without advertising. The
 * owner does hold {@code a} on BlueMind; it is still read per session, not assumed.
 * Read state is <b>one</b> {@code \Seen} per message, shared by the owner and every
 * delegate on both servers -- eXo mirrors that bit by decision and synthesises no
 * per-delegate state; {@code s} means "seen changes are kept", for the mailbox.
 * "Accept" means two things: purely eXo-side on IMAP engines (Stalwart listed the
 * share immediately), a real server-side subscription on BlueMind (the share was
 * invisible until accepted) -- {@link #accept} calls the engine's subscribe hook when
 * its capabilities say so, before looking for the mailbox. BlueMind e-mails the owner
 * on every rights change itself: an owner-facing notification of an ACL change this
 * add-on might add would have to be gated on {@code capabilities.serverNotifiesOwner()}
 * being false -- none exists today. The notices of an answer (accepted, declined,
 * left) are not ACL changes and are never gated
 * ({@code EmailDelegationNotificationListener#notifyOwner}). What is still open is
 * named where it matters, in the engine.
 */
@Service
public class EmailDelegationService {

  private static final Log        LOG                        = ExoLogger.getLogger(EmailDelegationService.class);

  /** How many shared mailboxes one grantee may have accepted at once. */
  public static final String      MAX_PER_USER_PROPERTY      = "exo.email.delegation.maxPerUser";

  /** The default of {@link #MAX_PER_USER_PROPERTY}. */
  public static final int         DEFAULT_MAX_PER_USER       = 5;

  /**
   * How many folders of one shared mailbox a delegate's discovery registers, INBOX aside
   * (EXO-90548, plan S1.4): the roles first, then by name. A mailbox with more reports the
   * rest as not shown. Read from this JVM property, 50 by default -- the custom folders'
   * own cap.
   */
  public static final String      MAX_FOLDERS_PROPERTY       = "exo.email.delegation.maxFolders";

  /** The default of {@link #MAX_FOLDERS_PROPERTY}. */
  public static final int         DEFAULT_MAX_FOLDERS        = 50;

  /**
   * How often a shared mailbox's folders are discovered again from the periodic pass: at
   * most once per quarter-hour per share (plan S1.4, S1.7).
   */
  static final long               DISCOVERY_INTERVAL_MS      = 15L * 60 * 1000;

  /** The mailbox every grant is written on in this phase. */
  static final String             OWNER_INBOX                = MailFolder.INBOX;

  /** RFC 4314's wildcard identifier; never a grantee eXo maps to a person. */
  static final String             ANYONE                     = "anyone";

  // Message codes the interface translates (IllegalArgumentException -> 400).
  public static final String      SELF_MESSAGE               = "emailConnector.delegation.self";

  public static final String      GRANTEE_UNKNOWN_MESSAGE    = "emailConnector.delegation.granteeUnknown";

  public static final String      GRANTEE_NOT_CONNECTED_MESSAGE = "emailConnector.delegation.granteeNotConnected";

  public static final String      PRESET_INVALID_MESSAGE     = "emailConnector.delegation.presetInvalid";

  public static final String      ALREADY_SHARED_MESSAGE     = "emailConnector.delegation.alreadyShared";

  public static final String      NOT_ACCEPTABLE_MESSAGE     = "emailConnector.delegation.notAcceptable";

  public static final String      NOT_CHANGEABLE_MESSAGE     = "emailConnector.delegation.notChangeable";

  public static final String      NOT_PENDING_MESSAGE        = "emailConnector.delegation.notPending";

  public static final String      NOT_ACCEPTED_MESSAGE       = "emailConnector.delegation.notAccepted";

  public static final String      TOO_MANY_MESSAGE           = "emailConnector.delegation.tooMany";

  public static final String      NOT_FOUND_MESSAGE          = "emailConnector.delegation.notFound";

  /** No accepted share of the caller's has the name an agent gave (EXO-90555). */
  public static final String      SHARED_MAILBOX_NOT_FOUND_MESSAGE = "emailConnector.delegation.sharedMailboxNotFound";

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private EmailConnectorService   emailConnectorService;

  @Autowired
  private MailboxAclEngineRegistry aclEngineRegistry;

  @Autowired
  private EmailDelegationStorage  emailDelegationStorage;

  @Autowired
  private EmailFolderStorage      emailFolderStorage;

  // Read for the switcher's unread counts only (getSharedMailboxes): the storage of the
  // same add-on, and it depends on nothing of this service, so no cycle.
  @Autowired
  private EmailBoxStorage         emailBoxStorage;

  @Autowired(required = false)
  private IdentityManager         identityManager;

  @Autowired(required = false)
  private EmailCredentialsResolver emailCredentialsResolver;

  @Autowired
  private ApplicationEventPublisher eventPublisher;

  // ---------------------------------------------------------------------------------
  // The owner's side
  // ---------------------------------------------------------------------------------

  /**
   * Who has access to the caller's own mailbox: the server's ACL on their INBOX, read
   * live on their own session, each entry mapped to the eXo user connected on the same
   * preset with that identifier and to its delegation row when one exists. The server
   * is the truth: an identifier eXo never wrote appears (granted in the server's own
   * interface, or by an administrator) and gets an {@code AVAILABLE/SERVER} row when it
   * maps to a connected user, so the grantee's side offers it; a row whose identifier
   * the ACL no longer carries is moved to {@code REVOKED}. An identifier nobody eXo
   * knows holds is listed raw, with no row and no action.
   * <p>
   * Unsupported server: the answer says why and lists eXo's own rows only.
   *
   * @param ownerUsername the caller
   * @return the overview
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws MailboxAclException when the mailbox cannot be reached or refuses GETACL
   */
  public GrantedDelegations getGrantedDelegations(String ownerUsername) throws IllegalAccessException {
    UserEmailSetting ownerSetting = connectedSetting(ownerUsername);
    EmailConnector connector = connectorOf(ownerSetting);
    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    String ownerMailbox = mailboxIdentifier(ownerSetting);
    List<EmailDelegation> rows = emailDelegationStorage.getGranted(ownerUsername);
    try (MailboxAclSession session = session(connector, ownerUsername, ownerMailbox)) {
      MailboxAclCapabilities capabilities = engine.probe(session);
      if (!capabilities.supported()) {
        return new GrantedDelegations(capabilities, ownerMailbox, rowsOnly(rows));
      }
      List<MailboxAce> acl = engine.listAcl(session, OWNER_INBOX);
      List<DelegationGrantee> grantees = merge(ownerUsername, ownerMailbox, connector, acl, rows);
      if (capabilities.grantGranularity() == GrantGranularity.FOLDER
          && grantees.stream().anyMatch(grantee -> isExtendableHere(grantee.delegation(), connector, ownerMailbox))) {
        // What an Extend would add to each share eXo wrote (EXO-90548): the owner's role
        // folders as her session names them now, one LIST for the whole list, and only
        // when some share could be extended at all.
        Set<FolderRole> ownerRoles = roleFoldersOf(engine, session).keySet();
        grantees = grantees.stream()
                           .map(grantee -> isExtendableHere(grantee.delegation(), connector, ownerMailbox)
                                                                              ? grantee.withExtendableRoles(missingRoles(grantee.delegation(), ownerRoles))
                                                                              : grantee)
                           .toList();
      }
      return new GrantedDelegations(capabilities, ownerMailbox, grantees);
    }
  }

  /**
   * Shares the caller's mailbox with another eXo user: writes the ACL on the caller's
   * own INBOX, on the caller's own session, then records the invitation.
   * <p>
   * The grant happens HERE and not at accept -- see the class comment. The order is:
   * the grantee resolved from their own connected setting on the same preset; the
   * server probed; the owner's own MYRIGHTS read -- {@code a} is not required, the
   * server's answer to SETACL decides; the engine handed the preset and the owner's
   * rights, expanding the one and capping by the other in its server's own vocabulary
   * (letters on IMAP, a verb on BlueMind -- plan, section 3.4); then the row
   * ({@code PENDING/EXO}) recording what was written, reusing a declined, revoked,
   * gone or available row of the same key so the unique key holds and the history
   * stays on one row.
   * <p>
   * On a per-folder server the grant covers INBOX and, beside it, the owner's Sent,
   * Archive, Trash and Spam as the owner's own session names them, each with its role's
   * letters -- an Editor holds {@code e} where mail leaves, never on Trash (EXO-90548,
   * PO decisions Q-1, Q-2). INBOX is read back with GETACL: a server that accepted it
   * and does not name the grantee afterwards shared nothing and is said so. A folder
   * refused beside INBOX does not undo the share; the row records what was shared. On a
   * per-mailbox server (BlueMind) the grant is one call that covers every folder.
   *
   * @param ownerUsername the caller
   * @param granteeUsername the eXo user to share with
   * @param preset READER or EDITOR
   * @return the row as created
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws IllegalArgumentException with a message code when the grantee is the
   *           caller, unknown, not connected on the same preset, the preset is not
   *           grantable, or the share already stands
   * @throws MailboxAclException when the server does not support ACLs, nothing is left
   *           to grant, or SETACL is refused
   */
  public EmailDelegation invite(String ownerUsername, String granteeUsername, DelegationPreset preset) throws IllegalAccessException {
    if (preset == null || !preset.isGrantable()) {
      throw new IllegalArgumentException(PRESET_INVALID_MESSAGE);
    }
    if (StringUtils.isBlank(granteeUsername) || granteeUsername.equals(ownerUsername)) {
      throw new IllegalArgumentException(SELF_MESSAGE);
    }
    UserEmailSetting ownerSetting = connectedSetting(ownerUsername);
    EmailConnector connector = connectorOf(ownerSetting);
    String ownerMailbox = mailboxIdentifier(ownerSetting);
    String granteeIdentifier = resolveGranteeIdentifier(granteeUsername, connector.getId());

    EmailDelegation existing = emailDelegationStorage.getByKey(granteeUsername, connector.getId(), ownerMailbox);
    if (existing != null && (existing.getStatus() == DelegationStatus.PENDING || existing.getStatus() == DelegationStatus.ACCEPTED)) {
      throw new IllegalArgumentException(ALREADY_SHARED_MESSAGE);
    }

    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    MailboxAce written;
    String grantedRoles;
    Map<FolderRole, String> roleFolders = new EnumMap<>(FolderRole.class);
    try (MailboxAclSession session = session(connector, ownerUsername, ownerMailbox)) {
      MailboxAclCapabilities capabilities = engine.probe(session);
      requireSupported(capabilities);
      MailboxRights ownerRights = engine.myRights(session, OWNER_INBOX);
      if (!ownerRights.canAdminister()) {
        // NOT a refusal: 'a' is a positive signal, never a precondition -- the same
        // lesson the capability probe learned, met a second time on the same rig.
        // BlueMind answers lrswipkxtea, so the owner holds 'a' there; Stalwart 0.11.8
        // answers rliteswkxp for the owner OF THAT VERY MAILBOX -- no 'a' at all --
        // and then accepts her SETACL perfectly well (verified: the phase-0 grant to
        // bob was made exactly that way). Refusing here told the owner of a mailbox
        // she could not share her own mailbox, on a server that was willing.
        // So: try the command. A server that really does refuse answers the SETACL,
        // and engine.grant turns that into the refusal the user reads.
        LOG.debug("{} holds no administer right on their own INBOX ({}); granting anyway, the server decides",
                  ownerUsername,
                  ownerRights.letters());
      }
      written = recorded(engine, session, granteeIdentifier, engine.grant(session, OWNER_INBOX, granteeIdentifier, preset, ownerRights));
      // Beside INBOX, the owner's Sent, Archive, Trash and Spam (EXO-90548, PO decision
      // Q-2), each with its role's letters; one call on a per-mailbox server.
      if (capabilities.grantGranularity() == GrantGranularity.MAILBOX) {
        grantedRoles = EmailDelegation.GRANTED_WHOLE_MAILBOX;
      } else {
        roleFolders = roleFoldersOf(engine, session);
        grantedRoles = EmailDelegation.grantedRolesOf(grantRoleFolders(engine, session, granteeIdentifier, preset, FolderRole.GRANTED, roleFolders));
      }
    }
    MailboxRights granted = written.rights() == null ? MailboxRights.NONE : written.rights();

    Date now = new Date();
    EmailDelegation delegation = existing == null ? new EmailDelegation() : existing;
    delegation.setGrantedRoles(grantedRoles);
    delegation.setOwnerRoleFolders(roleFolders);
    delegation.setGranteeId(granteeUsername);
    delegation.setOwnerId(ownerUsername);
    delegation.setOwnerMailbox(ownerMailbox);
    delegation.setGranteeMailbox(granteeIdentifier);
    delegation.setConnectorId(connector.getId());
    // What was granted, not what was asked -- an Editor capped to lrs by the owner's
    // own rights is recorded a Reader; letters no preset reads as keep the choice.
    delegation.setPreset(written.preset() == null || written.preset() == DelegationPreset.CUSTOM ? preset : written.preset());
    delegation.setRights(granted.letters());
    delegation.setNativeRights(written.nativeRights());
    delegation.setStatus(DelegationStatus.PENDING);
    delegation.setOrigin(DelegationOrigin.EXO);
    delegation.setInvitedDate(now);
    delegation.setRespondedDate(null);
    delegation.setRevokedDate(null);
    delegation.setLastRightsCheckDate(now);
    delegation = existing == null ? emailDelegationStorage.create(delegation) : emailDelegationStorage.update(delegation);
    if (existing != null) {
      // A re-grant of a row the grantee may still have registered: their folders follow
      // at the next pass.
      markDiscoveryDue(delegation.getId());
    }
    LOG.info("Mailbox delegation granted: actor={} ownerMailbox={} grantee={} identifier={} rights={} folders={}",
             ownerUsername,
             ownerMailbox,
             granteeUsername,
             granteeIdentifier,
             granted.letters(),
             grantedRoles);
    publish(EmailDelegationEvent.Type.INVITED, ownerUsername, delegation);
    return delegation;
  }

  /**
   * Removes a grantee's access: DELETEACL on the caller's own INBOX, on the caller's
   * own session, for the identifier the grant was written to (kept on the row, so this
   * works after the grantee disconnected their own mailbox from eXo) -- then on every
   * other folder of the caller whose ACL names that identifier, including an entry made
   * in another mail application, and on the folders the grant recorded (EXO-90548). The
   * row goes {@code REVOKED} and the grantee's registered folders of this mailbox are
   * dropped, the mail mirrored under them with them ({@link #dropDelegatedFolders}).
   *
   * @param ownerUsername the caller
   * @param id the delegation id
   * @throws ObjectNotFoundException when no such row belongs to the caller as owner
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws IllegalArgumentException {@code emailConnector.delegation.notChangeable} for a
   *           row of a mailbox the caller is no longer connected to
   * @throws MailboxAclException when the server refuses DELETEACL
   */
  public void revoke(String ownerUsername, long id) throws ObjectNotFoundException, IllegalAccessException {
    EmailDelegation delegation = asOwner(ownerUsername, id);
    UserEmailSetting ownerSetting = connectedSetting(ownerUsername);
    EmailConnector connector = connectorOf(ownerSetting);
    if (!connector.getId().equals(delegation.getConnectorId())
        || !mailboxIdentifier(ownerSetting).equalsIgnoreCase(delegation.getOwnerMailbox())) {
      // A row of a mailbox the owner is no longer connected to (EXO-90557): its DELETEACL
      // would remove the grantee's entry from the mailbox connected NOW, which this row
      // never covered.
      throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
    }
    String identifier = StringUtils.isNotBlank(delegation.getGranteeMailbox()) ? delegation.getGranteeMailbox()
                                                                                : resolveGranteeIdentifierOrNull(delegation.getGranteeId(),
                                                                                                                 connector.getId());
    if (identifier != null && delegation.getStatus() != DelegationStatus.REVOKED) {
      MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
      try (MailboxAclSession session = session(connector, ownerUsername, mailboxIdentifier(ownerSetting))) {
        requireSupported(engine.probe(session));
        engine.revoke(session, OWNER_INBOX, identifier);
        revokeOtherFolders(engine, session, identifier, delegation);
      }
    }
    delegation.setStatus(DelegationStatus.REVOKED);
    delegation.setRevokedDate(new Date());
    // Back off by default (#441-1): a share taken up again must be chosen again to count
    // in the badge, as a new one is (plan 7.7).
    delegation.setBadgeIncluded(false);
    delegation = emailDelegationStorage.update(delegation);
    dropDelegatedFolders(delegation.getGranteeId(), delegation.getId());
    LOG.info("Mailbox delegation revoked: actor={} ownerMailbox={} grantee={} identifier={}",
             ownerUsername,
             delegation.getOwnerMailbox(),
             delegation.getGranteeId(),
             identifier);
    publish(EmailDelegationEvent.Type.REVOKED, ownerUsername, delegation);
  }

  /**
   * Changes the access a grantee holds on the caller's own mailbox to another preset --
   * the owner's "Change access": Reader to Editor and back, or a share whose letters no
   * preset names (set in the mail server's own interface) normalised to one.
   * <p>
   * The same write as {@link #invite}, on the same session and through the same engine
   * call: {@link MailboxAclEngine#grant} REPLACES the identifier's entry (RFC 4314 SETACL
   * sets the rights, it does not add to them), expands the preset into the server's own
   * vocabulary and caps it by the owner's rights -- no letters are written here, which
   * keeps a per-mailbox engine (BlueMind, phase 1b) a matter of its own grant. What is
   * recorded is what the engine says it wrote, and only that: the status, the dates and
   * the grantee's toggles are left as they stand -- an accepted share stays accepted, a
   * pending invitation stays pending with its new rights, and a leave made while the
   * server was asked stays a leave. A share revoked or gone meanwhile is refused as
   * not changeable.
   * <p>
   * The owner's other shared folders follow (EXO-90548), each with its role's letters.
   * When the change narrows the access (to Reader), a folder that refuses the narrower
   * letters has the grantee's entry removed instead; one that refuses that too keeps the
   * wider access, and the call ends with {@code NOT_NARROWED} after recording the rest,
   * so the owner is told rather than shown a Reader that is not one.
   * <p>
   * Owner only, and only the owner's own rows: the row is resolved with the caller as
   * owner, so a delegate -- or anybody else -- asking gets "no such delegation", which
   * does not even say the row exists. A share no longer on the server (revoked, gone)
   * has nothing to change. The grantee is not notified: nothing on this server tells
   * them either, and their mailbox's controls follow the stored rights at their next
   * reading of the share.
   *
   * @param ownerUsername the caller, the mailbox's owner
   * @param id the delegation id
   * @param preset READER or EDITOR
   * @return the row as it now stands
   * @throws ObjectNotFoundException when no such row belongs to the caller as owner
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws IllegalArgumentException {@code emailConnector.delegation.presetInvalid} for a
   *           preset that is not grantable, {@code emailConnector.delegation.notChangeable}
   *           for a share that is no longer on the server, before the write or after it
   * @throws MailboxAclException when the server refuses or cannot be asked
   */
  public EmailDelegation changePreset(String ownerUsername, long id, DelegationPreset preset) throws ObjectNotFoundException,
                                                                                                IllegalAccessException {
    if (preset == null || !preset.isGrantable()) {
      throw new IllegalArgumentException(PRESET_INVALID_MESSAGE);
    }
    EmailDelegation delegation = asOwner(ownerUsername, id);
    if (delegation.getStatus() == DelegationStatus.REVOKED || delegation.getStatus() == DelegationStatus.GONE) {
      throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
    }
    UserEmailSetting ownerSetting = connectedSetting(ownerUsername);
    EmailConnector connector = connectorOf(ownerSetting);
    String ownerMailbox = mailboxIdentifier(ownerSetting);
    if (!connector.getId().equals(delegation.getConnectorId()) || !ownerMailbox.equalsIgnoreCase(delegation.getOwnerMailbox())) {
      // A row of a mailbox the owner is no longer connected to: writing it would grant
      // the grantee access to the mailbox the owner is connected to NOW, which this row
      // never covered.
      throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
    }
    String identifier = StringUtils.isNotBlank(delegation.getGranteeMailbox()) ? delegation.getGranteeMailbox()
                                                                                : resolveGranteeIdentifier(delegation.getGranteeId(),
                                                                                                           connector.getId());
    boolean keptSeen = delegation.getMailboxRights().canKeepSeen();
    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    MailboxAce written;
    Set<FolderRole> kept = delegation.grantedRoleSet();
    Set<FolderRole> notNarrowed = EnumSet.noneOf(FolderRole.class);
    Map<FolderRole, String> roleFolders = new EnumMap<>(FolderRole.class);
    if (delegation.getOwnerRoleFolders() != null) {
      roleFolders.putAll(delegation.getOwnerRoleFolders());
    }
    // Only the folders eXo's own grant covered: a folder it never shared keeps whatever
    // was decided for it elsewhere.
    Map<FolderRole, String> recordedFolders = new EnumMap<>(FolderRole.class);
    roleFolders.forEach((role, folder) -> {
      if (kept.contains(role)) {
        recordedFolders.put(role, folder);
      }
    });
    try (MailboxAclSession session = session(connector, ownerUsername, ownerMailbox)) {
      requireSupported(engine.probe(session));
      written = engine.grant(session, OWNER_INBOX, identifier, preset, engine.myRights(session, OWNER_INBOX));
      if (!kept.isEmpty()) {
        // The owner's folders as they are named NOW: a Trash renamed since the grant
        // would otherwise be written at a name that no longer exists, every retry.
        roleFolders.putAll(roleFoldersOf(engine, session));
      }
      // The owner's other shared folders follow the new preset (EXO-90548): a Reader
      // left an Editor on Trash would be a share wider than the one the owner reads.
      Set<FolderRole> changed = grantRoleFolders(engine, session, identifier, preset, kept, roleFolders);
      if (preset == DelegationPreset.READER) {
        // A folder that would not take the narrower access loses the access altogether
        // rather than keep the wider one; one that refuses both is said to the owner.
        for (FolderRole role : new ArrayList<>(kept)) {
          if (changed.contains(role)) {
            continue;
          }
          if (revokeRoleFolder(engine, session, identifier, role, roleFolders)) {
            kept.remove(role);
          } else {
            notNarrowed.add(role);
          }
        }
        notNarrowed.addAll(narrowFormerRoleFolders(engine, session, identifier, recordedFolders, roleFolders));
      }
    }
    MailboxRights granted = written.rights() == null ? MailboxRights.NONE : written.rights();
    DelegationPreset recorded = written.preset() == null || written.preset() == DelegationPreset.CUSTOM ? preset : written.preset();
    // A share recorded per folder keeps its folder roles in step with what was written;
    // a whole-mailbox (or older) share has none, and they are left untouched.
    boolean perFolder = delegation.getGrantedRoles() != null && !delegation.grantsWholeMailbox();
    // Only what was written on the server, and not over a share that ended while the
    // server was asked: the row read above is as old as the SETACL round-trip, and a
    // whole-row write from it would undo a leave made meanwhile (stack review N-1).
    delegation = perFolder ? emailDelegationStorage.updateGrantedRights(ownerUsername,
                                                                       id,
                                                                       recorded,
                                                                       granted.letters(),
                                                                       written.nativeRights(),
                                                                       identifier,
                                                                       new Date(),
                                                                       EmailDelegation.grantedRolesOf(kept),
                                                                       roleFolders)
                           : emailDelegationStorage.updateGrantedRights(ownerUsername,
                                                                       id,
                                                                       recorded,
                                                                       granted.letters(),
                                                                       written.nativeRights(),
                                                                       identifier,
                                                                       new Date());
    if (delegation == null) {
      // Revoked or gone meanwhile. The owner's next reconcile reads the server's ACL
      // and offers the share again if this grant landed after the revoke.
      throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
    }
    // The grantee's folders follow at their next pass, not a quarter-hour later.
    markDiscoveryDue(id);
    LOG.info("Mailbox delegation changed: actor={} ownerMailbox={} grantee={} identifier={} rights={}",
             ownerUsername,
             delegation.getOwnerMailbox(),
             delegation.getGranteeId(),
             identifier,
             granted.letters());
    if (keptSeen != granted.canKeepSeen()) {
      // The grantee's badge may count this inbox only while s is held (EXO-90546).
      publish(EmailDelegationEvent.Type.RIGHTS_CHANGED, ownerUsername, delegation);
    }
    if (!notNarrowed.isEmpty()) {
      // Said, not swallowed: the owner asked for less access than a folder still gives.
      throw new MailboxAclException(MailboxAclException.NOT_NARROWED, "roles " + notNarrowed);
    }
    return delegation;
  }

  /**
   * Shares with the grantee the owner's folders a share written before EXO-90548 left
   * out -- Sent, Archive, Trash and Spam, beside the INBOX it already covers -- with the
   * share's own preset: the owner's "Extend access". The share must still be on INBOX --
   * one the owner removed in another mail application is not revived
   * ({@code notChangeable}). INBOX is then granted again, to the preset's letters of
   * today (an Editor now holds {@code e} there), and read back ({@code NOT_RECORDED}
   * when the server does not record it). Owner only, on the owner's own
   * session, and only for a share eXo wrote: a share made in the mail server's own
   * interface is never rewritten by eXo. Nothing is widened silently: this is the owner's
   * explicit act, answered with the consent text of the drawer that asks it (plan,
   * section S1.3, PO decision Q-4).
   *
   * @param ownerUsername the caller, the mailbox's owner
   * @param id the delegation id
   * @return the row as it now stands
   * @throws ObjectNotFoundException when no such row belongs to the caller as owner
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws IllegalArgumentException {@code emailConnector.delegation.notChangeable} for a
   *           share that is neither accepted nor pending, is no longer on the server (by
   *           its row, or by the INBOX ACL), was not written by eXo, has no preset, is on
   *           another mailbox than the one connected, or is on a server that grants a whole
   *           mailbox at once; when the owner's mailbox has no default role folder left to
   *           add; and when the share was revoked or went while the server was being asked
   * @throws MailboxAclException when the server cannot be asked, no longer holds the
   *           share on INBOX ({@code NOT_RECORDED}), or refused every folder there was
   *           to add ({@code SERVER_REFUSED})
   */
  public EmailDelegation extend(String ownerUsername, long id) throws ObjectNotFoundException, IllegalAccessException {
    EmailDelegation delegation = asOwner(ownerUsername, id);
    // Only a share in use or on offer (decision 3b): a declined or merely available
    // share is extended by inviting again, a revoked or gone one not at all.
    if (!isExtendable(delegation)) {
      throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
    }
    UserEmailSetting ownerSetting = connectedSetting(ownerUsername);
    EmailConnector connector = connectorOf(ownerSetting);
    String ownerMailbox = mailboxIdentifier(ownerSetting);
    if (!connector.getId().equals(delegation.getConnectorId()) || !ownerMailbox.equalsIgnoreCase(delegation.getOwnerMailbox())) {
      throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
    }
    String identifier = StringUtils.isNotBlank(delegation.getGranteeMailbox()) ? delegation.getGranteeMailbox()
                                                                                : resolveGranteeIdentifier(delegation.getGranteeId(),
                                                                                                           connector.getId());
    Set<FolderRole> granted = delegation.grantedRoleSet();
    Map<FolderRole, String> roleFolders = new EnumMap<>(FolderRole.class);
    if (delegation.getOwnerRoleFolders() != null) {
      roleFolders.putAll(delegation.getOwnerRoleFolders());
    }
    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    MailboxAce written;
    boolean keptSeen = delegation.getMailboxRights().canKeepSeen();
    try (MailboxAclSession session = session(connector, ownerUsername, ownerMailbox)) {
      MailboxAclCapabilities capabilities = engine.probe(session);
      requireSupported(capabilities);
      if (capabilities.grantGranularity() != GrantGranularity.FOLDER) {
        throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
      }
      // What there is to add, read before anything is written: the default roles her
      // mailbox has and the share does not cover yet (EXO-90548). Nothing to add is a
      // refusal, not an INBOX rewritten for nothing.
      Map<FolderRole, String> current = roleFoldersOf(engine, session);
      roleFolders.putAll(current);
      List<FolderRole> missing = missingRoles(delegation, current.keySet());
      if (missing.isEmpty()) {
        throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
      }
      // Still on the server? A share the owner removed in another mail application is
      // not revived by "Extend access": eXo never rewrites what was decided there.
      requireOnInbox(engine, session, identifier);
      // INBOX first, to the preset's letters of today -- a phase-1 Editor held no e
      // there, and without it a delete from the shared INBOX leaves the original
      // behind (PO decision Q-1). Read back, as a grant is.
      written = recorded(engine,
                         session,
                         identifier,
                         engine.grant(session, OWNER_INBOX, identifier, delegation.getPreset(), engine.myRights(session, OWNER_INBOX)));
      Set<FolderRole> added = grantRoleFolders(engine, session, identifier, delegation.getPreset(), EnumSet.copyOf(missing), roleFolders);
      if (missing.stream().noneMatch(added::contains)) {
        // The server refused every folder there was to add: said as the refusal it is,
        // never recorded or answered as a success (EXO-90548 review).
        throw new MailboxAclException(MailboxAclException.SERVER_REFUSED, "roles " + missing);
      }
      granted.addAll(added);
    }
    MailboxRights inboxRights = written.rights() == null ? MailboxRights.NONE : written.rights();
    DelegationPreset recorded = written.preset() == null || written.preset() == DelegationPreset.CUSTOM ? delegation.getPreset()
                                                                                                        : written.preset();
    // What the grants wrote, and only that, as changePreset records it (stack review
    // N-1): the row read above is as old as the ACL round-trips, and a whole-row write
    // from it would undo a leave or a revoke made meanwhile.
    delegation = emailDelegationStorage.updateGrantedRights(ownerUsername,
                                                           id,
                                                           recorded,
                                                           inboxRights.letters(),
                                                           written.nativeRights(),
                                                           identifier,
                                                           new Date(),
                                                           EmailDelegation.grantedRolesOf(granted),
                                                           roleFolders);
    if (delegation == null) {
      // Revoked or gone meanwhile: the owner's next reconcile reads the server's ACL.
      throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
    }
    // The folders just shared show at the grantee's next pass, not a quarter-hour later
    // (live on Stalwart: an Extend left the delegate on the Inbox alone).
    markDiscoveryDue(id);
    if (keptSeen != inboxRights.canKeepSeen()) {
      publish(EmailDelegationEvent.Type.RIGHTS_CHANGED, ownerUsername, delegation);
    }
    LOG.info("Mailbox delegation extended: actor={} ownerMailbox={} grantee={} identifier={} folders={}",
             ownerUsername,
             delegation.getOwnerMailbox(),
             delegation.getGranteeId(),
             identifier,
             delegation.getGrantedRoles());
    return delegation;
  }

  /**
   * Whether "Extend access" can act on a share at all (EXO-90548): one eXo wrote, in use
   * or on offer (decision 3b), with a preset eXo grants, and recorded per folder -- a
   * grant of a whole mailbox at once already covers every folder.
   *
   * @param delegation the share, or null
   * @return true when an Extend may be asked for it
   */
  private static boolean isExtendable(EmailDelegation delegation) {
    return delegation != null
        && (delegation.getStatus() == DelegationStatus.ACCEPTED || delegation.getStatus() == DelegationStatus.PENDING)
        && delegation.getOrigin() == DelegationOrigin.EXO && delegation.getPreset() != null && delegation.getPreset().isGrantable()
        && !delegation.grantsWholeMailbox();
  }

  /**
   * {@link #isExtendable} for the mailbox the owner is connected to now: extend refuses a
   * row of another connector or mailbox, so the list never offers it (EXO-90548 review).
   *
   * @param delegation the share, or null
   * @param connector the owner's connector
   * @param ownerMailbox the owner's mailbox address
   * @return true when an Extend of it can succeed on this mailbox
   */
  private static boolean isExtendableHere(EmailDelegation delegation, EmailConnector connector, String ownerMailbox) {
    return isExtendable(delegation) && connector.getId().equals(delegation.getConnectorId())
        && ownerMailbox.equalsIgnoreCase(delegation.getOwnerMailbox());
  }

  /**
   * The default roles (Sent, Archive, Trash, Spam -- never Drafts) the owner's mailbox has
   * and a share does not cover yet, in the order they are granted: exactly what an
   * Extend adds (EXO-90548).
   *
   * @param delegation the share
   * @param ownerRoles the roles the owner's mailbox has now
   * @return the roles to add, possibly empty
   */
  private static List<FolderRole> missingRoles(EmailDelegation delegation, Set<FolderRole> ownerRoles) {
    Set<FolderRole> granted = delegation.grantedRoleSet();
    return FolderRole.GRANTED.stream().filter(ownerRoles::contains).filter(role -> !granted.contains(role)).toList();
  }

  /**
   * Refuses to act on a share the INBOX ACL no longer names: removed in another mail
   * application while its row still says otherwise. An ACL that cannot be read decides
   * nothing here; the grant's own read-back follows.
   *
   * @param engine the engine
   * @param session the owner's session
   * @param identifier the grantee as the server names them
   * @throws IllegalArgumentException {@code notChangeable} when the ACL does not name them
   */
  private void requireOnInbox(MailboxAclEngine engine, MailboxAclSession session, String identifier) {
    List<MailboxAce> acl;
    try {
      acl = engine.listAcl(session, OWNER_INBOX);
    } catch (MailboxAclException e) {
      LOG.debug("The ACL could not be read before extending a share ({})", e.getCode());
      return;
    }
    boolean named = acl.stream().anyMatch(ace -> ace != null && identifier != null && identifier.equalsIgnoreCase(ace.identifier()));
    if (!named) {
      throw new IllegalArgumentException(NOT_CHANGEABLE_MESSAGE);
    }
  }

  /**
   * The entry the server holds for the grantee after a grant, read back with GETACL on
   * INBOX -- the letters recorded are the server's, not eXo's wish. A server that
   * accepted the SETACL and does not name the grantee afterwards shared nothing, and is
   * said so ({@code NOT_RECORDED}), whatever it answered; a server whose ACL cannot be
   * read back leaves the entry as written (EXO-90548).
   *
   * @param engine the engine
   * @param session the owner's session
   * @param identifier the grantee as the server names them
   * @param written what the grant wrote
   * @return the entry as the server holds it
   * @throws MailboxAclException {@code NOT_RECORDED} when the ACL does not name the grantee
   */
  private MailboxAce recorded(MailboxAclEngine engine, MailboxAclSession session, String identifier, MailboxAce written) {
    List<MailboxAce> acl;
    try {
      acl = engine.listAcl(session, OWNER_INBOX);
    } catch (MailboxAclException e) {
      LOG.debug("The ACL could not be read back after a grant ({}); keeping what was written", e.getCode());
      return written;
    }
    for (MailboxAce ace : acl) {
      if (ace != null && identifier != null && identifier.equalsIgnoreCase(ace.identifier())) {
        return ace;
      }
    }
    throw new MailboxAclException(MailboxAclException.NOT_RECORDED, identifier);
  }

  /**
   * The owner's folders by role, from the owner's session. A server that cannot say is
   * no reason to fail the share INBOX already holds: none are shared beside it.
   *
   * @param engine the engine
   * @param session the owner's session
   * @return the folder name of each role found, never null
   */
  private Map<FolderRole, String> roleFoldersOf(MailboxAclEngine engine, MailboxAclSession session) {
    Map<FolderRole, String> roleFolders = new EnumMap<>(FolderRole.class);
    try {
      roleFolders.putAll(engine.findRoleFolders(session));
    } catch (MailboxAclException e) {
      LOG.info("The owner's folders could not be read ({}); only INBOX is shared", e.getCode());
    }
    return roleFolders;
  }

  /**
   * Grants a preset on the owner's folder of each role, with that role's letters, and
   * answers the roles the server accepted. One folder refused -- the server's NO, or
   * nothing left once capped by the owner's rights on it -- does not undo the others nor
   * INBOX: the row records what was actually shared, and the owner's list says what
   * could not be.
   *
   * @param engine the engine
   * @param session the owner's session
   * @param identifier the grantee as the server names them
   * @param preset READER or EDITOR
   * @param roles the roles to grant
   * @param roleFolders the owner's folder of each role
   * @return the roles granted, never null
   */
  private Set<FolderRole> grantRoleFolders(MailboxAclEngine engine,
                                           MailboxAclSession session,
                                           String identifier,
                                           DelegationPreset preset,
                                           Collection<FolderRole> roles,
                                           Map<FolderRole, String> roleFolders) {
    Set<FolderRole> granted = EnumSet.noneOf(FolderRole.class);
    for (FolderRole role : roles == null ? List.<FolderRole> of() : roles) {
      String folder = roleFolders == null ? null : roleFolders.get(role);
      if (StringUtils.isBlank(folder) || role == FolderRole.DRAFTS) {
        continue;
      }
      try {
        engine.grant(session, folder, identifier, preset, engine.myRights(session, folder), role);
        granted.add(role);
      } catch (MailboxAclException e) {
        LOG.info("The owner's {} folder could not be shared ({})", role, e.getCode());
      }
    }
    return granted;
  }

  /**
   * Narrowing's second pass: the folder a grant was recorded on, when a role now names
   * another folder -- the role moved (another folder became the Trash) rather than the
   * folder being renamed, which carries its ACL with it. That folder still holds eXo's
   * own wider grant: it is narrowed to Reader, else the grantee's entry removed. A
   * recorded folder whose ACL no longer names the grantee -- the rename case, or a folder
   * gone -- needs nothing; one whose ACL cannot be read is said not narrowed.
   *
   * @param engine the engine
   * @param session the owner's session
   * @param identifier the grantee as the server names them
   * @param recordedFolders the folders eXo's grant covered, by role, as recorded
   * @param currentFolders the role folders as the owner's session names them now
   * @return the roles whose former folder keeps the wider access, never null
   */
  private Set<FolderRole> narrowFormerRoleFolders(MailboxAclEngine engine,
                                                  MailboxAclSession session,
                                                  String identifier,
                                                  Map<FolderRole, String> recordedFolders,
                                                  Map<FolderRole, String> currentFolders) {
    Set<FolderRole> notNarrowed = EnumSet.noneOf(FolderRole.class);
    Map<FolderRole, String> moved = new EnumMap<>(FolderRole.class);
    recordedFolders.forEach((role, folder) -> {
      if (StringUtils.isNotBlank(folder) && !folder.equals(currentFolders.get(role))) {
        moved.put(role, folder);
      }
    });
    if (moved.isEmpty()) {
      return notNarrowed;
    }
    moved.forEach((role, folder) -> {
      boolean named;
      try {
        // That one folder, asked directly: exact, and never cut off by a folder cap.
        named = engine.listAcl(session, folder)
                      .stream()
                      .anyMatch(ace -> ace != null && identifier != null && identifier.equalsIgnoreCase(ace.identifier()));
      } catch (MailboxAclException e) {
        LOG.warn("The owner's former {} folder could not be checked while narrowing an access ({})", role, e.getCode());
        notNarrowed.add(role);
        return;
      }
      if (!named) {
        return;
      }
      try {
        engine.grant(session, folder, identifier, DelegationPreset.READER, engine.myRights(session, folder), role);
      } catch (MailboxAclException e) {
        if (!revokeRoleFolder(engine, session, identifier, role, Map.of(role, folder))) {
          notNarrowed.add(role);
        }
      }
    });
    return notNarrowed;
  }

  /**
   * Removes the grantee's entry from the owner's folder of one role.
   *
   * @param engine the engine
   * @param session the owner's session
   * @param identifier the grantee as the server names them
   * @param role the role
   * @param roleFolders the owner's folder of each role
   * @return true when the entry is gone (or there is no such folder), false when the
   *         server refused
   */
  private boolean revokeRoleFolder(MailboxAclEngine engine,
                                   MailboxAclSession session,
                                   String identifier,
                                   FolderRole role,
                                   Map<FolderRole, String> roleFolders) {
    String folder = roleFolders == null ? null : roleFolders.get(role);
    if (StringUtils.isBlank(folder)) {
      return true;
    }
    try {
      engine.revoke(session, folder, identifier);
      return true;
    } catch (MailboxAclException e) {
      LOG.warn("The grantee's entry on the owner's {} folder could not be removed ({})", role, e.getCode());
      return false;
    }
  }

  /**
   * "Remove access" beyond INBOX: every other folder of the owner whose ACL names the
   * grantee -- including an entry written in another mail application -- and the folders
   * the grant recorded, in case the ACLs cannot be listed. A folder that refuses is
   * logged and left; INBOX, which is what the share is, has already gone.
   *
   * @param engine the engine
   * @param session the owner's session
   * @param identifier the grantee as the server names them
   * @param delegation the row, for the folders its grant recorded
   */
  private void revokeOtherFolders(MailboxAclEngine engine, MailboxAclSession session, String identifier, EmailDelegation delegation) {
    Set<String> folders = new LinkedHashSet<>();
    try {
      folders.addAll(engine.foldersHolding(session, identifier));
    } catch (MailboxAclException e) {
      LOG.debug("The owner's folders holding an access could not be listed ({})", e.getCode());
    }
    Map<FolderRole, String> roleFolders = delegation.getOwnerRoleFolders();
    for (FolderRole role : delegation.grantedRoleSet()) {
      if (roleFolders != null && StringUtils.isNotBlank(roleFolders.get(role))) {
        folders.add(roleFolders.get(role));
      }
    }
    for (String folder : folders) {
      if (OWNER_INBOX.equalsIgnoreCase(folder)) {
        continue;
      }
      try {
        engine.revoke(session, folder, identifier);
      } catch (MailboxAclException e) {
        LOG.warn("An access on one of the owner's folders could not be removed ({})", e.getCode());
      }
    }
  }

  // ---------------------------------------------------------------------------------
  // The grantee's side
  // ---------------------------------------------------------------------------------

  /**
   * The mailboxes shared with the caller: eXo's rows, and -- when asked and when the
   * caller's own mailbox can be reached -- the Other Users namespace of the caller's
   * own session, so a share granted outside eXo gets an {@code AVAILABLE/SERVER} row
   * (owner mapped to an eXo user when one is connected with that identifier) and an
   * accepted share whose mailbox is no longer listed goes {@code GONE}. Proposed, never
   * auto-subscribed.
   * <p>
   * Discovery is best-effort: an unreachable or unsupported server leaves the rows as
   * they are, logged at DEBUG.
   *
   * @param granteeUsername the caller
   * @param discover whether to walk the server's namespace too
   * @return the rows, most recently changed first, never null
   */
  public List<EmailDelegation> getReceivedDelegations(String granteeUsername, boolean discover) {
    if (discover) {
      try {
        discoverShares(granteeUsername);
      } catch (IllegalAccessException | MailboxAclException e) {
        LOG.debug("Shared mailboxes of {} could not be discovered on the server: {}", granteeUsername, e.getMessage());
      }
    }
    return emailDelegationStorage.getReceived(granteeUsername);
  }

  /**
   * Accepts a share: on the caller's own session, performs the server's own acceptance
   * step where the engine says there is one (BlueMind's subscription, run as the
   * caller -- and run first, because the share may not be listed before it; plan,
   * sections 5.2 and 13.B.14; nothing at all on an IMAP engine, where "Accept" is
   * purely eXo-side), then finds the owner's mailbox under the Other Users namespace
   * and reads MYRIGHTS on its INBOX. The server's answer decides:
   * access confirmed, the row goes {@code ACCEPTED} with the path and the letters, and
   * the shared INBOX is registered as a {@code DELEGATED_INBOX} folder of the caller;
   * no access (revoked in between), the row goes {@code REVOKED} and the caller is told
   * so. A declined or available row is accepted the same way -- the MYRIGHTS check at
   * that moment is what decides, so a share the owner removed meanwhile is reported
   * honestly.
   * <p>
   * The folder is registered opted in: accepting is the delegate asking for the mailbox,
   * and the delegated sync (window cap, activity gate, no new-mail broadcast) mirrors it
   * from the next pass the delegate is in it. The custom-folder rotation never picks it
   * up, since its queries exclude delegated rows.
   *
   * @param granteeUsername the caller
   * @param id the delegation id
   * @return the row as it now stands
   * @throws ObjectNotFoundException when no such row belongs to the caller as grantee
   * @throws IllegalAccessException when the caller has no connected mailbox on the
   *           row's preset
   * @throws IllegalArgumentException with a message code when the row is not in a state
   *           that can be accepted, or the caller reached the cap
   * @throws DelegationRevokedException when the server no longer grants the access
   * @throws MailboxAclException when the server cannot be asked
   */
  public EmailDelegation accept(String granteeUsername, long id) throws ObjectNotFoundException, IllegalAccessException {
    EmailDelegation delegation = asGrantee(granteeUsername, id);
    DelegationStatus status = delegation.getStatus();
    if (status != DelegationStatus.PENDING && status != DelegationStatus.DECLINED && status != DelegationStatus.AVAILABLE) {
      throw new IllegalArgumentException(NOT_ACCEPTABLE_MESSAGE);
    }
    if (emailDelegationStorage.count(granteeUsername, DelegationStatus.ACCEPTED) >= getMaxPerUser()) {
      throw new IllegalArgumentException(TOO_MANY_MESSAGE);
    }
    UserEmailSetting granteeSetting = connectedSetting(granteeUsername);
    EmailConnector connector = connectorOf(granteeSetting);
    if (!connector.getId().equals(delegation.getConnectorId())) {
      // Connected, but on another preset: this share lives on a server the caller's
      // session cannot reach.
      throw new IllegalAccessException(GRANTEE_NOT_CONNECTED_MESSAGE);
    }
    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    SharedMailbox shared;
    MailboxRights rights;
    try (MailboxAclSession session = session(connector, granteeUsername, mailboxIdentifier(granteeSetting))) {
      MailboxAclCapabilities capabilities = engine.probe(session);
      requireSupported(capabilities);
      if (capabilities.subscriptionRequired()) {
        // A refused subscription is a failed accept with the server's code, not a
        // revocation: the row is left as it was.
        engine.subscribe(session, delegation.getOwnerMailbox());
      }
      shared = engine.findSharedMailbox(session, delegation.getOwnerMailbox());
      if (shared == null) {
        markRevoked(delegation, DelegationStatus.REVOKED);
        throw new DelegationRevokedException(DelegationRevokedException.REVOKED);
      }
      try {
        rights = engine.myRights(session, shared.inboxName());
      } catch (MailboxAclException e) {
        markRevoked(delegation, DelegationStatus.REVOKED);
        throw new DelegationRevokedException(DelegationRevokedException.REVOKED);
      }
    }
    if (!rights.canRead()) {
      markRevoked(delegation, DelegationStatus.REVOKED);
      throw new DelegationRevokedException(DelegationRevokedException.REVOKED);
    }
    // Only accept's own columns (EXO-90548 review, finding 1): the row read above is as
    // old as the server calls, and an owner's Extend of this pending share may have
    // written its folder roles meanwhile. The server's own words are kept where the
    // owner's side recorded them -- MYRIGHTS answers letters on every engine.
    DelegationPreset preset = delegation.getOrigin() == DelegationOrigin.SERVER || delegation.getPreset() == null ? engine.presetOf(rights)
                                                                                                                 : null;
    EmailDelegation accepted = emailDelegationStorage.accept(granteeUsername,
                                                             id,
                                                             shared.remoteRoot(),
                                                             rights.letters(),
                                                             preset,
                                                             new Date());
    if (accepted == null) {
      // Moved on while the server was asked: accepted already by another request, or
      // revoked, gone or reopened by the owner.
      EmailDelegation current = asGrantee(granteeUsername, id);
      if (current.getStatus() == DelegationStatus.REVOKED || current.getStatus() == DelegationStatus.GONE) {
        throw new DelegationRevokedException(DelegationRevokedException.REVOKED);
      }
      if (current.getStatus() != DelegationStatus.ACCEPTED) {
        throw new IllegalArgumentException(NOT_ACCEPTABLE_MESSAGE);
      }
      accepted = current;
    }
    delegation = accepted;
    registerDelegatedInbox(granteeUsername, delegation, shared);
    // The mailbox's other folders, at once: the delegate opens it now. Best-effort -- the
    // share stands on its INBOX, and the next periodic pass discovers them otherwise. The
    // rows it could drop here are only rows the delegate's own walk registered before
    // shared trees were kept out of it and that the delegate cannot read: their mirror
    // stays, unreachable behind the guard (no r), until a later discovery drops the row
    // or the share ends -- this service has no mail to delete.
    try (MailboxAclSession session = session(connector, granteeUsername, mailboxIdentifier(granteeSetting))) {
      discoverDelegatedFolders(granteeUsername, delegation, engine, session, true);
    } catch (RuntimeException e) {
      LOG.info("The folders of shared mailbox {} could not be discovered at accept; the next pass will", delegation.getOwnerMailbox());
      LOG.debug("Discovery at accept failed", e);
    }
    LOG.info("Mailbox delegation accepted: actor={} ownerMailbox={} remoteRoot={} rights={}",
             granteeUsername,
             delegation.getOwnerMailbox(),
             shared.remoteRoot(),
             rights.letters());
    publish(EmailDelegationEvent.Type.ACCEPTED, granteeUsername, delegation);
    return delegation;
  }

  /**
   * Records the caller's "no" to a pending invitation. <b>The ACL on the server is not
   * touched</b>: the grant was the owner's consented action on the owner's session, and
   * removing it would mean eXo acting as the owner on the grantee's request thread --
   * the unattended identity switch the design refuses. The owner is told (through the
   * event) and has a one-click remove; the caller can accept later, and the MYRIGHTS
   * check of {@link #accept} decides then.
   *
   * @param granteeUsername the caller
   * @param id the delegation id
   * @return the row as it now stands
   * @throws ObjectNotFoundException when no such row belongs to the caller as grantee
   * @throws IllegalArgumentException with a message code when the row is not pending
   */
  public EmailDelegation decline(String granteeUsername, long id) throws ObjectNotFoundException {
    EmailDelegation delegation = asGrantee(granteeUsername, id);
    if (delegation.getStatus() != DelegationStatus.PENDING) {
      throw new IllegalArgumentException(NOT_PENDING_MESSAGE);
    }
    delegation.setStatus(DelegationStatus.DECLINED);
    delegation.setRespondedDate(new Date());
    delegation = emailDelegationStorage.update(delegation);
    LOG.info("Mailbox delegation declined: actor={} ownerMailbox={} (the server ACL is left in place)",
             granteeUsername,
             delegation.getOwnerMailbox());
    publish(EmailDelegationEvent.Type.DECLINED, granteeUsername, delegation);
    return delegation;
  }

  /**
   * Unsubscribes the caller from an accepted share. As with {@link #decline}, the ACL
   * stays: an eXo-granted share goes back to {@code DECLINED} (the owner still sees the
   * grantee and can remove them), a server-discovered one back to {@code AVAILABLE}.
   * The caller's registered folders of the mailbox are dropped. Where the server has a
   * subscription of its own (BlueMind), the engine withdraws it as the caller, best
   * effort: a refusal is logged and the eXo-side leave stands. On an IMAP engine the
   * hook is a no-op and no connection is opened.
   *
   * @param granteeUsername the caller
   * @param id the delegation id
   * @return the row as it now stands
   * @throws ObjectNotFoundException when no such row belongs to the caller as grantee
   * @throws IllegalArgumentException with a message code when the row is not accepted
   */
  public EmailDelegation leave(String granteeUsername, long id) throws ObjectNotFoundException {
    EmailDelegation delegation = asGrantee(granteeUsername, id);
    if (delegation.getStatus() != DelegationStatus.ACCEPTED) {
      throw new IllegalArgumentException(NOT_ACCEPTED_MESSAGE);
    }
    delegation = endShare(granteeUsername, delegation);
    unsubscribeBestEffort(granteeUsername, delegation);
    LOG.info("Mailbox delegation left: actor={} ownerMailbox={} (the server ACL is left in place)",
             granteeUsername,
             delegation.getOwnerMailbox());
    publish(EmailDelegationEvent.Type.LEFT, granteeUsername, delegation);
    return delegation;
  }

  /**
   * Ends every share the caller is using, as a leave would, when the caller's own mailbox
   * is disconnected from eXo or bound to another account (#432-3, decision 4): the
   * shared mailboxes were read with that account's credentials, and their folders and
   * mirrored mail are wiped with the account's own. Each goes back to what a leave leaves
   * -- DECLINED, or AVAILABLE for one discovered on the server -- so it can be taken up
   * again once a mailbox is connected, and its owner is told as for a leave. The ACLs on
   * the server are left in place, as for a leave. No server-side unsubscription: the
   * account that would make it is the one just disconnected.
   *
   * @param granteeUsername the user whose mailbox was disconnected or rebound
   */
  public void endReceivedShares(String granteeUsername) {
    if (StringUtils.isBlank(granteeUsername)) {
      return;
    }
    for (EmailDelegation delegation : emailDelegationStorage.getReceived(granteeUsername)) {
      if (delegation.getStatus() != DelegationStatus.ACCEPTED) {
        continue;
      }
      EmailDelegation ended = endShare(granteeUsername, delegation);
      LOG.info("Mailbox delegation ended by a mailbox disconnect: grantee={} ownerMailbox={}",
               granteeUsername,
               ended.getOwnerMailbox());
      publish(EmailDelegationEvent.Type.LEFT, granteeUsername, ended);
    }
  }

  /**
   * A leave's own write: the row back to DECLINED (AVAILABLE for a share discovered on
   * the server), answered now, and the caller's folders of the mailbox dropped.
   *
   * @param granteeUsername the grantee
   * @param delegation an accepted row
   * @return the row as it now stands
   */
  private EmailDelegation endShare(String granteeUsername, EmailDelegation delegation) {
    delegation.setStatus(delegation.getOrigin() == DelegationOrigin.SERVER ? DelegationStatus.AVAILABLE : DelegationStatus.DECLINED);
    delegation.setRespondedDate(new Date());
    // Back off by default (#441-1): a share taken up again must be chosen again to count
    // in the badge, as a new one is (plan 7.7).
    delegation.setBadgeIncluded(false);
    EmailDelegation updated = emailDelegationStorage.update(delegation);
    dropDelegatedFolders(granteeUsername, updated.getId());
    return updated;
  }

  /**
   * The caller's per-delegation toggles: whether the shared INBOX counts in their badge,
   * whether new mail there notifies them. A null leaves a toggle as it is.
   *
   * @param granteeUsername the caller
   * @param id the delegation id
   * @param badgeIncluded the badge toggle, or null
   * @param notifyNewMail the notification toggle, or null
   * @return the row as it now stands
   * @throws ObjectNotFoundException when no such row belongs to the caller as grantee
   */
  public EmailDelegation updatePreferences(String granteeUsername,
                                           long id,
                                           Boolean badgeIncluded,
                                           Boolean notifyNewMail) throws ObjectNotFoundException {
    return updatePreferences(granteeUsername, id, badgeIncluded, notifyNewMail, null);
  }

  /**
   * The caller's per-delegation toggles, the search one included (EXO-90554): whether
   * the unified search returns this shared mailbox's mail, labelled with its owner. A
   * null leaves a toggle as it is. The search toggle is written on its own, so it never
   * carries the other two back to an earlier read.
   *
   * @param granteeUsername the caller
   * @param id the delegation id
   * @param badgeIncluded the badge toggle, or null
   * @param notifyNewMail the notification toggle, or null
   * @param searchIncluded the search toggle, or null
   * @return the row as it now stands
   * @throws ObjectNotFoundException when no such row belongs to the caller as grantee
   */
  public EmailDelegation updatePreferences(String granteeUsername,
                                           long id,
                                           Boolean badgeIncluded,
                                           Boolean notifyNewMail,
                                           Boolean searchIncluded) throws ObjectNotFoundException {
    EmailDelegation updated = updateBadgeAndNotify(granteeUsername, id, badgeIncluded, notifyNewMail);
    if (updated != null && searchIncluded != null && searchIncluded != updated.isSearchIncluded()) {
      updated = emailDelegationStorage.updateSearchIncluded(granteeUsername, id, searchIncluded);
    }
    return updated;
  }

  /**
   * The badge and notification toggles of {@link #updatePreferences}.
   *
   * @param granteeUsername the caller
   * @param id the delegation id
   * @param badgeIncluded the badge toggle, or null
   * @param notifyNewMail the notification toggle, or null
   * @return the row as it now stands
   * @throws ObjectNotFoundException when no such row belongs to the caller as grantee
   */
  private EmailDelegation updateBadgeAndNotify(String granteeUsername,
                                               long id,
                                               Boolean badgeIncluded,
                                               Boolean notifyNewMail) throws ObjectNotFoundException {
    EmailDelegation delegation = asGrantee(granteeUsername, id);
    if (badgeIncluded == null && notifyNewMail == null) {
      return delegation;
    }
    boolean badgeChanged = badgeIncluded != null && badgeIncluded != delegation.isBadgeIncluded();
    // The two toggles alone (#432-2): a whole-row write from this read would put back a
    // status, a revoke date or rights the owner changed since -- a revoke made while
    // the grantee flipped their badge would come undone.
    EmailDelegation updated = emailDelegationStorage.updatePreferences(granteeUsername,
                                                                       id,
                                                                       badgeIncluded != null ? badgeIncluded : delegation.isBadgeIncluded(),
                                                                       notifyNewMail != null ? notifyNewMail : delegation.isNotifyNewMail());
    if (badgeChanged) {
      // The badge counts this shared inbox, or stops counting it (EXO-90546): whoever
      // shows the badge is told, after the commit, by the listener of this event.
      publish(EmailDelegationEvent.Type.BADGE_PREFERENCE_CHANGED, granteeUsername, updated);
    }
    return updated;
  }

  /**
   * The cap on accepted shares per grantee -- see {@link #MAX_PER_USER_PROPERTY}. A
   * misconfigured value falls back to the default.
   *
   * @return the cap, at least one
   */
  public int getMaxPerUser() {
    String value = System.getProperty(MAX_PER_USER_PROPERTY);
    if (StringUtils.isBlank(value)) {
      return DEFAULT_MAX_PER_USER;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : DEFAULT_MAX_PER_USER;
    } catch (NumberFormatException e) {
      return DEFAULT_MAX_PER_USER;
    }
  }

  /**
   * The shared mailboxes the caller can switch to from the mail drawer's header
   * (delegation plan 7.3): every ACCEPTED share whose INBOX is registered, with the
   * folder key it is listed under, the rights last granted, and the unread count of that
   * INBOX in the caller's mirror.
   * <p>
   * Read from eXo's rows alone, with no connection to the mail server: the switcher is
   * drawn every time the drawer opens, and the rights it shows are the ones every write
   * guard checks against ({@link #checkRight}), refreshed by the sync, so the chrome and
   * the guard read the same letters. A share without a registered INBOX (accepted, then
   * purged by the reconciler) is left out rather than offered with nothing to open.
   * <p>
   * Scoped to the caller by construction: only rows whose grantee is the caller, and
   * only folder rows the caller owns.
   *
   * @param granteeUsername the caller
   * @return the entries, most recently changed share first, never null
   */
  public List<SharedMailboxEntry> getSharedMailboxes(String granteeUsername) {
    if (StringUtils.isBlank(granteeUsername)) {
      return List.of();
    }
    List<EmailDelegation> accepted = emailDelegationStorage.getReceived(granteeUsername)
                                                           .stream()
                                                           .filter(delegation -> delegation.getStatus() == DelegationStatus.ACCEPTED)
                                                           .toList();
    if (accepted.isEmpty()) {
      return List.of();
    }
    FolderMessageCounts counts = emailBoxStorage.getFolderCounts(granteeUsername);
    Map<String, Integer> unreadCounts = counts == null || counts.getUnreadCounts() == null ? Map.of()
                                                                                           : counts.getUnreadCounts();
    // Read once for the whole list (EXO-90551 review): the switcher is drawn every time
    // the drawer opens.
    boolean sentCopyEnabled = isSentCopyEnabled();
    List<SharedMailboxEntry> entries = new ArrayList<>();
    for (EmailDelegation delegation : accepted) {
      List<EmailFolder> folders = emailFolderStorage.getDelegatedFolders(granteeUsername, delegation.getId());
      EmailFolder inbox = folders.stream()
                                 .filter(folder -> MailFolderView.TYPE_DELEGATED_INBOX.equals(folder.getType()))
                                 .findFirst()
                                 .orElse(null);
      if (inbox == null) {
        continue;
      }
      entries.add(new SharedMailboxEntry(delegation.getId(),
                                         delegation.getOwnerId(),
                                         ownerFullName(delegation),
                                         delegation.getOwnerMailbox(),
                                         delegation.getPreset(),
                                         delegation.getRights(),
                                         delegation.getAffordances(),
                                         inbox.getKey(),
                                         unreadCounts.getOrDefault(inbox.getKey(), 0),
                                         otherFolders(folders, delegation),
                                         // What an Extend can change (EXO-90548 review): a share
                                         // eXo wrote before folders were shared. One made in the
                                         // mail server's interface records no roles either, and
                                         // may well cover its Trash.
                                         delegation.isInboxOnly() && delegation.getOrigin() == DelegationOrigin.EXO,
                                         sentCopyEnabled && sentCopyOf(folders, delegation)));
    }
    return entries;
  }

  /**
   * A shared mailbox's folders besides INBOX, for the switcher and the folder tree: the
   * ones the last discovery still listed, roles first in their usual order, then by
   * name; each with the controls the delegate's letters on it unlock.
   *
   * @param folders the share's registered folders
   * @param delegation the share
   * @return the folders, never null
   */
  private List<SharedMailboxFolder> otherFolders(List<EmailFolder> folders, EmailDelegation delegation) {
    return folders.stream()
                  .filter(folder -> MailFolderView.TYPE_DELEGATED.equals(folder.getType()))
                  .filter(folder -> !folder.isMissing())
                  .sorted(Comparator.comparing((EmailFolder folder) -> folder.getRole() == null ? Integer.MAX_VALUE : folder.getRole().ordinal())
                                    .thenComparing(EmailFolder::getDisplayName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                  .map(folder -> {
                    MailboxRights rights = folderRights(folder, delegation);
                    return new SharedMailboxFolder(folder.getKey(),
                                                   folder.getRole(),
                                                   folder.getDisplayName(),
                                                   rights.letters(),
                                                   rights.affordances(),
                                                   rights.canRead());
                  })
                  .toList();
  }

  /**
   * The delegate's letters on one folder of a share: the folder's own, as discovery read
   * them, for a folder beside INBOX (EXO-90548); the share's for its INBOX -- re-read
   * every periodic pass -- and for a row discovery has not read yet, which knows no
   * better.
   *
   * @param folder the delegated folder
   * @param delegation its share
   * @return the letters, never null
   */
  private static MailboxRights folderRights(EmailFolder folder, EmailDelegation delegation) {
    // "Discovery has read this folder" is its stamp, not its letters: no letter at all is
    // stored as an empty string, which Oracle reads back as null.
    if (folder != null && MailFolderView.TYPE_DELEGATED.equals(folder.getType()) && folder.getRightsCheckDate() != null) {
      return MailboxRights.of(StringUtils.defaultString(folder.getRights()));
    }
    return delegation.getMailboxRights();
  }

  /**
   * The same letters as {@link #folderRights(EmailFolder, EmailDelegation)}, from a
   * share's letters as a switcher entry carries them (EXO-90555), for a caller that holds
   * the entry rather than the row.
   *
   * @param folder the delegated folder
   * @param shareLetters the share's letters
   * @return the letters, never null
   */
  private static MailboxRights folderRights(EmailFolder folder, String shareLetters) {
    if (folder != null && MailFolderView.TYPE_DELEGATED.equals(folder.getType()) && folder.getRightsCheckDate() != null) {
      return MailboxRights.of(StringUtils.defaultString(folder.getRights()));
    }
    return MailboxRights.of(StringUtils.defaultString(shareLetters));
  }

  /**
   * The name the switcher and the identity cue show for a share's owner: the eXo
   * profile's full name when the owner is a known user, the mailbox address otherwise. A
   * profile that cannot be read falls back to the address rather than failing the list.
   *
   * @param delegation the share
   * @return the name, never null
   */
  private String ownerFullName(EmailDelegation delegation) {
    String fallback = StringUtils.defaultString(delegation.getOwnerMailbox());
    if (StringUtils.isBlank(delegation.getOwnerId()) || identityManager == null) {
      return fallback;
    }
    try {
      Identity identity = identityManager.getOrCreateUserIdentity(delegation.getOwnerId());
      String fullName = identity == null || identity.getProfile() == null ? null : identity.getProfile().getFullName();
      return StringUtils.isBlank(fullName) ? fallback : fullName;
    } catch (RuntimeException e) {
      LOG.debug("The display name of mailbox owner {} could not be resolved", delegation.getOwnerId(), e);
      return fallback;
    }
  }

  // ---------------------------------------------------------------------------------
  // The delegated branch of the sync, and the rights it is gated on
  // ---------------------------------------------------------------------------------

  /**
   * Where every mailbox somebody shared with the caller sits in the caller's own folder
   * listing on one server -- the {@code REMOTE_ROOT} of each of their rows on that
   * connector, whatever its status (EXO-90548). The caller's folder walk and their Trash
   * and Archive finders leave these trees out, beside the namespaces the server
   * advertises: on a server whose namespace is not advertised or cannot be read, and
   * whose shared root has no INBOX child -- Dovecot's {@code shared/<owner>}, which holds
   * the owner's INBOX mail itself, when NAMESPACE fails -- no shape reveals it and only
   * the row does. Any status, because a declined or left share is still listed by the
   * server; one connector only, because another server's paths mean nothing in this
   * listing and could match one of the caller's own folders there. Read from eXo's rows,
   * no mail server.
   *
   * @param username the caller
   * @param connectorId the connector preset of the listing
   * @return the roots, possibly empty, never null
   */
  public Set<String> getSharedMailboxRoots(String username, Long connectorId) {
    if (StringUtils.isBlank(username) || connectorId == null) {
      return Set.of();
    }
    Set<String> roots = new HashSet<>();
    for (EmailDelegation delegation : emailDelegationStorage.getReceived(username)) {
      if (connectorId.equals(delegation.getConnectorId()) && StringUtils.isNotBlank(delegation.getRemoteRoot())) {
        roots.add(delegation.getRemoteRoot());
      }
    }
    return roots;
  }

  /**
   * The folder keys of every mailbox somebody shared with the caller -- what the
   * caller's own reads leave out (EXO-90557): their search, their conversations, their
   * list's thread counts. Read from eXo's rows, no mail server.
   *
   * @param username the caller
   * @return the {@code CUSTOM:<id>} keys, possibly empty, never null
   */
  public List<String> getDelegatedFolderKeys(String username) {
    if (StringUtils.isBlank(username)) {
      return List.of();
    }
    List<String> keys = new ArrayList<>();
    for (EmailDelegation delegation : emailDelegationStorage.getReceived(username)) {
      // Only a share in use has folders: leaving, declining and revoking drop them.
      if (delegation.getStatus() == DelegationStatus.ACCEPTED) {
        keys.addAll(getMailboxFolderKeys(username, delegation.getId()));
      }
    }
    return keys;
  }

  /**
   * The folders of the mailboxes shared with the caller as the unified search needs them
   * (EXO-90554, PO decision Q-6), in one pass over the caller's shares: every folder of
   * an ACCEPTED share -- what the read of the caller's own mail leaves out, as
   * {@link #getDelegatedFolderKeys} does -- and, among them, the ones the search reads,
   * each with whose mailbox it is. A folder is searched when its share's search toggle
   * is on, the last discovery still listed it, the caller may read it, and it is not the
   * owner's Trash or Spam -- the folders the caller's own search leaves out of their own
   * mailbox ({@link MailFolder#HIDDEN_FOLDERS}), for the same reason: a hit says nothing
   * of the bin it came out of. Read from eXo's rows, no mail server; the owner's name is
   * resolved once per share, never per folder or per hit.
   *
   * @param username the caller
   * @return the folders, never null
   */
  public SharedMailboxSearchFolders getSharedMailboxSearchFolders(String username) {
    if (StringUtils.isBlank(username)) {
      return SharedMailboxSearchFolders.NONE;
    }
    List<String> sharedKeys = new ArrayList<>();
    Map<String, SharedMailboxSearchScope> searchable = new HashMap<>();
    for (EmailDelegation delegation : emailDelegationStorage.getReceived(username)) {
      // Only a share in use has folders: leaving, declining and revoking drop them.
      if (delegation.getStatus() != DelegationStatus.ACCEPTED) {
        continue;
      }
      List<EmailFolder> folders = emailFolderStorage.getDelegatedFolders(username, delegation.getId());
      folders.forEach(folder -> sharedKeys.add(folder.getKey()));
      if (!delegation.isSearchIncluded()) {
        continue;
      }
      List<EmailFolder> searched = folders.stream()
                                          .filter(folder -> !folder.isMissing())
                                          .filter(folder -> folder.getRole() != FolderRole.TRASH && folder.getRole() != FolderRole.JUNK)
                                          .filter(folder -> folderRights(folder, delegation).canRead())
                                          .toList();
      if (!searched.isEmpty()) {
        SharedMailboxSearchScope scope = new SharedMailboxSearchScope(delegation.getId(), ownerFullName(delegation));
        searched.forEach(folder -> searchable.put(folder.getKey(), scope));
      }
    }
    return new SharedMailboxSearchFolders(sharedKeys, searchable);
  }

  /**
   * The folder keys of one mailbox shared with the caller.
   *
   * @param username the caller
   * @param delegationId the share
   * @return the keys, possibly empty, never null
   */
  public List<String> getMailboxFolderKeys(String username, long delegationId) {
    return emailFolderStorage.getDelegatedFolders(username, delegationId).stream().map(EmailFolder::getKey).toList();
  }

  /**
   * Re-reads what the server lets the caller do in a mailbox shared with them, on the
   * caller's own session -- the connection their sync already holds -- at the start of
   * each pass over it (EXO-90557). The stored rights are what every control and every
   * write guard reads, and until now they were set at accept and never re-read: a share
   * the owner narrowed, widened or removed on the server went on answering with what it
   * was.
   * <p>
   * MYRIGHTS on the shared INBOX decides: no read right, or a refusal, and the share is
   * revoked (its folders dropped, the badge told); otherwise changed letters are
   * recorded, with the preset they read as, and a move of the right to keep read state
   * announced ({@link EmailDelegationEvent.Type#RIGHTS_CHANGED}). An unchanged answer
   * writes nothing.
   *
   * @param granteeUsername the caller
   * @param delegation an accepted share of the caller's
   * @param store the caller's own connected store
   * @return the share as it now stands, null when it was revoked
   */
  public EmailDelegation refreshGranteeRights(String granteeUsername, EmailDelegation delegation, Store store) {
    EmailFolder inbox = emailFolderStorage.getDelegatedFolders(granteeUsername, delegation.getId())
                                          .stream()
                                          .filter(folder -> MailFolderView.TYPE_DELEGATED_INBOX.equals(folder.getType()))
                                          .findFirst()
                                          .orElse(null);
    if (inbox == null) {
      return delegation;
    }
    EmailConnector connector = emailConnectorService.getEmailConnector(delegation.getConnectorId());
    if (connector == null) {
      return delegation;
    }
    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    // The sync's own store, not closed here: the session only borrows it.
    MailboxAclSession session = new MailboxAclSession(connector, granteeUsername, delegation.getGranteeMailbox(), () -> store, null);
    MailboxRights rights;
    try {
      rights = engine.myRights(session, inbox.getRemoteName());
    } catch (MailboxAclException e) {
      if (!MailboxAclException.SERVER_REFUSED.equals(e.getCode())) {
        LOG.debug("The rights of {} on shared mailbox {} could not be read; kept as they were",
                  granteeUsername,
                  delegation.getOwnerMailbox(),
                  e);
        return delegation;
      }
      rights = MailboxRights.NONE;
    }
    if (!rights.canRead()) {
      // Re-read, like the rights update below: a leave or a revoke made during the pass
      // stands as it is, and is not announced twice.
      EmailDelegation current = emailDelegationStorage.getAsGrantee(granteeUsername, delegation.getId());
      if (current != null && current.getStatus() == DelegationStatus.ACCEPTED) {
        LOG.info("Mailbox delegation found withdrawn: grantee={} ownerMailbox={}", granteeUsername, delegation.getOwnerMailbox());
        markRevoked(current, DelegationStatus.REVOKED);
      }
      return null;
    }
    if (rights.letters().equals(delegation.getRights())) {
      return delegation;
    }
    // Re-read before writing: the row given was read at the start of the pass, and the
    // storage writes every column back -- a badge or notification choice, an activity
    // stamp or an owner's revoke made since would otherwise be undone.
    EmailDelegation current = emailDelegationStorage.getAsGrantee(granteeUsername, delegation.getId());
    if (current == null || current.getStatus() != DelegationStatus.ACCEPTED) {
      return null;
    }
    boolean keptSeen = current.getMailboxRights().canKeepSeen();
    current.setRights(rights.letters());
    current.setPreset(engine.presetOf(rights));
    current.setLastRightsCheckDate(new Date());
    EmailDelegation updated = emailDelegationStorage.update(current);
    if (keptSeen != rights.canKeepSeen()) {
      publish(EmailDelegationEvent.Type.RIGHTS_CHANGED, null, updated);
    }
    return updated;
  }

  /**
   * The periodic pass's discovery of a shared mailbox's folders (EXO-90548), on the
   * delegate's own store, borrowed and not closed: at most every
   * {@link #DISCOVERY_INTERVAL_MS} per share. See
   * {@link #discoverDelegatedFolders(String, EmailDelegation, MailboxAclEngine, MailboxAclSession, boolean)}.
   *
   * @param granteeUsername the delegate
   * @param delegation an accepted share
   * @param store the delegate's connected store
   * @return the rows whose mirrored mail the caller must delete, never null
   */
  public List<EmailFolder> discoverDelegatedFoldersIfDue(String granteeUsername, EmailDelegation delegation, Store store) {
    EmailConnector connector = delegation == null || delegation.getConnectorId() == null ? null
                                                                                         : emailConnectorService.getEmailConnector(delegation.getConnectorId());
    if (connector == null) {
      return List.of();
    }
    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    MailboxAclSession session = new MailboxAclSession(connector, granteeUsername, delegation.getGranteeMailbox(), () -> store, null);
    try {
      return discoverDelegatedFolders(granteeUsername, delegation, engine, session, false);
    } catch (MailboxAclException e) {
      LOG.debug("The folders of shared mailbox {} could not be discovered ({}); kept as they were",
                delegation.getOwnerMailbox(),
                e.getCode());
      if (MailboxAclException.SERVER_REFUSED.equals(e.getCode())) {
        // A refusal is an answer: asked again in a quarter-hour, not every pass.
        stampDiscovery(granteeUsername, delegation);
      }
      return List.of();
    }
  }

  /**
   * Stamps a share's INBOX row as discovered now, which is what the discovery's
   * throttle reads.
   *
   * @param granteeUsername the delegate
   * @param delegation the share
   */
  private void stampDiscovery(String granteeUsername, EmailDelegation delegation) {
    emailFolderStorage.getDelegatedFolders(granteeUsername, delegation.getId())
                      .stream()
                      .filter(folder -> MailFolderView.TYPE_DELEGATED_INBOX.equals(folder.getType()))
                      .findFirst()
                      .ifPresent(inbox -> emailFolderStorage.updateDelegatedRights(granteeUsername,
                                                                                   inbox.getId(),
                                                                                   delegation.getId(),
                                                                                   null,
                                                                                   delegation.getRights(),
                                                                                   new Date()));
  }

  /**
   * Discovers the folders of a shared mailbox on the delegate's session and registers
   * them as the delegate's view of it (EXO-90548, plan S1.4): every folder the server
   * lists under the share's root, with its role in the owner's mailbox and the
   * delegate's own MYRIGHTS letters on it.
   * <ul>
   * <li><b>Role</b>: the owner's role-to-folder map the grant recorded on the owner's
   * session first -- a delegate may be shown no special-use attribute at all (Dovecot)
   * -- then the attribute the delegate's listing shows, then the usual name of a direct
   * child of the root. One folder per role.</li>
   * <li><b>Letters</b>: a folder listed without {@code r} is registered greyed and never
   * synced; one the server refuses MYRIGHTS on reads as no right.</li>
   * <li><b>Cap</b>: {@link #MAX_FOLDERS_PROPERTY}, roles first, then by name.</li>
   * <li><b>Reconciliation</b>, as for the user's own folders: a folder listed again is
   * un-missed; one not listed is marked missing, then dropped at the next discovery that
   * still does not list it -- one grace walk, for an owner's rename, which a delegate's
   * listing may not show until the owner shares the renamed folder again (Dovecot).</li>
   * <li><b>Adoption</b>: a row the delegate's own walk registered at the same name
   * before EXO-90548 kept shared trees out of it is taken into the share, not
   * duplicated (plan R-b).</li>
   * </ul>
   * The delegated INBOX row is stamped with the delegate's letters and the discovery's
   * time, which is what the quarter-hour throttle reads.
   *
   * @param granteeUsername the delegate
   * @param delegation an accepted share
   * @param engine the engine
   * @param session the delegate's session
   * @param force whether to run inside the throttle
   * @return the rows dropped or no longer readable, whose mirrored mail the caller must
   *         delete; never null
   * @throws MailboxAclException when the server cannot be reached; nothing is changed then
   */
  List<EmailFolder> discoverDelegatedFolders(String granteeUsername,
                                             EmailDelegation delegation,
                                             MailboxAclEngine engine,
                                             MailboxAclSession session,
                                             boolean force) {
    if (delegation == null || delegation.getId() == null || delegation.getStatus() != DelegationStatus.ACCEPTED
        || StringUtils.isBlank(delegation.getRemoteRoot())) {
      return List.of();
    }
    List<EmailFolder> registered = emailFolderStorage.getDelegatedFolders(granteeUsername, delegation.getId());
    EmailFolder inbox = registered.stream().filter(folder -> MailFolderView.TYPE_DELEGATED_INBOX.equals(folder.getType())).findFirst().orElse(null);
    if (inbox == null) {
      return List.of();
    }
    Date now = new Date();
    if (!force && inbox.getRightsCheckDate() != null && now.getTime() - inbox.getRightsCheckDate().getTime() < DISCOVERY_INTERVAL_MS) {
      return List.of();
    }
    String delimiter = StringUtils.defaultIfEmpty(inbox.getDelimiter(), "/");
    List<DiscoveredFolder> listed = new ArrayList<>(engine.listFoldersUnder(session, delegation.getRemoteRoot(), delimiter)
                                                          .stream()
                                                          .filter(DiscoveredFolder::selectable)
                                                          .filter(folder -> !folder.fullName().equals(inbox.getRemoteName()))
                                                          .toList());
    Map<String, FolderRole> roles = rolesOf(listed, delegation, delimiter);
    listed.sort(Comparator.comparing((DiscoveredFolder folder) -> roles.containsKey(folder.fullName()) ? roles.get(folder.fullName()).ordinal() : Integer.MAX_VALUE)
                          .thenComparing(DiscoveredFolder::fullName));
    int cap = getMaxFolders();
    if (listed.size() > cap) {
      LOG.info("Shared mailbox {} lists {} folders; the first {} are shown", delegation.getOwnerMailbox(), listed.size(), cap);
      listed = new ArrayList<>(listed.subList(0, cap));
    }
    Map<String, MailboxRights> rights = new HashMap<>();
    for (DiscoveredFolder folder : listed) {
      rights.put(folder.fullName(), rightsOf(engine, session, folder.fullName()));
    }

    Map<String, EmailFolder> byName = new HashMap<>();
    registered.forEach(folder -> byName.put(folder.getRemoteName(), folder));
    List<EmailFolder> purged = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (DiscoveredFolder folder : listed) {
      seen.add(folder.fullName());
      MailboxRights letters = rights.get(folder.fullName());
      FolderRole role = roles.get(folder.fullName());
      EmailFolder row = byName.get(folder.fullName());
      if (row == null) {
        row = adoptOrCreate(granteeUsername, delegation, folder, now);
      } else {
        emailFolderStorage.markSeen(granteeUsername, row.getId(), folder.displayName(), folder.delimiter(), now);
      }
      if (row == null) {
        continue;
      }
      emailFolderStorage.updateDelegatedRights(granteeUsername, row.getId(), delegation.getId(), role, letters.letters(), now);
      if (letters.canRead() && !row.isSyncEnabled()) {
        emailFolderStorage.updateSyncEnabled(granteeUsername, row.getId(), true, now);
      } else if (!letters.canRead() && row.isSyncEnabled()) {
        emailFolderStorage.updateSyncEnabled(granteeUsername, row.getId(), false, now);
        purged.add(row);
      }
    }
    for (EmailFolder row : registered) {
      if (row == inbox || seen.contains(row.getRemoteName())) {
        continue;
      }
      if (row.isMissing()) {
        emailFolderStorage.deleteFolder(granteeUsername, row.getId());
        purged.add(row);
      } else {
        emailFolderStorage.markMissing(granteeUsername, row.getId());
      }
    }
    emailFolderStorage.updateDelegatedRights(granteeUsername, inbox.getId(), delegation.getId(), null, delegation.getRights(), now);
    return purged;
  }

  /**
   * Each listed folder's role in the owner's mailbox: the owner's own map first (read
   * on the owner's session at the grant), then the special-use attribute the delegate's
   * listing shows, then the usual English name of a direct child of the root. One
   * folder per role; Drafts is recognised but was never granted by eXo.
   *
   * @param listed the folders under the root
   * @param delegation the share
   * @param delimiter the hierarchy delimiter
   * @return the role of each folder that has one, by full name
   */
  private Map<String, FolderRole> rolesOf(List<DiscoveredFolder> listed, EmailDelegation delegation, String delimiter) {
    Map<String, FolderRole> roles = new HashMap<>();
    Set<FolderRole> taken = EnumSet.noneOf(FolderRole.class);
    String rootPrefix = StringUtils.removeEnd(delegation.getRemoteRoot(), delimiter) + delimiter;
    Map<FolderRole, String> owner = delegation.getOwnerRoleFolders() == null ? Map.of() : delegation.getOwnerRoleFolders();
    owner.forEach((role, ownerName) -> {
      String shared = rootPrefix + withoutInboxPrefix(ownerName, delimiter);
      listed.stream().filter(folder -> folder.fullName().equals(shared)).findFirst().ifPresent(folder -> {
        roles.put(folder.fullName(), role);
        taken.add(role);
      });
    });
    for (DiscoveredFolder folder : listed) {
      if (roles.containsKey(folder.fullName())) {
        continue;
      }
      FolderRole role = folder.attributes() == null ? null
                                                    : folder.attributes().stream().map(FolderRole::ofAttribute).filter(r -> r != null).findFirst().orElse(null);
      String below = folder.fullName().startsWith(rootPrefix) ? folder.fullName().substring(rootPrefix.length()) : folder.fullName();
      if (role == null) {
        // The path below the root, whole: only a direct child can be named "Trash".
        role = FolderRole.ofUsualName(below);
      }
      if (role != null && taken.add(role)) {
        roles.put(folder.fullName(), role);
      }
    }
    return roles;
  }

  /**
   * An owner's folder name as it appears under the shared root: a server that names the
   * owner's folders under INBOX ({@code INBOX.Sent}) lists them under the owner's root
   * without it.
   *
   * @param ownerName the name on the owner's session
   * @param delimiter the hierarchy delimiter
   * @return the name relative to the owner's root
   */
  private static String withoutInboxPrefix(String ownerName, String delimiter) {
    String prefix = MailFolder.INBOX + delimiter;
    return ownerName != null && ownerName.regionMatches(true, 0, prefix, 0, prefix.length()) ? ownerName.substring(prefix.length())
                                                                                             : StringUtils.defaultString(ownerName);
  }

  /**
   * The delegate's letters on one folder. A refusal of MYRIGHTS on a listed folder reads
   * as no right; a lost connection stops the discovery.
   *
   * @param engine the engine
   * @param session the delegate's session
   * @param mailbox the folder's full name
   * @return the letters
   * @throws MailboxAclException when the server cannot be reached
   */
  private MailboxRights rightsOf(MailboxAclEngine engine, MailboxAclSession session, String mailbox) {
    try {
      return engine.myRights(session, mailbox);
    } catch (MailboxAclException e) {
      if (MailboxAclException.UNREACHABLE.equals(e.getCode())) {
        throw e;
      }
      return MailboxRights.NONE;
    }
  }

  /**
   * The row of a folder discovery lists for the first time: the delegate's own row of
   * that name adopted into the share when their walk registered one before EXO-90548 kept
   * shared trees out, a new row otherwise (opt-in off; discovery then opts in a readable
   * folder).
   *
   * @param granteeUsername the delegate
   * @param delegation the share
   * @param folder the listed folder
   * @param now the sighting time
   * @return the row, or null when it could not be written
   */
  private EmailFolder adoptOrCreate(String granteeUsername, EmailDelegation delegation, DiscoveredFolder folder, Date now) {
    EmailFolder own = emailFolderStorage.getFolderByRemoteName(granteeUsername, folder.fullName());
    if (own != null) {
      if (own.getDelegationId() != null && !own.getDelegationId().equals(delegation.getId())) {
        // Another share's row at the same name: not this discovery's to take.
        return null;
      }
      emailFolderStorage.adoptAsDelegated(granteeUsername, own.getId(), delegation.getId(), MailFolderView.TYPE_DELEGATED);
      // The delegate's own walk, which now leaves shared trees out, may have marked it
      // missing: it is listed right now.
      emailFolderStorage.markSeen(granteeUsername, own.getId(), folder.displayName(), folder.delimiter(), now);
      return emailFolderStorage.getFolder(granteeUsername, own.getId());
    }
    EmailFolder created = new EmailFolder();
    created.setUserId(granteeUsername);
    created.setRemoteName(folder.fullName());
    created.setDisplayName(folder.displayName());
    created.setDelimiter(folder.delimiter());
    created.setType(MailFolderView.TYPE_DELEGATED);
    created.setDelegationId(delegation.getId());
    created.setDiscoveredDate(now);
    created.setLastSeenDate(now);
    try {
      return emailFolderStorage.createFolder(created);
    } catch (RuntimeException e) {
      LOG.debug("Folder '{}' of shared mailbox {} could not be registered", folder.fullName(), delegation.getOwnerMailbox(), e);
      return null;
    }
  }

  /**
   * The cap on registered folders per shared mailbox -- see {@link #MAX_FOLDERS_PROPERTY}.
   * A misconfigured value falls back to the default.
   *
   * @return the cap, at least one
   */
  public int getMaxFolders() {
    String value = System.getProperty(MAX_FOLDERS_PROPERTY);
    if (StringUtils.isBlank(value)) {
      return DEFAULT_MAX_FOLDERS;
    }
    try {
      int parsed = Integer.parseInt(value.trim());
      return parsed > 0 ? parsed : DEFAULT_MAX_FOLDERS;
    } catch (NumberFormatException e) {
      return DEFAULT_MAX_FOLDERS;
    }
  }

  /**
   * The shared mailboxes the caller is subscribed to AND currently in -- what the
   * delegated branch of their sync walks.
   * <p>
   * "Currently in" is {@code LAST_ACTIVITY_DATE} within the same activity threshold the
   * mailbox tiers already use, stamped by {@link #touchActivity} when the caller lists
   * one of the mailbox's folders. A delegation nobody opens therefore costs nothing at
   * all -- not a slower tier, nothing -- which is the third of the mitigations that
   * make one mirror per delegate affordable, beside the smaller window and INBOX-only.
   *
   * @param granteeUsername the caller
   * @param activeSince an activity stamp at or after this instant makes a share active
   * @return the active accepted delegations, never null
   */
  public List<EmailDelegation> getActiveDelegations(String granteeUsername, Date activeSince) {
    if (StringUtils.isBlank(granteeUsername) || activeSince == null) {
      return List.of();
    }
    return emailDelegationStorage.getActive(granteeUsername, activeSince);
  }

  /**
   * The folders of one shared mailbox that the caller asked to mirror: opted in and
   * still listed by the server.
   * <p>
   * The delegated INBOX only. Since EXO-90548 the shared mailbox's other folders are
   * discovered and opted in too -- so that opening one refreshes it on the spot -- and
   * they are kept out here, by type: a share the delegate uses costs one folder per
   * period, whatever the owner's mailbox holds (plan S1.7).
   *
   * @param granteeUsername the caller
   * @param delegationId the delegation
   * @return the folders to mirror, never null
   */
  public List<EmailFolder> getSyncableFolders(String granteeUsername, long delegationId) {
    return emailFolderStorage.getDelegatedFolders(granteeUsername, delegationId)
                             .stream()
                             .filter(EmailFolder::isSyncEnabled)
                             .filter(folder -> !folder.isMissing())
                             // The periodic pass keeps to the shared INBOX (plan S1.7): the
                             // owner's other folders are opted in so that opening one
                             // refreshes it, and cost nothing until someone does.
                             .filter(folder -> MailFolderView.TYPE_DELEGATED_INBOX.equals(folder.getType()))
                             .toList();
  }

  /**
   * Records that the caller is looking at the shared mailbox a folder key belongs to.
   * <p>
   * A no-op, without a query, for a key that is not a delegated folder's -- which is
   * every key of the caller's own mailbox, so the listing path may call this
   * unconditionally. Failure-isolated for the reason the mailbox's own stamp is: a
   * listing must never fail because a stamp did, and a lost stamp costs one sync
   * period of a shared mailbox, not the mail.
   *
   * @param granteeUsername the caller
   * @param folderKey the {@code EMAIL_BOX.FOLDER} discriminator being listed
   */
  public void touchActivity(String granteeUsername, String folderKey) {
    try {
      EmailDelegation delegation = delegationOf(granteeUsername, folderKey);
      if (delegation == null) {
        return;
      }
      Date now = new Date();
      emailDelegationStorage.touchActivity(granteeUsername,
                                           delegation.getId(),
                                           now,
                                           EmailConnectorUtils.getSyncActivityThrottleBefore(now));
    } catch (RuntimeException e) {
      LOG.debug("Could not stamp the activity of user {} on folder {}; the share stands on its previous stamp",
                granteeUsername,
                folderKey,
                e);
    }
  }

  /**
   * The delegation a folder key belongs to, or null when the key names a folder of the
   * caller's own mailbox.
   * <p>
   * Both lookups are scoped to the caller: the folder row by {@code (id, userId)}, the
   * delegation by {@code (id, granteeId)}. A key naming somebody else's folder, or a
   * folder whose delegation is not the caller's, answers null and is then treated as an
   * own-mailbox key by every caller -- which is safe, because the row it would act on
   * does not exist for this user either.
   *
   * @param username the caller
   * @param folderKey the {@code EMAIL_BOX.FOLDER} discriminator
   * @return the delegation, or null for an own folder
   */
  public EmailDelegation delegationOf(String username, String folderKey) {
    if (StringUtils.isBlank(username) || !MailFolder.isCustom(folderKey)) {
      return null;
    }
    EmailFolder folder;
    try {
      folder = emailFolderStorage.getFolder(username, MailFolder.customId(folderKey));
    } catch (IllegalArgumentException malformed) {
      return null;
    }
    if (folder == null || folder.getDelegationId() == null) {
      return null;
    }
    return emailDelegationStorage.getAsGrantee(username, folder.getDelegationId());
  }

  /**
   * The rights the server last told us the caller holds on a folder of a shared
   * mailbox, or null when the key is one of their own folders.
   *
   * @param username the caller
   * @param folderKey the {@code EMAIL_BOX.FOLDER} discriminator
   * @return the rights, or null for an own folder
   */
  public MailboxRights rightsOn(String username, String folderKey) {
    EmailFolder folder = delegatedFolderOf(username, folderKey);
    EmailDelegation delegation = folder == null ? null : emailDelegationStorage.getAsGrantee(username, folder.getDelegationId());
    return delegation == null ? null : folderRights(folder, delegation);
  }

  /**
   * The key of a shared mailbox's folder of one role -- where a delete, an archive or a
   * "mark as spam" in that mailbox files (EXO-90548): the same share's folder, never one
   * of the caller's own. Missing folders do not count.
   *
   * @param username the delegate
   * @param delegationId the share
   * @param role the role
   * @return the {@code CUSTOM:<id>} key, or null when the share has no such folder
   */
  public String roleFolderKey(String username, long delegationId, FolderRole role) {
    return emailFolderStorage.getDelegatedFolders(username, delegationId)
                             .stream()
                             .filter(folder -> MailFolderView.TYPE_DELEGATED.equals(folder.getType()))
                             .filter(folder -> !folder.isMissing())
                             .filter(folder -> folder.getRole() == role)
                             .map(EmailFolder::getKey)
                             .findFirst()
                             .orElse(null);
  }

  /**
   * Where a mail the delegate sends from a shared mailbox is filed for its owner
   * (EXO-90551): that share's Sent folder, when the delegate may insert into it. The
   * share is resolved with the caller as grantee, so a share of somebody else -- or an
   * unknown id -- is "no such delegation", and one no longer accepted is a revocation:
   * a client-supplied id never selects another user's folder.
   *
   * @param granteeUsername the sender, who must be the share's grantee
   * @param delegationId the share the mail is sent from
   * @return the owner's Sent folder key, or null when the share has no Sent the sender
   *         may file into (no Sent shared, no i there)
   * @throws ObjectNotFoundException when no such share belongs to the sender
   * @throws DelegationRevokedException when the share is no longer accepted
   */
  public String ownerSentFolderKey(String granteeUsername, long delegationId) throws ObjectNotFoundException {
    EmailDelegation delegation = asGrantee(granteeUsername, delegationId);
    if (delegation.getStatus() != DelegationStatus.ACCEPTED) {
      throw new DelegationRevokedException(DelegationRevokedException.REVOKED);
    }
    String key = roleFolderKey(granteeUsername, delegationId, FolderRole.SENT);
    if (key == null) {
      return null;
    }
    try {
      checkRight(granteeUsername, key, MailboxRights.INSERT);
      return key;
    } catch (MailboxRightMissingException e) {
      return null;
    }
  }

  /**
   * A mailbox shared with the caller, named the way a person or an agent names it
   * (EXO-90555): by its address, or by its owner's eXo username, ignoring case. The ONE
   * place an agent's {@code mailbox} argument is resolved, and it resolves among
   * {@link #getSharedMailboxes} alone -- the caller's own received shares, ACCEPTED,
   * with a registered INBOX -- so a pending, declined, revoked or gone share, somebody
   * else's share, a share without a mirror and a name that matches nothing all get the
   * same answer: not found. Nothing distinguishes "not yours" from "does not exist".
   * <p>
   * A blank name resolves to nothing either: "the user's own mailbox" is the caller's
   * decision to make by not naming one, never this method's fallback. And nothing
   * resolves while the caller's own mail access is switched off
   * ({@link #getUsableSharedMailboxes}).
   *
   * @param granteeUsername the caller
   * @param mailbox the owner's mailbox address or eXo username
   * @return the shared mailbox, never null
   * @throws ObjectNotFoundException when no accepted share of the caller's has that name
   */
  public SharedMailboxEntry getSharedMailbox(String granteeUsername, String mailbox) throws ObjectNotFoundException {
    String wanted = StringUtils.trimToNull(mailbox);
    if (wanted == null) {
      throw new ObjectNotFoundException(SHARED_MAILBOX_NOT_FOUND_MESSAGE);
    }
    return getUsableSharedMailboxes(granteeUsername).stream()
                                              .filter(entry -> wanted.equalsIgnoreCase(entry.ownerMailbox())
                                                  || wanted.equalsIgnoreCase(entry.ownerId()))
                                              .findFirst()
                                              .orElseThrow(() -> new ObjectNotFoundException(SHARED_MAILBOX_NOT_FOUND_MESSAGE));
  }

  /**
   * The shared mailboxes an agent may work in (EXO-90555): {@link #getSharedMailboxes},
   * while the caller may use mail at all -- the email feature on, their connector active
   * and theirs ({@code UserEmailSettingService.canConnect}, the switch every one of the
   * caller's own mailbox reads is behind) -- and none otherwise. A shared mailbox is
   * reached through the caller's own session, so it is switched off with it.
   *
   * @param granteeUsername the caller
   * @return the entries, empty when the caller's mail access is switched off
   */
  public List<SharedMailboxEntry> getUsableSharedMailboxes(String granteeUsername) {
    UserEmailSetting setting = StringUtils.isBlank(granteeUsername) ? null : userEmailSettingService.getUserEmailSetting(granteeUsername);
    if (setting == null || StringUtils.isBlank(setting.getEmailConnectorId())
        || !userEmailSettingService.canConnect(Long.parseLong(setting.getEmailConnectorId()), granteeUsername)) {
      return List.of();
    }
    return getSharedMailboxes(granteeUsername);
  }

  /**
   * The key of a shared mailbox's folder of one kind, when that folder is in the
   * caller's mirror (EXO-90555): registered for that share (by DELEGATION_ID), still
   * listed by the server, readable with the caller's letters on it, opted in, and synced
   * at least once. The periodic pass mirrors the shared INBOX only; the owner's other
   * folders are mirrored once somebody opens them, so a folder can be shared and still
   * hold nothing yet -- which is "not available", never "no mail".
   *
   * @param granteeUsername the caller
   * @param share the shared mailbox, as {@link #getSharedMailbox} resolved it
   * @param folder {@code INBOX} (or blank), {@code SENT} or {@code ARCHIVE}
   * @return the {@code CUSTOM:<id>} key, or null when that folder is not in the mirror
   * @throws IllegalArgumentException {@code emailConnector.folder.notBrowsable} for any
   *           other folder
   */
  public String getMirroredFolderKey(String granteeUsername, SharedMailboxEntry share, String folder) {
    boolean inbox = StringUtils.isBlank(folder) || MailFolder.INBOX.equals(folder);
    if (!inbox && !MailFolder.SENT.equals(folder) && !MailFolder.ARCHIVE.equals(folder)) {
      throw new IllegalArgumentException("emailConnector.folder.notBrowsable");
    }
    return mirroredFolderKey(emailFolderStorage.getDelegatedFolders(granteeUsername, share.delegationId()),
                             share,
                             inbox ? MailFolder.INBOX : folder);
  }

  /**
   * Which of a shared mailbox's INBOX, SENT and ARCHIVE are in the caller's mirror
   * (EXO-90555), by the rule of {@link #getMirroredFolderKey}, from one read of the
   * share's folders.
   *
   * @param granteeUsername the caller
   * @param share the shared mailbox, as {@link #getSharedMailbox} resolved it
   * @return the available folders among INBOX, SENT and ARCHIVE, in that order
   */
  public List<String> getMirroredFolders(String granteeUsername, SharedMailboxEntry share) {
    List<EmailFolder> folders = emailFolderStorage.getDelegatedFolders(granteeUsername, share.delegationId());
    return Stream.of(MailFolder.INBOX, MailFolder.SENT, MailFolder.ARCHIVE)
                 .filter(folder -> mirroredFolderKey(folders, share, folder) != null)
                 .toList();
  }

  /**
   * The rule of {@link #getMirroredFolderKey}, over a share's registered folders: the
   * folder of that kind still listed, opted in, synced at least once, and readable with
   * the caller's letters on it.
   *
   * @param folders the share's registered folders
   * @param share the shared mailbox
   * @param folder INBOX, SENT or ARCHIVE
   * @return the key, or null
   */
  private static String mirroredFolderKey(List<EmailFolder> folders, SharedMailboxEntry share, String folder) {
    boolean inbox = MailFolder.INBOX.equals(folder);
    FolderRole role = MailFolder.SENT.equals(folder) ? FolderRole.SENT : FolderRole.ARCHIVE;
    return folders.stream()
                  .filter(candidate -> inbox ? MailFolderView.TYPE_DELEGATED_INBOX.equals(candidate.getType())
                                             : MailFolderView.TYPE_DELEGATED.equals(candidate.getType())
                                                 && candidate.getRole() == role)
                  .filter(candidate -> !candidate.isMissing())
                  .filter(EmailFolder::isSyncEnabled)
                  .filter(candidate -> candidate.getLastSyncDate() != null)
                  .filter(candidate -> folderRights(candidate, share.rights()).canRead())
                  .map(EmailFolder::getKey)
                  .findFirst()
                  .orElse(null);
  }


  /**
   * Whether an administrator left the owner's Sent copy on (EXO-90551). A setting that
   * cannot be read answers no rather than failing the switcher: the switcher then
   * promises no copy, which is the safe side of the promise.
   *
   * @return true when the copy is switched on
   */
  private boolean isSentCopyEnabled() {
    try {
      return emailConnectorService.isSharedMailboxSentCopyEnabled();
    } catch (RuntimeException e) {
      LOG.debug("Could not read whether the owner's Sent copy is switched on; the switcher promises none", e);
      return false;
    }
  }

  /**
   * Whether a mail sent from this share is filed in its owner's Sent (EXO-90551), for the
   * switcher entry, from the rows the list already holds: the answer
   * {@link #ownerSentFolderKey} gives for an accepted share -- the share's Sent, still
   * listed, and the delegate's letters on it holding i -- without reading the share and
   * its folders again per entry. Both read the letters through {@link #folderRights}, so
   * the promise and the send agree.
   *
   * @param folders the share's registered folders
   * @param delegation the accepted share
   * @return true when the copy will be filed
   */
  private static boolean sentCopyOf(List<EmailFolder> folders, EmailDelegation delegation) {
    return folders.stream()
                  .filter(folder -> MailFolderView.TYPE_DELEGATED.equals(folder.getType()))
                  .filter(folder -> !folder.isMissing())
                  .filter(folder -> folder.getRole() == FolderRole.SENT)
                  .findFirst()
                  .map(folder -> folderRights(folder, delegation).has(MailboxRights.INSERT))
                  .orElse(false);
  }

  /**
   * The role a folder key has in the owner's mailbox, when it is a folder of a shared
   * mailbox of the caller's.
   *
   * @param username the caller
   * @param folderKey the key
   * @return the role, null for the shared INBOX, a folder without role, or an own folder
   */
  public FolderRole roleOf(String username, String folderKey) {
    EmailFolder folder = delegatedFolderOf(username, folderKey);
    return folder == null ? null : folder.getRole();
  }

  /**
   * Re-reads the caller's letters on one folder of a shared mailbox, on the caller's own
   * store -- after a write the server acknowledged but did not do (Dovecot answers an
   * expunge it refused with a tagged OK, EXO-90548), so the next attempt is refused by
   * eXo with the right reason and the chrome stops offering it. The shared INBOX goes
   * through {@link #refreshGranteeRights}. Best-effort: a failure leaves the letters as
   * they were.
   *
   * @param username the delegate
   * @param folderKey the folder's key
   * @param store the delegate's connected store, borrowed
   */
  public void refreshFolderRights(String username, String folderKey, Store store) {
    EmailFolder folder = delegatedFolderOf(username, folderKey);
    EmailDelegation delegation = folder == null ? null : emailDelegationStorage.getAsGrantee(username, folder.getDelegationId());
    if (delegation == null || delegation.getStatus() != DelegationStatus.ACCEPTED) {
      return;
    }
    if (MailFolderView.TYPE_DELEGATED_INBOX.equals(folder.getType())) {
      refreshGranteeRights(username, delegation, store);
      return;
    }
    EmailConnector connector = emailConnectorService.getEmailConnector(delegation.getConnectorId());
    if (connector == null) {
      return;
    }
    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    MailboxAclSession session = new MailboxAclSession(connector, username, delegation.getGranteeMailbox(), () -> store, null);
    try {
      MailboxRights rights = rightsOf(engine, session, folder.getRemoteName());
      emailFolderStorage.updateDelegatedRights(username, folder.getId(), delegation.getId(), folder.getRole(), rights.letters(), new Date());
    } catch (MailboxAclException e) {
      LOG.debug("The rights of {} on folder {} could not be re-read ({})", username, folderKey, e.getCode());
    }
  }

  /**
   * The registered folder a key names, when it is a folder of a shared mailbox of the
   * caller's; null for an own folder, an unknown key or a malformed one.
   *
   * @param username the caller
   * @param folderKey the {@code EMAIL_BOX.FOLDER} discriminator
   * @return the delegated folder, or null
   */
  private EmailFolder delegatedFolderOf(String username, String folderKey) {
    if (StringUtils.isBlank(username) || !MailFolder.isCustom(folderKey)) {
      return null;
    }
    EmailFolder folder;
    try {
      folder = emailFolderStorage.getFolder(username, MailFolder.customId(folderKey));
    } catch (IllegalArgumentException malformed) {
      return null;
    }
    return folder == null || folder.getDelegationId() == null ? null : folder;
  }

  /**
   * <b>The guard.</b> Refuses an operation on a folder of a shared mailbox when the
   * right it needs is not among the letters the server grants the caller there -- on
   * that folder since EXO-90548, whose letters may differ from the INBOX's.
   * <p>
   * Three things about it are deliberate.
   * <ul>
   * <li><b>An own folder passes untouched.</b> The check is "is this key delegated, and
   * if so may I", never "is this key mine" -- so it can be put in front of every write
   * path without changing what any of them do for the mailbox the user owns.</li>
   * <li><b>A share that is no longer accepted is a {@link DelegationRevokedException},
   * not a refusal.</b> The two mean different things to the interface: the first says
   * "this mailbox is gone, leave it", the second says "you may not do THAT here".</li>
   * <li><b>It is not the enforcement.</b> The server enforces the ACL and would refuse
   * the same operation itself; this turns its {@code NO} -- which arrives as a
   * protocol exception whose text may name internal paths and users -- into a message
   * code, and stops eXo optimistically writing a local change the server was never
   * going to keep.</li>
   * </ul>
   *
   * @param username the caller
   * @param folderKey the {@code EMAIL_BOX.FOLDER} discriminator acted on
   * @param right the RFC 4314 letter the operation needs
   * @throws MailboxRightMissingException when the folder is delegated and the letter is
   *           not held
   * @throws DelegationRevokedException when the share is no longer accepted
   */
  public void checkRight(String username, String folderKey, char right) throws MailboxRightMissingException {
    EmailFolder folder = delegatedFolderOf(username, folderKey);
    EmailDelegation delegation = folder == null ? null : emailDelegationStorage.getAsGrantee(username, folder.getDelegationId());
    if (delegation == null) {
      return;
    }
    if (delegation.getStatus() != DelegationStatus.ACCEPTED) {
      throw new DelegationRevokedException(DelegationRevokedException.REVOKED);
    }
    // THAT folder's letters (EXO-90548): an Editor holds e on INBOX and not on Trash.
    MailboxRights rights = folderRights(folder, delegation);
    if (!rights.has(right)) {
      LOG.debug("User {} was refused right '{}' on delegated folder {} of mailbox {} (rights {})",
                username,
                right,
                folderKey,
                delegation.getOwnerMailbox(),
                rights.letters());
      throw new MailboxRightMissingException(right);
    }
  }

  /**
   * Refuses an operation that only makes sense on a folder of the caller's OWN mailbox,
   * addressed by its registry id: creating, renaming or deleting a folder belongs to
   * whoever owns the mailbox, and a delegate is never offered it.
   * <p>
   * Why by id rather than by letter. Rename and delete of a mailbox are RFC 4314's
   * {@code x}, which eXo never grants and never will from its own presets, so the
   * letter check would be a refusal in every case that can arise -- but it would ALSO
   * admit the case where a server-made share happens to carry {@code x}, and let a
   * delegate rename or destroy a folder of somebody else's mailbox from a screen that
   * only ever meant to manage their own. The folder-settings screen does not list
   * delegated folders at all; this is what makes the REST endpoints behind it agree.
   *
   * @param username the caller
   * @param folderId the registry id
   * @throws MailboxRightMissingException always, when the folder is a delegated one
   */
  public void checkOwnFolder(String username, long folderId) throws MailboxRightMissingException {
    EmailFolder folder = emailFolderStorage.getFolder(username, folderId);
    if (folder != null && folder.getDelegationId() != null) {
      throw new MailboxRightMissingException(MailboxRights.DELETE_MAILBOX);
    }
  }

  // ---------------------------------------------------------------------------------
  // Resolution
  // ---------------------------------------------------------------------------------

  /**
   * The IMAP identifier of a grantee, from THEIR OWN connected setting on the given
   * preset -- the one way eXo knows an identifier without trusting typed input. The
   * user must exist and be enabled, and must be connected on the same preset as the
   * owner; otherwise the grant is refused with the code that tells the owner what to do.
   * <p>
   * TODO (phase 0): the identifier is the login the grantee connects with
   * ({@code UserEmailSetting.emailAddress}). Whether a server's ACL names the user by
   * that login, by its local part, or by a directory id is a live check per server; a
   * connector whose credentials provider derives the login (the sudo providers) is a
   * second question.
   *
   * @param granteeUsername the eXo username
   * @param connectorId the owner's connector preset
   * @return the identifier
   * @throws IllegalArgumentException with a message code when the user is unknown or
   *           not connected on that preset
   */
  String resolveGranteeIdentifier(String granteeUsername, long connectorId) {
    if (identityManager != null) {
      Identity identity = identityManager.getOrCreateUserIdentity(granteeUsername);
      if (identity == null || !identity.isEnable() || identity.isDeleted()) {
        throw new IllegalArgumentException(GRANTEE_UNKNOWN_MESSAGE);
      }
    }
    String identifier = resolveGranteeIdentifierOrNull(granteeUsername, connectorId);
    if (identifier == null) {
      throw new IllegalArgumentException(GRANTEE_NOT_CONNECTED_MESSAGE);
    }
    return identifier;
  }

  /**
   * The identifier of a user connected on a preset, or null when they are not.
   *
   * @param username the eXo username
   * @param connectorId the connector preset
   * @return the identifier, or null
   */
  private String resolveGranteeIdentifierOrNull(String username, long connectorId) {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    if (setting == null || StringUtils.isBlank(setting.getEmailConnectorId())
        || !String.valueOf(connectorId).equals(setting.getEmailConnectorId())) {
      return null;
    }
    return mailboxIdentifier(setting);
  }

  /**
   * The eXo users connected on a preset, by the identifier they connect with, for
   * mapping the server's identifiers back to people. Reads every connected setting of
   * the preset -- a settings-screen read, once per open, over the connected users of one
   * server.
   *
   * @param connectorId the connector preset
   * @return identifier (lower-case) to username
   */
  private Map<String, String> connectedUsersByIdentifier(long connectorId) {
    Map<String, String> users = new HashMap<>();
    for (String username : userEmailSettingService.getUserEmailSettingsByEmailConnectorId(connectorId)) {
      UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
      String identifier = setting == null ? null : mailboxIdentifier(setting);
      if (StringUtils.isNotBlank(identifier)) {
        users.putIfAbsent(identifier.toLowerCase(Locale.ROOT), username);
      }
    }
    return users;
  }

  /**
   * The identifier of a mailbox as its ACL and namespace name it, from its owner's
   * setting.
   *
   * @param setting the connected setting
   * @return the identifier
   */
  private String mailboxIdentifier(UserEmailSetting setting) {
    return StringUtils.trimToNull(setting.getEmailAddress());
  }

  /**
   * The owner's ACL merged with eXo's rows. Every ACL entry but the owner's own and
   * {@code anyone} is an entry of the list; rows the ACL no longer carries are revoked;
   * connected users the ACL carries without a row get an available one.
   *
   * @param ownerUsername the owner
   * @param ownerMailbox the owner's identifier
   * @param connector the preset
   * @param acl the server's entries
   * @param rows eXo's rows for this owner
   * @return the entries, server order
   */
  private List<DelegationGrantee> merge(String ownerUsername,
                                        String ownerMailbox,
                                        EmailConnector connector,
                                        List<MailboxAce> acl,
                                        List<EmailDelegation> rows) {
    Map<String, String> connected = connectedUsersByIdentifier(connector.getId());
    Map<String, EmailDelegation> rowsByGrantee = new HashMap<>();
    for (EmailDelegation row : rows) {
      if (connector.getId().equals(row.getConnectorId()) && ownerMailbox.equalsIgnoreCase(row.getOwnerMailbox())) {
        rowsByGrantee.put(row.getGranteeId(), row);
      }
    }
    List<DelegationGrantee> grantees = new ArrayList<>();
    Set<String> seenGrantees = new HashSet<>();
    for (MailboxAce ace : acl) {
      String identifier = StringUtils.trimToEmpty(ace.identifier());
      if (identifier.isEmpty() || identifier.equalsIgnoreCase(ownerMailbox) || ANYONE.equalsIgnoreCase(identifier)) {
        continue;
      }
      String granteeId = connected.get(identifier.toLowerCase(Locale.ROOT));
      EmailDelegation row = granteeId == null ? rowByIdentifier(rows, identifier) : rowsByGrantee.get(granteeId);
      if (row == null && granteeId != null) {
        row = new EmailDelegation();
        row.setGranteeId(granteeId);
        row.setOwnerId(ownerUsername);
        row.setOwnerMailbox(ownerMailbox);
        row.setGranteeMailbox(identifier);
        row.setConnectorId(connector.getId());
        row.setPreset(ace.preset() == null ? DelegationPreset.CUSTOM : ace.preset());
        row.setRights(ace.rights().letters());
        row.setNativeRights(ace.nativeRights());
        row.setStatus(DelegationStatus.AVAILABLE);
        row.setOrigin(DelegationOrigin.SERVER);
        row.setLastRightsCheckDate(new Date());
        row = createOrReread(row);
      } else if (row != null && (row.getStatus() == DelegationStatus.REVOKED || row.getStatus() == DelegationStatus.GONE)) {
        // The owner's own ACL names the grantee again (#443-2): the share stands on the
        // server, so the row is not left dead in eXo -- offered to the grantee again,
        // with the letters the ACL holds.
        row.setRights(ace.rights().letters());
        row.setNativeRights(ace.nativeRights());
        row = reopen(row);
      } else if (row != null && row.getStatus() != DelegationStatus.ACCEPTED
                 && (!ace.rights().letters().equals(row.getRights())
                     || !StringUtils.equals(ace.nativeRights(), row.getNativeRights()))) {
        // Not on a share in use (EXO-90557): its stored rights are what the grantee's own
        // MYRIGHTS answered at their last sync (refreshGranteeRights) -- what their
        // mailbox's controls and guards read -- and the owner's ACE, which a server may
        // spell differently, is shown to the owner from the ACL itself, not from the row.
        // Targeted (EXO-90548 review, finding 1): an Extend of a pending share may have
        // written its folder roles since these rows were read.
        EmailDelegation refreshed = emailDelegationStorage.updateOfferedRights(ownerUsername,
                                                                              row.getId(),
                                                                              ace.rights().letters(),
                                                                              ace.nativeRights());
        row = refreshed == null ? row : refreshed;
      }
      if (row != null) {
        seenGrantees.add(row.getGranteeId());
      }
      grantees.add(DelegationGrantee.of(ace, row == null ? granteeId : row.getGranteeId(), row));
    }
    for (EmailDelegation row : rowsByGrantee.values()) {
      if (seenGrantees.contains(row.getGranteeId())) {
        continue;
      }
      if (row.getStatus() == DelegationStatus.PENDING || row.getStatus() == DelegationStatus.ACCEPTED
          || row.getStatus() == DelegationStatus.DECLINED || row.getStatus() == DelegationStatus.AVAILABLE) {
        // The server no longer carries the entry: an administrator, the server's own
        // interface, or the revoke the owner just asked for removed it. The server is
        // the truth.
        markRevoked(row, DelegationStatus.REVOKED);
      }
      // And it is NOT a grantee any more, so it does not belong in a list of who holds
      // access. Adding it here made a successful revoke look like a failed one: the row
      // went REVOKED on the server and in the database, and came straight back to the
      // screen the owner had just removed it from, with no way to remove it again.
    }
    return grantees;
  }

  /**
   * eXo's rows as entries, for a server on which the ACL cannot be read.
   *
   * @param rows the owner's rows
   * @return the entries
   */
  private List<DelegationGrantee> rowsOnly(List<EmailDelegation> rows) {
    List<DelegationGrantee> grantees = new ArrayList<>();
    for (EmailDelegation row : rows) {
      grantees.add(new DelegationGrantee(row.getGranteeMailbox(),
                                         row.getGranteeId(),
                                         row,
                                         row.getPreset(),
                                         row.getRights(),
                                         row.getNativeRights(),
                                         row.getMailboxRights().affordances(),
                                         List.of()));
    }
    return grantees;
  }

  /**
   * The row whose grant was written for an identifier, for a grantee who has since
   * disconnected (no longer mapped through their setting).
   *
   * @param rows the owner's rows
   * @param identifier the ACL identifier
   * @return the row, or null
   */
  private EmailDelegation rowByIdentifier(List<EmailDelegation> rows, String identifier) {
    for (EmailDelegation row : rows) {
      if (identifier.equalsIgnoreCase(row.getGranteeMailbox())) {
        return row;
      }
    }
    return null;
  }

  /**
   * Walks the caller's Other Users namespace and reconciles it with their rows. Not
   * gated on NAMESPACE being advertised: the engine finds the prefix from the
   * session's own LIST when it is not (Stalwart served {@code Shared Folders/} without
   * advertising it -- plan, section 13.C); only an unsupported server skips the walk.
   *
   * @param granteeUsername the caller
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws MailboxAclException when the server cannot be asked
   */
  private void discoverShares(String granteeUsername) throws IllegalAccessException {
    UserEmailSetting granteeSetting = connectedSetting(granteeUsername);
    EmailConnector connector = connectorOf(granteeSetting);
    MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
    List<EmailDelegation> rows = emailDelegationStorage.getReceived(granteeUsername);
    try (MailboxAclSession session = session(connector, granteeUsername, mailboxIdentifier(granteeSetting))) {
      if (!engine.probe(session).supported()) {
        return;
      }
      List<SharedMailbox> shared = engine.listSharedMailboxes(session);
      Map<String, String> connected = null;
      Set<String> listedRoots = new HashSet<>();
      for (SharedMailbox mailbox : shared) {
        listedRoots.add(mailbox.remoteRoot());
        EmailDelegation known = rowFor(rows, connector.getId(), mailbox);
        if (known != null) {
          if (known.getStatus() == DelegationStatus.REVOKED || known.getStatus() == DelegationStatus.GONE) {
            // The server lists the share again (#443-2): a transient refusal, the stale
            // rights window, a listing miss -- REVOKED was one negative answer, not the
            // truth. Offered again, never subscribed on the grantee's behalf.
            reopen(known);
          }
          continue;
        }
        if (connected == null) {
          connected = connectedUsersByIdentifier(connector.getId());
        }
        MailboxRights rights;
        try {
          rights = engine.myRights(session, mailbox.inboxName());
        } catch (MailboxAclException e) {
          LOG.debug("MYRIGHTS on shared mailbox '{}' refused for {}: {}", mailbox.remoteRoot(), granteeUsername, e.getDetail());
          continue;
        }
        EmailDelegation row = new EmailDelegation();
        row.setGranteeId(granteeUsername);
        row.setOwnerId(ownerFor(connected, mailbox.ownerIdentifier()));
        row.setOwnerMailbox(mailbox.ownerIdentifier());
        row.setConnectorId(connector.getId());
        row.setRemoteRoot(mailbox.remoteRoot());
        row.setPreset(engine.presetOf(rights));
        row.setRights(rights.letters());
        row.setNativeRights(rights.letters());
        row.setStatus(DelegationStatus.AVAILABLE);
        row.setOrigin(DelegationOrigin.SERVER);
        row.setLastRightsCheckDate(new Date());
        createOrReread(row);
      }
      for (EmailDelegation row : rows) {
        if (row.getStatus() == DelegationStatus.ACCEPTED && connector.getId().equals(row.getConnectorId())
            && row.getRemoteRoot() != null && !listedRoots.contains(row.getRemoteRoot())) {
          markRevoked(row, DelegationStatus.GONE);
        }
      }
    }
  }

  /**
   * Offers again a share eXo had ended (REVOKED, GONE) that the server lists again
   * (#443-2): back to AVAILABLE -- proposed to the grantee, never subscribed on their
   * behalf -- with its revoke date cleared and its rights checked now.
   *
   * @param row the ended row
   * @return the row as it now stands
   */
  private EmailDelegation reopen(EmailDelegation row) {
    row.setStatus(DelegationStatus.AVAILABLE);
    row.setRevokedDate(null);
    row.setLastRightsCheckDate(new Date());
    LOG.info("Mailbox delegation listed again by the server: grantee={} ownerMailbox={}", row.getGranteeId(), row.getOwnerMailbox());
    return emailDelegationStorage.update(row);
  }

  /**
   * Creates a row a listing found, or answers the one a concurrent listing -- another
   * tab, another node -- created first under the same key (#432-6): the unique key
   * refuses the second insert, and the share is the same share either way.
   *
   * @param row the row to create
   * @return the row as stored, or null when neither could be read
   */
  private EmailDelegation createOrReread(EmailDelegation row) {
    try {
      return emailDelegationStorage.create(row);
    } catch (DataIntegrityViolationException raced) {
      LOG.debug("A concurrent listing created the share of {} on {} first", row.getGranteeId(), row.getOwnerMailbox());
      return emailDelegationStorage.getByKey(row.getGranteeId(), row.getConnectorId(), row.getOwnerMailbox());
    }
  }

  /**
   * The row of a listed shared mailbox among the caller's rows, matched the way
   * {@link MailboxAclEngine#findSharedMailbox} matches: remote root, whole identifier,
   * then local part -- that one only when a single row has it.
   *
   * @param rows the caller's rows
   * @param connectorId the preset
   * @param mailbox the listed mailbox
   * @return the row, or null
   */
  private EmailDelegation rowFor(List<EmailDelegation> rows, long connectorId, SharedMailbox mailbox) {
    String segment = StringUtils.trimToEmpty(mailbox.ownerIdentifier()).toLowerCase(Locale.ROOT);
    EmailDelegation byLocalPart = null;
    boolean ambiguous = false;
    for (EmailDelegation row : rows) {
      if (!Long.valueOf(connectorId).equals(row.getConnectorId())) {
        continue;
      }
      if (mailbox.remoteRoot() != null && mailbox.remoteRoot().equals(row.getRemoteRoot())) {
        return row;
      }
      String owner = StringUtils.trimToEmpty(row.getOwnerMailbox()).toLowerCase(Locale.ROOT);
      if (segment.equals(owner)) {
        return row;
      }
      String localPart = owner.contains("@") ? owner.substring(0, owner.indexOf('@')) : owner;
      if (segment.equals(localPart)) {
        // Only when it is the one row with that local part (#432-5).
        ambiguous = byLocalPart != null;
        byLocalPart = row;
      }
    }
    return ambiguous ? null : byLocalPart;
  }

  /**
   * The eXo user a namespace segment maps to, whole identifier then local part -- that
   * one only when a single connected user has it.
   *
   * @param connected identifier (lower-case) to username
   * @param ownerIdentifier the segment
   * @return the username, or null
   */
  private String ownerFor(Map<String, String> connected, String ownerIdentifier) {
    String segment = StringUtils.trimToEmpty(ownerIdentifier).toLowerCase(Locale.ROOT);
    String owner = connected.get(segment);
    if (owner != null) {
      return owner;
    }
    // The local part only when exactly one connected user has it (#432-5): on a preset
    // serving several domains, "anne" is nobody in particular.
    String byLocalPart = null;
    for (Map.Entry<String, String> entry : connected.entrySet()) {
      String key = entry.getKey();
      String localPart = key.contains("@") ? key.substring(0, key.indexOf('@')) : key;
      if (localPart.equals(segment)) {
        if (byLocalPart != null && !byLocalPart.equals(entry.getValue())) {
          return null;
        }
        byLocalPart = entry.getValue();
      }
    }
    return byLocalPart;
  }

  /**
   * Registers the shared INBOX as a folder of the grantee, or adopts the row the
   * grantee's own discovery walk already registered under that remote name.
   *
   * @param granteeUsername the grantee
   * @param delegation the accepted row
   * @param shared the mailbox as the namespace listed it
   */
  private void registerDelegatedInbox(String granteeUsername, EmailDelegation delegation, SharedMailbox shared) {
    Date now = new Date();
    EmailFolder existing = emailFolderStorage.getFolderByRemoteName(granteeUsername, shared.inboxName());
    if (existing != null) {
      if (!delegation.getId().equals(existing.getDelegationId())) {
        emailFolderStorage.adoptAsDelegated(granteeUsername, existing.getId(), delegation.getId(), MailFolderView.TYPE_DELEGATED_INBOX);
      }
      enableDelegatedInboxSync(granteeUsername, existing, now);
      return;
    }
    EmailFolder folder = new EmailFolder();
    folder.setUserId(granteeUsername);
    folder.setRemoteName(shared.inboxName());
    folder.setDisplayName(MailFolder.INBOX);
    folder.setDelimiter(shared.delimiter());
    folder.setType(MailFolderView.TYPE_DELEGATED_INBOX);
    folder.setDelegationId(delegation.getId());
    folder.setDiscoveredDate(now);
    folder.setLastSeenDate(now);
    enableDelegatedInboxSync(granteeUsername, emailFolderStorage.createFolder(folder), now);
  }

  /**
   * Opts the delegated INBOX in, which is what makes accepting a share actually mirror
   * anything: {@code EmailFolderStorage.createFolder} writes every row opted OUT, on
   * purpose -- for a folder DISCOVERED in the user's own mailbox, nothing is mirrored
   * until the user asks. A shared mailbox's INBOX is the opposite case: the user just
   * asked, by accepting, and a share that mirrored nothing until a second click in a
   * settings screen that does not even list delegated folders would simply look broken.
   * <p>
   * The INBOX alone. Every other folder of the shared mailbox stays opted out, which is
   * the "INBOX only unless the delegate opts a folder in" rule of the sync budget.
   *
   * @param granteeUsername the grantee -- the rows' viewer
   * @param folder the registered delegated INBOX
   * @param now the opt-in stamp
   */
  private void enableDelegatedInboxSync(String granteeUsername, EmailFolder folder, Date now) {
    if (folder != null && folder.getId() != null && !folder.isSyncEnabled()) {
      emailFolderStorage.updateSyncEnabled(granteeUsername, folder.getId(), true, now);
    }
  }

  /**
   * Drops a share's folders from the delegate's registry, and the mail mirrored under
   * them with them (stack review #437-1): the keys are read before the rows go, and the
   * mailbox cache purges them on the event. The one way a share's folders are dropped --
   * revoke, leave, a disconnect, a withdrawal found on the server -- so no path can
   * leave the owner's mail in the delegate's database, where it would resurface as the
   * delegate's own once nothing marks it as shared any more.
   *
   * @param granteeUsername the delegate
   * @param delegationId the share
   */
  private void dropDelegatedFolders(String granteeUsername, long delegationId) {
    List<String> keys = emailFolderStorage.getDelegatedFolders(granteeUsername, delegationId).stream().map(EmailFolder::getKey).toList();
    emailFolderStorage.deleteDelegatedFolders(granteeUsername, delegationId);
    if (!keys.isEmpty() && eventPublisher != null) {
      eventPublisher.publishEvent(new DelegatedFoldersDroppedEvent(granteeUsername, keys));
    }
  }

  /**
   * Records that the server no longer grants a row's access.
   *
   * @param delegation the row
   * @param status REVOKED or GONE
   * @return the row as it now stands
   */
  private EmailDelegation markRevoked(EmailDelegation delegation, DelegationStatus status) {
    boolean wasInUse = delegation.getStatus() == DelegationStatus.ACCEPTED;
    delegation.setStatus(status);
    delegation.setRevokedDate(new Date());
    // Back off by default (#441-1): a share taken up again must be chosen again to count
    // in the badge, as a new one is (plan 7.7).
    delegation.setBadgeIncluded(false);
    EmailDelegation updated = emailDelegationStorage.update(delegation);
    dropDelegatedFolders(updated.getGranteeId(), updated.getId());
    if (wasInUse) {
      // Found gone by a reconciliation rather than by anybody's act: no notification
      // says so, but a grantee who counted this inbox in their badge must see it stop
      // counting (EXO-90546). No actor: the server decided.
      publish(EmailDelegationEvent.Type.RIGHTS_CHANGED, null, updated);
    }
    return updated;
  }

  // ---------------------------------------------------------------------------------
  // Sessions and rows
  // ---------------------------------------------------------------------------------

  /**
   * The caller's connected setting.
   *
   * @param username the caller
   * @return the setting
   * @throws IllegalAccessException when the caller has no connected mailbox
   */
  private UserEmailSetting connectedSetting(String username) throws IllegalAccessException {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    if (setting == null || StringUtils.isBlank(setting.getEmailConnectorId()) || StringUtils.isBlank(setting.getEmailAddress())) {
      throw new IllegalAccessException("emailConnector.notConnected");
    }
    return setting;
  }

  /**
   * The preset a setting is bound to.
   *
   * @param setting the connected setting
   * @return the connector
   * @throws IllegalAccessException when the preset no longer exists
   */
  private EmailConnector connectorOf(UserEmailSetting setting) throws IllegalAccessException {
    EmailConnector connector = emailConnectorService.getEmailConnector(Long.parseLong(setting.getEmailConnectorId()));
    if (connector == null) {
      throw new IllegalAccessException("emailConnector.notConnected");
    }
    return connector;
  }

  /**
   * The caller's own session for the engine -- and the ONE place a session is built.
   * Its store opener is {@link UserEmailSettingService#connect(String, String)} with
   * the caller's own connector and username, the same call the sync makes; its HTTP
   * material is the caller's own, through the platform's credentials contract on the
   * channel the CardDAV sync uses. Nothing here can name anyone but the caller, which
   * is what lets the engine be handed the session without a second thought about
   * whose identity it acts under. Nothing is opened until an engine asks; the
   * try-with-resources at each call site closes what was.
   *
   * @param connector the caller's connector preset
   * @param username the caller
   * @param mailboxIdentifier the caller's own mailbox identifier
   * @return the session, to be closed by the caller
   */
  private MailboxAclSession session(EmailConnector connector, String username, String mailboxIdentifier) {
    String connectorId = String.valueOf(connector.getId());
    MailboxAclSession.StoreOpener opener = () -> userEmailSettingService.connect(connectorId, username);
    MailboxAclSession.HttpAuthorizationResolver http = emailCredentialsResolver == null ? null
                                                                                        : () -> emailCredentialsResolver.authorization(connector.getId(),
                                                                                                                                       connector.getAuthProviderName(),
                                                                                                                                       username);
    return new MailboxAclSession(connector, username, mailboxIdentifier, opener, http);
  }

  /**
   * Withdraws the caller's server-side subscription to a share they leave, where the
   * engine has one, and shrugs off a failure: the eXo-side leave has already happened
   * and the ACL was never eXo's to touch. A caller who has since disconnected their
   * own mailbox has no session and nothing to withdraw with.
   *
   * @param granteeUsername the caller
   * @param delegation the row being left
   */
  private void unsubscribeBestEffort(String granteeUsername, EmailDelegation delegation) {
    try {
      UserEmailSetting granteeSetting = connectedSetting(granteeUsername);
      EmailConnector connector = connectorOf(granteeSetting);
      if (!connector.getId().equals(delegation.getConnectorId())) {
        return;
      }
      MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
      try (MailboxAclSession session = session(connector, granteeUsername, mailboxIdentifier(granteeSetting))) {
        engine.unsubscribe(session, delegation.getOwnerMailbox());
      }
    } catch (IllegalAccessException | MailboxAclException e) {
      LOG.debug("Server-side subscription of {} to '{}' could not be withdrawn: {}",
                granteeUsername,
                delegation.getOwnerMailbox(),
                e.getMessage());
    }
  }

  /**
   * Refuses an operation on a server that cannot do it, with the probe's reason.
   *
   * @param capabilities the probe's answer
   * @throws MailboxAclException when unsupported
   */
  private void requireSupported(MailboxAclCapabilities capabilities) {
    if (!capabilities.supported()) {
      throw new MailboxAclException(capabilities.reasonCode() == null ? MailboxAclException.UNSUPPORTED : capabilities.reasonCode(),
                                    "probe: aclAdvertised=" + capabilities.aclAdvertised() + " namespaceAdvertised="
                                        + capabilities.namespaceAdvertised());
    }
  }

  /**
   * A row, as its grantee.
   *
   * @param granteeUsername the caller
   * @param id the row id
   * @return the row
   * @throws ObjectNotFoundException when no such row belongs to the caller as grantee
   */
  private EmailDelegation asGrantee(String granteeUsername, long id) throws ObjectNotFoundException {
    EmailDelegation delegation = emailDelegationStorage.getAsGrantee(granteeUsername, id);
    if (delegation == null) {
      throw new ObjectNotFoundException(NOT_FOUND_MESSAGE);
    }
    return delegation;
  }

  /**
   * A row, as its owner.
   *
   * @param ownerUsername the caller
   * @param id the row id
   * @return the row
   * @throws ObjectNotFoundException when no such row belongs to the caller as owner
   */
  private EmailDelegation asOwner(String ownerUsername, long id) throws ObjectNotFoundException {
    EmailDelegation delegation = emailDelegationStorage.getAsOwner(ownerUsername, id);
    if (delegation == null) {
      throw new ObjectNotFoundException(NOT_FOUND_MESSAGE);
    }
    return delegation;
  }

  /**
   * Makes a share's discovery due at the grantee's next pass, best-effort (EXO-90548
   * review): the owner's change has landed on the server and in the row by then, and
   * clearing a throttle's stamp must not turn it into an error -- at worst the grantee
   * waits for the quarter-hour.
   *
   * @param delegationId the share
   */
  private void markDiscoveryDue(long delegationId) {
    try {
      emailFolderStorage.markDiscoveryDue(delegationId);
    } catch (RuntimeException e) {
      LOG.warn("The discovery of shared mailbox {} could not be made due; its folders follow within the quarter-hour", delegationId, e);
    }
  }

  /**
   * Publishes a transition for the listeners (notifications, analytics) to react to.
   *
   * @param type the transition
   * @param actor whose action it was
   * @param delegation the row
   */
  private void publish(EmailDelegationEvent.Type type, String actor, EmailDelegation delegation) {
    if (eventPublisher != null) {
      eventPublisher.publishEvent(new EmailDelegationEvent(type, actor, delegation));
    }
  }
}
