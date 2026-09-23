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
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.event.EmailDelegationEvent;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.DelegationGrantee;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.GrantedDelegations;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailFolderView;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SharedMailbox;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngine;
import org.exoplatform.emailConnector.service.acl.MailboxAclEngineRegistry;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.storage.EmailDelegationStorage;
import org.exoplatform.emailConnector.storage.EmailFolderStorage;
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
 * which this service reads and requires {@code a} in -- eXo never grants a right the
 * owner does not hold. The row records what the engine says it wrote, in letters and
 * in the server's own words ({@code NATIVE_RIGHTS}).</li>
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
 * on every rights change itself; any owner-facing notification this add-on adds must
 * be gated on {@code capabilities.serverNotifiesOwner()} being false. What is still
 * open is named where it matters, in the engine.
 */
@Service
public class EmailDelegationService {

  private static final Log        LOG                        = ExoLogger.getLogger(EmailDelegationService.class);

  /** How many shared mailboxes one grantee may have accepted at once. */
  public static final String      MAX_PER_USER_PROPERTY      = "exo.email.delegation.maxPerUser";

  /** The default of {@link #MAX_PER_USER_PROPERTY}. */
  public static final int         DEFAULT_MAX_PER_USER       = 5;

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

  public static final String      NOT_PENDING_MESSAGE        = "emailConnector.delegation.notPending";

  public static final String      NOT_ACCEPTED_MESSAGE       = "emailConnector.delegation.notAccepted";

  public static final String      TOO_MANY_MESSAGE           = "emailConnector.delegation.tooMany";

  public static final String      NOT_FOUND_MESSAGE          = "emailConnector.delegation.notFound";

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
      return new GrantedDelegations(capabilities, ownerMailbox, merge(ownerUsername, ownerMailbox, connector, acl, rows));
    }
  }

  /**
   * Shares the caller's mailbox with another eXo user: writes the ACL on the caller's
   * own INBOX, on the caller's own session, then records the invitation.
   * <p>
   * The grant happens HERE and not at accept -- see the class comment. The order is:
   * the grantee resolved from their own connected setting on the same preset; the
   * server probed; the owner's own MYRIGHTS read and {@code a} required; the engine
   * handed the preset and the owner's rights, expanding the one and capping by the
   * other in its server's own vocabulary (letters on IMAP, a verb on BlueMind -- plan,
   * section 3.4); then the row ({@code PENDING/EXO}) recording what was written,
   * reusing a declined, revoked, gone or available row of the same key so the unique
   * key holds and the history stays on one row.
   * <p>
   * The grant is on the mailbox as a whole -- INBOX on a per-folder server -- on every
   * engine in this phase (plan, section 3.4: BlueMind's {@code _acls} is per mailbox,
   * so per-folder rights are a later phase, on per-folder engines only).
   *
   * @param ownerUsername the caller
   * @param granteeUsername the eXo user to share with
   * @param preset READER or EDITOR
   * @return the row as created
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws IllegalArgumentException with a message code when the grantee is the
   *           caller, unknown, not connected on the same preset, the preset is not
   *           grantable, or the share already stands
   * @throws MailboxAclException when the server does not support ACLs, the owner
   *           cannot administer their INBOX, nothing is left to grant, or SETACL is
   *           refused
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
    try (MailboxAclSession session = session(connector, ownerUsername, ownerMailbox)) {
      requireSupported(engine.probe(session));
      MailboxRights ownerRights = engine.myRights(session, OWNER_INBOX);
      if (!ownerRights.canAdminister()) {
        // Verified on BlueMind (the owner holds lrswipkxtea) and consistent with
        // Stalwart accepting the owner's SETACL; still read per session, never assumed.
        throw new MailboxAclException(MailboxAclException.OWNER_CANNOT_ADMINISTER, "MYRIGHTS INBOX = " + ownerRights.letters());
      }
      written = engine.grant(session, OWNER_INBOX, granteeIdentifier, preset, ownerRights);
    }
    MailboxRights granted = written.rights() == null ? MailboxRights.NONE : written.rights();

    Date now = new Date();
    EmailDelegation delegation = existing == null ? new EmailDelegation() : existing;
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
    LOG.info("Mailbox delegation granted: actor={} ownerMailbox={} grantee={} identifier={} rights={}",
             ownerUsername,
             ownerMailbox,
             granteeUsername,
             granteeIdentifier,
             granted.letters());
    publish(EmailDelegationEvent.Type.INVITED, ownerUsername, delegation);
    return delegation;
  }

  /**
   * Removes a grantee's access: DELETEACL on the caller's own INBOX, on the caller's
   * own session, for the identifier the grant was written to (kept on the row, so this
   * works after the grantee disconnected their own mailbox from eXo). The row goes
   * {@code REVOKED} and the grantee's registered folders of this mailbox are dropped.
   * <p>
   * TODO (sync branch): the mirrored {@code EMAIL_BOX} rows of those folders are purged
   * by the delegated sync when it lands; in this phase no such rows exist, since
   * delegated folders are registered with the sync off.
   *
   * @param ownerUsername the caller
   * @param id the delegation id
   * @throws ObjectNotFoundException when no such row belongs to the caller as owner
   * @throws IllegalAccessException when the caller has no connected mailbox
   * @throws MailboxAclException when the server refuses DELETEACL
   */
  public void revoke(String ownerUsername, long id) throws ObjectNotFoundException, IllegalAccessException {
    EmailDelegation delegation = asOwner(ownerUsername, id);
    UserEmailSetting ownerSetting = connectedSetting(ownerUsername);
    EmailConnector connector = connectorOf(ownerSetting);
    String identifier = StringUtils.isNotBlank(delegation.getGranteeMailbox()) ? delegation.getGranteeMailbox()
                                                                                : resolveGranteeIdentifierOrNull(delegation.getGranteeId(),
                                                                                                                 connector.getId());
    if (identifier != null && delegation.getStatus() != DelegationStatus.REVOKED) {
      MailboxAclEngine engine = aclEngineRegistry.engineFor(connector);
      try (MailboxAclSession session = session(connector, ownerUsername, mailboxIdentifier(ownerSetting))) {
        requireSupported(engine.probe(session));
        engine.revoke(session, OWNER_INBOX, identifier);
      }
    }
    delegation.setStatus(DelegationStatus.REVOKED);
    delegation.setRevokedDate(new Date());
    delegation = emailDelegationStorage.update(delegation);
    emailFolderStorage.deleteDelegatedFolders(delegation.getGranteeId(), delegation.getId());
    LOG.info("Mailbox delegation revoked: actor={} ownerMailbox={} grantee={} identifier={}",
             ownerUsername,
             delegation.getOwnerMailbox(),
             delegation.getGranteeId(),
             identifier);
    publish(EmailDelegationEvent.Type.REVOKED, ownerUsername, delegation);
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
   * TODO (sync branch): the folder is registered with {@code SYNC_ENABLED} off. The
   * delegated sync -- window cap, activity gate, lazy attachments, no new-mail
   * broadcast -- is not in this delivery, and the custom-folder rotation must not pick
   * the folder up in its place (its queries exclude delegated rows). That branch turns
   * the opt-in on at accept.
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
    Date now = new Date();
    delegation.setStatus(DelegationStatus.ACCEPTED);
    delegation.setRemoteRoot(shared.remoteRoot());
    delegation.setRights(rights.letters());
    if (StringUtils.isBlank(delegation.getNativeRights())) {
      // MYRIGHTS answers letters on every engine; the server's own words, where they
      // differ, were recorded by the owner's side and are kept.
      delegation.setNativeRights(rights.letters());
    }
    delegation.setLastRightsCheckDate(now);
    delegation.setRespondedDate(now);
    if (delegation.getOrigin() == DelegationOrigin.SERVER || delegation.getPreset() == null) {
      delegation.setPreset(engine.presetOf(rights));
    }
    delegation = emailDelegationStorage.update(delegation);
    registerDelegatedInbox(granteeUsername, delegation, shared);
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
    EmailDelegation updated = emailDelegationStorage.update(delegation);
    emailFolderStorage.deleteDelegatedFolders(granteeUsername, updated.getId());
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
    EmailDelegation delegation = asGrantee(granteeUsername, id);
    // The two toggles alone (#432-2): a whole-row write from this read would put back a
    // status, a revoke date or rights the owner changed since -- a revoke made while
    // the grantee flipped their badge would come undone.
    return emailDelegationStorage.updatePreferences(granteeUsername,
                                                    id,
                                                    badgeIncluded != null ? badgeIncluded : delegation.isBadgeIncluded(),
                                                    notifyNewMail != null ? notifyNewMail : delegation.isNotifyNewMail());
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
      } else if (row != null && (!ace.rights().letters().equals(row.getRights())
                                 || !StringUtils.equals(ace.nativeRights(), row.getNativeRights()))) {
        row.setRights(ace.rights().letters());
        row.setNativeRights(ace.nativeRights());
        row.setLastRightsCheckDate(new Date());
        row = emailDelegationStorage.update(row);
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
        // The server no longer carries the entry: an administrator, or the server's
        // own interface, removed it. The server is the truth.
        row = markRevoked(row, DelegationStatus.REVOKED);
      }
      grantees.add(new DelegationGrantee(row.getGranteeMailbox(),
                                         row.getGranteeId(),
                                         row,
                                         row.getPreset(),
                                         row.getRights(),
                                         row.getNativeRights(),
                                         row.getMailboxRights().affordances()));
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
                                         row.getMailboxRights().affordances()));
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
        if (rowFor(rows, connector.getId(), mailbox) != null) {
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
    EmailFolder existing = emailFolderStorage.getFolderByRemoteName(granteeUsername, shared.inboxName());
    if (existing != null) {
      if (!delegation.getId().equals(existing.getDelegationId())) {
        emailFolderStorage.adoptAsDelegated(granteeUsername, existing.getId(), delegation.getId(), MailFolderView.TYPE_DELEGATED_INBOX);
      }
      return;
    }
    Date now = new Date();
    EmailFolder folder = new EmailFolder();
    folder.setUserId(granteeUsername);
    folder.setRemoteName(shared.inboxName());
    folder.setDisplayName(MailFolder.INBOX);
    folder.setDelimiter(shared.delimiter());
    folder.setType(MailFolderView.TYPE_DELEGATED_INBOX);
    folder.setDelegationId(delegation.getId());
    folder.setDiscoveredDate(now);
    folder.setLastSeenDate(now);
    emailFolderStorage.createFolder(folder);
  }

  /**
   * Records that the server no longer grants a row's access.
   *
   * @param delegation the row
   * @param status REVOKED or GONE
   * @return the row as it now stands
   */
  private EmailDelegation markRevoked(EmailDelegation delegation, DelegationStatus status) {
    delegation.setStatus(status);
    delegation.setRevokedDate(new Date());
    EmailDelegation updated = emailDelegationStorage.update(delegation);
    emailFolderStorage.deleteDelegatedFolders(updated.getGranteeId(), updated.getId());
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
