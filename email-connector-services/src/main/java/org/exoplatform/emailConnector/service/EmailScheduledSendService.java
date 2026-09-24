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

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.emailConnector.exception.ScheduledSendConflictException;
import org.exoplatform.emailConnector.exception.ScheduledSendFailure;
import org.exoplatform.emailConnector.model.DraftMailbox;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailOutgoingAttachment;
import org.exoplatform.emailConnector.model.EmailScheduledSend;
import org.exoplatform.emailConnector.model.ScheduledEmail;
import org.exoplatform.emailConnector.model.ScheduledSendError;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.notification.plugin.ScheduledEmailFailedNotificationPlugin;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailScheduledSendStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * Scheduled send (EXO-90434): a draft sent at a date, as its owner, by whichever node
 * of the cluster gets to it first -- and by one only.
 * <p>
 * <b>Storage.</b> The mail stays its draft row; a row of the schedule table beside it
 * carries the date and the state, and its existence locks the draft
 * ({@code EmailBoxService} refuses every edit and interactive send of it). The
 * scheduling itself -- saving the text on screen, removing the copy in the server's
 * Drafts folder so no other client can send it -- is
 * {@code EmailBoxService.scheduleDraft}; the send is {@code EmailBoxService.sendStoredDraft}.
 * This class holds every rule around them.
 * <p>
 * <b>At most once.</b> Every transition is a conditional statement of
 * {@link EmailScheduledSendStorage}, so of the dispatchers of every node and the owner's
 * own "send now", exactly one claims a mail (SCHEDULED to SENDING). The claim is taken
 * before the SMTP session opens and no database transaction is held across it. From
 * SENDING a mail goes to SENT (accepted), FAILED (refused before anything was
 * accepted), back to SCHEDULED ONLY when the connection could not even be opened, or to
 * UNCERTAIN (anything else, or a node that stopped holding the claim) -- and an
 * UNCERTAIN mail is never sent again without its owner. Its one automatic step is a
 * search of the Sent folder for the Message-ID the draft was pinned with: found, it is
 * cleaned up as sent; not found, it stays and its owner is notified.
 * <p>
 * <b>Clock.</b> Every instant compared or written comes from {@link #now()}: the
 * database clock, so nodes with drifting clocks agree on what is due, truncated to the
 * second, so a claim instant survives a TIMESTAMP column that keeps no milliseconds and
 * can serve as the run's identity. The database clock is only trusted when it is close
 * to this JVM's (see {@link #now()}).
 * <p>
 * <b>Bounded.</b> A tick claims at most the free slots of a small pool
 * ({@value #THREADS_PROPERTY}, default {@value #DEFAULT_THREADS}); every other bound is
 * a JVM property read at each use, so changing one needs no restart.
 */
@Service
public class EmailScheduledSendService {

  /** How many mails a user may have scheduled at once. */
  public static final String         MAX_PER_USER_PROPERTY        = "email.connector.scheduledSend.maxPerUser";

  /** How far ahead a mail may be scheduled, in days. */
  public static final String         MAX_HORIZON_DAYS_PROPERTY    = "email.connector.scheduledSend.maxHorizonDays";

  /** How soon a mail may be scheduled, in seconds from now. */
  public static final String         MIN_DELAY_SECONDS_PROPERTY   = "email.connector.scheduledSend.minDelaySeconds";

  /** The dispatcher's pool size, read once at boot. */
  public static final String         THREADS_PROPERTY             = "email.connector.scheduledSend.threads";

  /** After how many minutes a claim is taken to have been interrupted. */
  public static final String         STUCK_MINUTES_PROPERTY       = "email.connector.scheduledSend.stuckMinutes";

  /** How many automatic retries a failure to connect earns. */
  public static final String         RETRIES_PROPERTY             = "email.connector.scheduledSend.retries";

  /** How far the database clock may be from this JVM's before it is not trusted, in seconds. */
  public static final String         MAX_CLOCK_SKEW_PROPERTY      = "email.connector.scheduledSend.maxClockSkewSeconds";

  /** The code of an invalid time zone. */
  public static final String         INVALID_TIME_ZONE            = "emailConnector.scheduled.timeZone.invalid";

  /** The code of a date too soon. */
  public static final String         DATE_TOO_SOON                = "emailConnector.scheduled.date.tooSoon";

  /** The code of a date too far. */
  public static final String         DATE_TOO_FAR                 = "emailConnector.scheduled.date.tooFar";

  /** The code of a user already at the limit. */
  public static final String         LIMIT_REACHED                = "emailConnector.scheduled.limitReached";

  /** The code of a mail without recipient. */
  public static final String         RECIPIENTS_MANDATORY         = "emailConnector.scheduled.recipientsMandatory";

  /** The code of an action on a mail whose sending could not be confirmed. */
  public static final String         UNCERTAIN_CONFLICT           = "emailConnector.scheduled.uncertain";

  static final int                   DEFAULT_MAX_PER_USER         = 100;

  static final int                   DEFAULT_MAX_HORIZON_DAYS     = 365;

  static final int                   DEFAULT_MIN_DELAY_SECONDS    = 60;

  static final int                   DEFAULT_THREADS              = 2;

  static final int                   DEFAULT_STUCK_MINUTES        = 30;

  static final int                   DEFAULT_RETRIES              = 3;

  static final int                   DEFAULT_MAX_CLOCK_SKEW       = 300;

  // The waits before each automatic retry after a failure to connect, in minutes; past
  // the last one, the last is repeated.
  static final long[]                RETRY_BACKOFF_MINUTES        = { 1, 5, 15 };

  // When an ambiguous failure's Sent-folder check runs: soon, but not at once, so a
  // provider filing its own copy has had the time to.
  static final long                  CHECK_DELAY_SECONDS          = 60;

  private static final Log           LOG                          = ExoLogger.getLogger(EmailScheduledSendService.class);

  private static final String        THREAD_PREFIX                = "email-scheduled-send-";

  private static final long          SKEW_WARNING_PERIOD_MS       = 3_600_000L;

  @Autowired
  private EmailScheduledSendStorage  emailScheduledSendStorage;

  @Autowired
  private EmailBoxService            emailBoxService;

  @Autowired
  private EmailBoxStorage            emailBoxStorage;

  @Autowired
  private UserEmailSettingService    userEmailSettingService;

  @Autowired
  private IdentityManager            identityManager;

  @Autowired
  private EmailDelegationService     emailDelegationService;

  // The rows this JVM is sending (or checking) right now: excluded from every recovery,
  // so a restart's recovery, or a stale-claim sweep, never takes a live run for a dead one.
  private final Set<Long>            inFlight                     = ConcurrentHashMap.newKeySet();

  private final AtomicInteger        threadNumber                 = new AtomicInteger();

  private final AtomicLong           lastSkewWarning              = new AtomicLong();

  private ThreadPoolExecutor         executor                     = newExecutor(threads());

  // Recovery runs on the first tick, not in a @PostConstruct: a startup thread has no
  // container to write through, while the tick runs @ContainerTransactional.
  private volatile boolean           recovered;

  /**
   * Schedules a draft to be sent at a date: the date is checked against the database
   * clock (not before now plus the minimum delay, not after now plus the horizon), the
   * zone must be a real one, the owner must be under the limit and the draft must have
   * a recipient; then the draft is frozen and its schedule created, see
   * {@code EmailBoxService.scheduleDraft}.
   *
   * @param draft the draft as the composer shows it, carrying its local id
   * @param scheduledDate the chosen instant, epoch milliseconds
   * @param timeZone the zone it was chosen in, for display
   * @param username the owner
   * @return the scheduled mail
   * @throws IllegalAccessException if the owner may not use their mailbox
   * @throws ObjectNotFoundException if the owner has no such draft
   * @throws IllegalArgumentException a message code: invalid zone, date too soon or too
   *           far, limit reached, no recipient, a file that cannot be carried
   * @throws ScheduledSendConflictException when the draft is already scheduled or being
   *           sent
   */
  public ScheduledEmail schedule(Email draft,
                                 long scheduledDate,
                                 String timeZone,
                                 String username) throws IllegalAccessException, ObjectNotFoundException {
    requireValidTimeZone(timeZone);
    Date now = now();
    Date date = requireValidDate(scheduledDate, now);
    if (draft == null || !hasRecipient(draft)) {
      throw new IllegalArgumentException(RECIPIENTS_MANDATORY);
    }
    Email[] savedDraft = new Email[1];
    EmailScheduledSend created = emailBoxService.scheduleDraft(draft, username, saved -> {
      if (emailScheduledSendStorage.countListed(username) >= intProperty(MAX_PER_USER_PROPERTY, DEFAULT_MAX_PER_USER)) {
        throw new IllegalArgumentException(LIMIT_REACHED);
      }
      savedDraft[0] = saved;
      EmailScheduledSend row = new EmailScheduledSend(null,
                                                      saved.getId(),
                                                      username,
                                                      saved.getDraftLocalId(),
                                                      date,
                                                      timeZone,
                                                      ScheduledSendStatus.SCHEDULED,
                                                      date,
                                                      0,
                                                      null,
                                                      null,
                                                      null,
                                                      now,
                                                      now);
      try {
        return emailScheduledSendStorage.create(row);
      } catch (DataIntegrityViolationException e) {
        // The unique EMAIL_ID: another node scheduled the same draft a moment ago.
        throw new ScheduledSendConflictException(ScheduledSendConflictException.LOCKED);
      }
    });
    LOG.info("A draft of user {} is scheduled to be sent at {}", username, date);
    return toScheduledEmail(created, savedDraft[0]);
  }

  /**
   * Gives a scheduled (or failed) mail a new date.
   *
   * @param draftLocalId the draft's handle
   * @param scheduledDate the new instant, epoch milliseconds
   * @param timeZone the zone it was chosen in
   * @param username the owner
   * @return the scheduled mail
   * @throws IllegalAccessException if the owner may not use their mailbox
   * @throws ObjectNotFoundException if the owner has no such scheduled mail
   * @throws IllegalArgumentException a message code: invalid zone, date too soon or too far
   * @throws ScheduledSendConflictException when the mail is being sent, sent, or uncertain
   */
  public ScheduledEmail reschedule(String draftLocalId,
                                   long scheduledDate,
                                   String timeZone,
                                   String username) throws IllegalAccessException, ObjectNotFoundException {
    requireMailbox(username);
    requireValidTimeZone(timeZone);
    Date now = now();
    Date date = requireValidDate(scheduledDate, now);
    if (!emailScheduledSendStorage.reschedule(username, draftLocalId, date, timeZone, now)) {
      rejectAsConflictOrNotFound(username, draftLocalId);
    }
    return toScheduledEmail(emailScheduledSendStorage.get(username, draftLocalId));
  }

  /**
   * Replaces a scheduled mail's content -- subject, body, recipients, files -- and, when
   * a date is given, its date, in one transaction, the mail staying scheduled
   * (EXO-90434: an edit that is not a cancel followed by a new schedule, so the mail is
   * never sent half-edited nor left unscheduled). Only while it is scheduled or failed:
   * a mail being sent, sent, or whose sending could not be confirmed is refused, and
   * nothing is written.
   *
   * @param draftLocalId the draft's handle
   * @param draft the draft as the composer shows it, new files as uploads
   * @param removedAttachmentIds the draft's stored files to take off it, may be null
   * @param scheduledDate the new instant, epoch milliseconds, or null to keep the date
   * @param timeZone the zone it was chosen in, with a new date
   * @param username the owner
   * @return the scheduled mail
   * @throws IllegalAccessException if the owner may not use their mailbox
   * @throws ObjectNotFoundException if the owner has no such scheduled mail
   * @throws IllegalArgumentException a message code: no recipient, invalid zone, date
   *           too soon or too far, a file that cannot be carried or that would go over
   *           the size cap
   * @throws ScheduledSendConflictException when the mail is being sent, sent, or
   *           uncertain
   */
  public ScheduledEmail updateContent(String draftLocalId,
                                      Email draft,
                                      List<Long> removedAttachmentIds,
                                      Long scheduledDate,
                                      String timeZone,
                                      String username) throws IllegalAccessException, ObjectNotFoundException {
    requireMailbox(username);
    if (draft == null || !hasRecipient(draft)) {
      throw new IllegalArgumentException(RECIPIENTS_MANDATORY);
    }
    Date now = now();
    Date date = null;
    if (scheduledDate != null) {
      requireValidTimeZone(timeZone);
      date = requireValidDate(scheduledDate, now);
    }
    Date newDate = date;
    draft.setDraftLocalId(draftLocalId);
    emailBoxService.updateScheduledDraft(draft, removedAttachmentIds, username, () -> {
      boolean taken = newDate == null ? emailScheduledSendStorage.takeForEdit(username, draftLocalId, now)
                                      : emailScheduledSendStorage.reschedule(username, draftLocalId, newDate, timeZone, now);
      if (!taken) {
        rejectAsConflictOrNotFound(username, draftLocalId);
      }
    });
    if (draft.getAttachments() != null) {
      emailBoxService.releaseUploads(draft.getAttachments()
                                          .stream()
                                          .filter(upload -> upload != null && StringUtils.isNotBlank(upload.getUploadId()))
                                          .map(EmailOutgoingAttachment::getUploadId)
                                          .toList());
    }
    LOG.info("The content of a scheduled mail of user {} was updated", username);
    return toScheduledEmail(emailScheduledSendStorage.get(username, draftLocalId));
  }

  /**
   * Cancels a schedule: the mail goes back to Drafts, content kept (the draft stays a
   * local draft; its next save pushes it to the server again).
   *
   * @param draftLocalId the draft's handle
   * @param username the owner
   * @throws IllegalAccessException if the owner may not use their mailbox
   * @throws ObjectNotFoundException if the owner has no such scheduled mail
   * @throws ScheduledSendConflictException when the mail is being sent or sent
   */
  public void cancel(String draftLocalId, String username) throws IllegalAccessException, ObjectNotFoundException {
    requireMailbox(username);
    if (!emailScheduledSendStorage.cancel(username, draftLocalId)) {
      rejectAsConflictOrNotFound(username, draftLocalId);
    }
  }

  /**
   * Sends a scheduled mail now, or retries a failed or uncertain one, on the caller's
   * thread: the same claim as the dispatcher's, so of the two racing one sends and the
   * other is refused.
   *
   * @param draftLocalId the draft's handle
   * @param username the owner
   * @return the mail as it now stands: status SENT when it went out
   * @throws IllegalAccessException if the owner may not use their mailbox
   * @throws ObjectNotFoundException if the owner has no such scheduled mail
   * @throws ScheduledSendConflictException when the mail is being sent or sent
   */
  public ScheduledEmail sendNow(String draftLocalId, String username) throws IllegalAccessException, ObjectNotFoundException {
    requireMailbox(username);
    EmailScheduledSend before = emailScheduledSendStorage.get(username, draftLocalId);
    if (before == null) {
      throw new ObjectNotFoundException(draftLocalId);
    }
    // In flight BEFORE the claim, so a first-tick recovery running in between never
    // takes this live run for one a restart interrupted.
    boolean added = inFlight.add(before.getId());
    Email draft;
    try {
      Date now = now();
      String node = EmailConnectorUtils.getSyncNodeName();
      if (!emailScheduledSendStorage.claimNow(username, draftLocalId, node, now)) {
        rejectAsConflictOrNotFound(username, draftLocalId);
      }
      EmailScheduledSend claimed = emailScheduledSendStorage.get(username, draftLocalId);
      if (claimed == null) {
        throw new ObjectNotFoundException(draftLocalId);
      }
      draft = emailBoxStorage.getDraftByLocalId(username, draftLocalId);
      runClaimed(claimed);
      before = claimed;
    } finally {
      // Only when this call put it there: a dispatcher run of the same row on this node
      // owns its own entry.
      if (added) {
        inFlight.remove(before.getId());
      }
    }
    EmailScheduledSend claimed = before;
    EmailScheduledSend after = emailScheduledSendStorage.get(username, draftLocalId);
    if (after == null) {
      claimed.setStatus(ScheduledSendStatus.SENT);
      claimed.setLastError(null);
      return toScheduledEmail(claimed, draft);
    }
    return toScheduledEmail(after, draft);
  }

  /**
   * A page of the owner's scheduled mails, soonest first, with what each says.
   *
   * @param username the owner
   * @param offset the first row, a multiple of {@code limit}
   * @param limit the page size
   * @return the page
   * @throws IllegalAccessException if the owner may not use their mailbox
   */
  public List<ScheduledEmail> getScheduledEmails(String username, int offset, int limit) throws IllegalAccessException {
    requireMailbox(username);
    List<EmailScheduledSend> rows = emailScheduledSendStorage.getListed(username, Math.max(0, offset), Math.max(1, limit));
    if (rows.isEmpty()) {
      return Collections.emptyList();
    }
    Map<Long, Email> drafts = emailBoxStorage.getListedEmailsByIds(username,
                                                                   rows.stream().map(EmailScheduledSend::getEmailId).toList());
    // One lookup per share for the page, not per row: a user's mails come from one or two.
    Map<Long, DraftMailbox> mailboxes = new HashMap<>();
    List<ScheduledEmail> scheduled = new ArrayList<>(rows.size());
    for (EmailScheduledSend row : rows) {
      Email draft = drafts.get(row.getEmailId());
      ScheduledEmail view = toScheduledEmail(row, draft, false);
      if (draft != null && draft.getSendDelegationId() != null) {
        view.setMailbox(mailboxes.computeIfAbsent(draft.getSendDelegationId(), id -> draftMailbox(username, id)));
      }
      scheduled.add(view);
    }
    return scheduled;
  }

  /**
   * How many mails the owner has scheduled: what the disconnect confirmation warns
   * will be cancelled.
   *
   * @param username the owner
   * @return the count
   */
  public long countScheduledEmails(String username) {
    return emailScheduledSendStorage.countListed(username);
  }

  /**
   * One tick of the dispatcher, on every node: recover once after boot, recover claims
   * older than a send can take, clean up mails sent but not cleaned up, then claim and
   * run as many due mails -- and due Sent-folder checks -- as there are free slots.
   * <p>
   * A lost claim (another node won the row) is the mechanism working, logged at DEBUG.
   * Each row is guarded on its own, {@code RuntimeException} and {@code LinkageError}
   * both: a pass that sends on everyone's behalf cannot let one row end it.
   *
   * @return how many mails were claimed for sending
   */
  public int dispatchDue() {
    Date now = now();
    String node = EmailConnectorUtils.getSyncNodeName();
    if (!recovered) {
      int interrupted = emailScheduledSendStorage.markUncertainOf(node, inFlight(), now);
      if (interrupted > 0) {
        LOG.warn("{} scheduled mail(s) were being sent by node {} when it stopped; they are marked uncertain and will not be"
            + " sent again automatically", interrupted, node);
      }
      recovered = true;
    }
    int stale = emailScheduledSendStorage.markStaleUncertain(staleBefore(now), inFlight(), now);
    if (stale > 0) {
      LOG.warn("{} scheduled mail(s) were claimed for longer than a send can take; they are marked uncertain", stale);
    }
    cleanUpSent(now);
    int dispatched = 0;
    int free = freeSlots();
    if (free <= 0) {
      LOG.debug("The scheduled-send pool is full on node {}; nothing dispatched this tick", node);
      return 0;
    }
    for (Long id : emailScheduledSendStorage.findDueToSend(now, free)) {
      try {
        if (!emailScheduledSendStorage.claim(id, node, now)) {
          LOG.debug("Scheduled mail {} was claimed by another node first", id);
          continue;
        }
        EmailScheduledSend claimed = emailScheduledSendStorage.get(id);
        if (claimed != null && submit(claimed, () -> runClaimed(claimed))) {
          dispatched++;
        }
      } catch (RuntimeException | LinkageError e) {
        LOG.warn("Scheduled mail {} could not be dispatched", id, e);
      }
    }
    for (Long id : emailScheduledSendStorage.findDueToCheck(now, freeSlots())) {
      try {
        if (!emailScheduledSendStorage.claimCheck(id, node, now)) {
          continue;
        }
        EmailScheduledSend claimed = emailScheduledSendStorage.get(id);
        if (claimed != null) {
          submit(claimed, () -> runCheck(claimed));
        }
      } catch (RuntimeException | LinkageError e) {
        LOG.warn("The Sent-folder check of scheduled mail {} could not be dispatched", id, e);
      }
    }
    return dispatched;
  }

  /**
   * One claimed mail: sent as its owner, and the run ended in the state its outcome
   * calls for. {@link ContainerTransactional} because a pool thread has no container
   * bound; no database transaction is held across the SMTP session (each storage write
   * is its own short one).
   *
   * @param claimed the claimed row, carrying the run's node and claim instant
   */
  @ContainerTransactional
  public void runClaimed(EmailScheduledSend claimed) {
    String username = claimed.getUserId();
    if (!isUserActive(username)) {
      endAfterFailure(claimed, new ScheduledSendFailure(ScheduledSendFailure.Kind.PERMANENT, ScheduledSendError.DISCONNECTED, null));
      return;
    }
    try {
      emailBoxService.sendStoredDraft(username, claimed.getDraftLocalId(), () -> {
        if (!emailScheduledSendStorage.markSent(claimed.getId(), claimed.getClaimedBy(), claimed.getClaimedDate(), now())) {
          LOG.warn("Scheduled mail {} of user {} was sent, but its row was no longer this run's", claimed.getId(), username);
        }
      });
      LOG.info("A scheduled mail of user {} was sent", username);
    } catch (ObjectNotFoundException e) {
      // The draft is gone (discarded), and its schedule with it through the cascade:
      // nothing was sent, and there is nothing left to record.
      LOG.info("The draft of scheduled mail {} of user {} is gone; nothing was sent", claimed.getId(), username);
    } catch (ScheduledSendFailure failure) {
      endAfterFailure(claimed, failure);
    } catch (RuntimeException | LinkageError e) {
      // Not classified by the send: nothing may be assumed, the message may be out.
      LOG.warn("The scheduled send {} of user {} failed unexpectedly", claimed.getId(), username, e);
      endAfterFailure(claimed, new ScheduledSendFailure(ScheduledSendFailure.Kind.AMBIGUOUS, ScheduledSendError.UNCONFIRMED, e));
    }
  }

  /**
   * One claimed Sent-folder check of an uncertain mail: found, the mail was sent and is
   * cleaned up as such; not found (or unreadable), it stays uncertain and its owner is
   * told, once. Never a send.
   * <p>
   * The check is claimed by clearing its due instant, so a node that stops during it
   * leaves the mail UNCERTAIN with no check pending and no notification: it is still
   * listed, flagged for attention in the "Scheduled" view, and its owner decides. Not
   * re-armed on purpose: a check re-run cannot tell a lost check from a completed one
   * without another column, and the listing already says what matters.
   *
   * @param claimed the claimed row, carrying the check's node and claim instant
   */
  @ContainerTransactional
  public void runCheck(EmailScheduledSend claimed) {
    String username = claimed.getUserId();
    Email draft = emailBoxStorage.getDraftByLocalId(username, claimed.getDraftLocalId());
    if (draft == null) {
      return;
    }
    boolean found;
    try {
      found = emailBoxService.isInSentFolder(username, draft.getMailHeaderId());
    } catch (RuntimeException e) {
      LOG.info("The Sent folder of user {} could not be searched for scheduled mail {}; it stays uncertain",
               username,
               claimed.getId(),
               e);
      found = false;
    }
    if (found) {
      if (emailScheduledSendStorage.markCheckedSent(claimed.getId(), claimed.getClaimedBy(), claimed.getClaimedDate(), now())) {
        emailBoxService.deleteSentScheduledDraft(username, claimed.getDraftLocalId());
        LOG.info("Scheduled mail {} of user {} was found in the Sent folder: it was sent", claimed.getId(), username);
      }
      return;
    }
    notifyOwner(username, draft.getSubject(), ScheduledSendError.UNCONFIRMED);
  }

  /**
   * Stops the pool with the Spring context. A send interrupted here leaves its claim to
   * this node's recovery at its next boot, or to the stuck timeout: UNCERTAIN, never
   * sent again automatically.
   */
  @PreDestroy
  public void shutdown() {
    executor.shutdownNow();
  }

  /**
   * The instant every comparison and write uses: the database clock, truncated to the
   * whole second.
   * <p>
   * The database clock is what lets every node agree on what is due. It is read through
   * JDBC, though, and a {@code CURRENT_TIMESTAMP} comes back shifted by hours when the
   * database session's time zone is not the one the driver assumes (a MySQL server in
   * UTC read by a JVM in Europe/Paris, with Connector/J's defaults): a mail would then go
   * out hours early or late. So the database clock is used only when it is within
   * {@value #MAX_CLOCK_SKEW_PROPERTY} (default {@value #DEFAULT_MAX_CLOCK_SKEW} s) of
   * this JVM's; beyond, this JVM's is used and a warning, at most hourly, names the
   * misconfiguration. Drifts below the bound -- the ordinary NTP kind -- are exactly
   * what the database clock is for.
   *
   * @return the instant, a whole second
   */
  Date now() {
    long jvmNow = System.currentTimeMillis();
    long chosen = jvmNow;
    try {
      Date dbNow = emailScheduledSendStorage.currentTimestamp();
      if (dbNow != null) {
        long skew = Math.abs(dbNow.getTime() - jvmNow);
        if (skew <= intProperty(MAX_CLOCK_SKEW_PROPERTY, DEFAULT_MAX_CLOCK_SKEW) * 1000L) {
          chosen = dbNow.getTime();
        } else if (jvmNow - lastSkewWarning.get() > SKEW_WARNING_PERIOD_MS) {
          lastSkewWarning.set(jvmNow);
          LOG.warn("The database clock is {} s away from this node's; scheduled mails use this node's clock. A database"
              + " session time zone that differs from the JDBC connection's is the usual cause", skew / 1000);
        }
      }
    } catch (RuntimeException e) {
      LOG.debug("The database clock could not be read; using this node's", e);
    }
    return new Date(chosen - Math.floorMod(chosen, 1000L));
  }

  /**
   * Ends a run after a failure, in the state its kind calls for, under the run's own
   * claim: a failure to connect is retried after a back-off while retries remain, then
   * FAILED; a refusal is FAILED at once; a failure that may have reached the server is
   * UNCERTAIN, its Sent-folder check due shortly. The owner is notified of FAILED here,
   * and of UNCERTAIN only once the check has not found the mail.
   *
   * @param claimed the run's row
   * @param failure the classified failure
   */
  private void endAfterFailure(EmailScheduledSend claimed, ScheduledSendFailure failure) {
    Date now = now();
    ScheduledSendStatus status;
    Date next = null;
    switch (failure.getKind()) {
    case TRANSIENT:
      int attempts = Math.max(1, claimed.getAttempts());
      if (attempts <= intProperty(RETRIES_PROPERTY, DEFAULT_RETRIES)) {
        status = ScheduledSendStatus.SCHEDULED;
        long wait = RETRY_BACKOFF_MINUTES[Math.min(attempts, RETRY_BACKOFF_MINUTES.length) - 1];
        next = new Date(now.getTime() + wait * 60_000L);
      } else {
        status = ScheduledSendStatus.FAILED;
      }
      break;
    case PERMANENT:
      status = ScheduledSendStatus.FAILED;
      break;
    default:
      status = ScheduledSendStatus.UNCERTAIN;
      next = new Date(now.getTime() + CHECK_DELAY_SECONDS * 1000L);
      break;
    }
    boolean ended = emailScheduledSendStorage.endRun(claimed.getId(),
                                                      claimed.getClaimedBy(),
                                                      claimed.getClaimedDate(),
                                                      status,
                                                      failure.getError(),
                                                      next,
                                                      now);
    if (!ended) {
      LOG.warn("Scheduled mail {} of user {} was no longer this run's when its failure ({}) was recorded",
               claimed.getId(),
               claimed.getUserId(),
               failure.getError());
      return;
    }
    LOG.info("Scheduled mail {} of user {}: {} ({}), now {}",
             claimed.getId(),
             claimed.getUserId(),
             failure.getKind(),
             failure.getError(),
             status);
    if (status == ScheduledSendStatus.FAILED) {
      Email draft = emailBoxStorage.getDraftByLocalId(claimed.getUserId(), claimed.getDraftLocalId());
      notifyOwner(claimed.getUserId(), draft == null ? null : draft.getSubject(), failure.getError());
    }
  }

  /**
   * Removes the draft rows of mails recorded SENT whose run did not get to remove them,
   * once they are older than a run can take.
   *
   * @param now the tick's instant
   */
  private void cleanUpSent(Date now) {
    for (EmailScheduledSend sent : emailScheduledSendStorage.findSentBefore(staleBefore(now), 100)) {
      try {
        emailBoxService.deleteSentScheduledDraft(sent.getUserId(), sent.getDraftLocalId());
      } catch (RuntimeException | LinkageError e) {
        LOG.warn("The draft of sent scheduled mail {} could not be removed; next tick tries again", sent.getId(), e);
      }
    }
  }

  /**
   * Hands a claimed row to the pool, recording it as in flight for its whole run. A
   * row the pool refuses was claimed but never run, so nothing was transmitted: its
   * claim is given back (SCHEDULED, due at once), the one SENDING-to-SCHEDULED
   * transition that needs no connection to have failed.
   *
   * @param claimed the claimed row
   * @param run the work
   * @return whether the pool took it
   */
  private boolean submit(EmailScheduledSend claimed, Runnable run) {
    inFlight.add(claimed.getId());
    try {
      executor.execute(() -> {
        try {
          run.run();
        } finally {
          inFlight.remove(claimed.getId());
        }
      });
      return true;
    } catch (RejectedExecutionException e) {
      inFlight.remove(claimed.getId());
      if (claimed.getStatus() == ScheduledSendStatus.SENDING) {
        emailScheduledSendStorage.endRun(claimed.getId(),
                                         claimed.getClaimedBy(),
                                         claimed.getClaimedDate(),
                                         ScheduledSendStatus.SCHEDULED,
                                         null,
                                         claimed.getClaimedDate(),
                                         now());
      }
      LOG.info("The scheduled-send pool is full; mail {} waits for the next tick", claimed.getId());
      return false;
    }
  }

  /**
   * Tells the owner a scheduled mail was not sent, or not confirmed: the subject and the
   * reason code only. Never fails the caller.
   *
   * @param username the owner
   * @param subject the mail's subject
   * @param reason why
   */
  private void notifyOwner(String username, String subject, ScheduledSendError reason) {
    try {
      NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                       .append(ScheduledEmailFailedNotificationPlugin.RECEIVER, username)
                                                       .append(ScheduledEmailFailedNotificationPlugin.SUBJECT,
                                                               StringUtils.defaultString(subject))
                                                       .append(ScheduledEmailFailedNotificationPlugin.REASON, reason.name());
      ctx.getNotificationExecutor()
         .with(ctx.makeCommand(PluginKey.key(NotificationConstants.SCHEDULED_EMAIL_FAILED_NOTIFICATION_PLUGIN)))
         .execute(ctx);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("User {} could not be notified that a scheduled mail was not sent ({})", username, reason, e);
    }
  }

  /**
   * Whether the owner can still send as themselves: an enabled, undeleted account whose
   * mailbox is connected to an active connector.
   *
   * @param username the owner
   * @return true when a send may proceed
   */
  private boolean isUserActive(String username) {
    try {
      Identity identity = identityManager.getOrCreateUserIdentity(username);
      if (identity == null || !identity.isEnable() || identity.isDeleted()) {
        return false;
      }
      UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
      return setting != null && setting.getEmailConnectorId() != null
          && userEmailSettingService.canConnect(Long.parseLong(setting.getEmailConnectorId()), username);
    } catch (RuntimeException e) {
      LOG.warn("Could not tell whether user {} may still send; the scheduled mail is not sent", username, e);
      return false;
    }
  }

  /**
   * Refuses an owner whose mailbox is not usable, as every mailbox action does.
   *
   * @param username the owner
   * @throws IllegalAccessException when the mailbox is not connected or not allowed
   */
  private void requireMailbox(String username) throws IllegalAccessException {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    if (setting == null || setting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(setting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException("User " + username + " may not use their mailbox");
    }
  }

  /**
   * What a transition that landed on no row means: no such scheduled mail (404), or one
   * in a state that forbids it (409, the code naming the state).
   *
   * @param username the owner
   * @param draftLocalId the draft's handle
   * @throws ObjectNotFoundException when the owner has no such scheduled mail
   * @throws ScheduledSendConflictException always otherwise
   */
  private void rejectAsConflictOrNotFound(String username, String draftLocalId) throws ObjectNotFoundException {
    EmailScheduledSend row = emailScheduledSendStorage.get(username, draftLocalId);
    if (row == null) {
      throw new ObjectNotFoundException(draftLocalId);
    }
    throw new ScheduledSendConflictException(row.getStatus() == ScheduledSendStatus.UNCERTAIN ? UNCERTAIN_CONFLICT
                                                                                              : ScheduledSendConflictException.SENDING);
  }

  /**
   * Checks a time zone id.
   *
   * @param timeZone the id
   * @throws IllegalArgumentException {@link #INVALID_TIME_ZONE}
   */
  private void requireValidTimeZone(String timeZone) {
    if (StringUtils.isBlank(timeZone) || timeZone.length() > 64) {
      throw new IllegalArgumentException(INVALID_TIME_ZONE);
    }
    try {
      ZoneId.of(timeZone);
    } catch (DateTimeException e) {
      throw new IllegalArgumentException(INVALID_TIME_ZONE, e);
    }
  }

  /**
   * Checks a chosen instant against the clock: not before now plus the minimum delay,
   * not after now plus the horizon.
   *
   * @param scheduledDate the instant, epoch milliseconds
   * @param now the clock
   * @return the instant
   * @throws IllegalArgumentException {@link #DATE_TOO_SOON} or {@link #DATE_TOO_FAR}
   */
  private Date requireValidDate(long scheduledDate, Date now) {
    long earliest = now.getTime() + intProperty(MIN_DELAY_SECONDS_PROPERTY, DEFAULT_MIN_DELAY_SECONDS) * 1000L;
    long latest = now.getTime() + intProperty(MAX_HORIZON_DAYS_PROPERTY, DEFAULT_MAX_HORIZON_DAYS) * 86_400_000L;
    // A second of slack below: the client's "now plus one minute" was computed a moment
    // before this clock was read.
    if (scheduledDate < earliest - 1000L) {
      throw new IllegalArgumentException(DATE_TOO_SOON);
    }
    if (scheduledDate > latest) {
      throw new IllegalArgumentException(DATE_TOO_FAR);
    }
    return new Date(scheduledDate);
  }

  /**
   * Whether a draft names at least one recipient, To, Cc or Bcc.
   *
   * @param draft the draft
   * @return true when it does
   */
  private static boolean hasRecipient(Email draft) {
    return draft.getTo() != null && draft.getTo().stream().anyMatch(r -> r != null && StringUtils.isNotBlank(r.getAddress()))
        || draft.getCc() != null && draft.getCc().stream().anyMatch(r -> r != null && StringUtils.isNotBlank(r.getAddress()))
        || draft.getBcc() != null && draft.getBcc().stream().anyMatch(r -> r != null && StringUtils.isNotBlank(r.getAddress()));
  }

  /**
   * A scheduled mail as the view lists it, reading its draft.
   *
   * @param row the schedule row
   * @return the view
   */
  private ScheduledEmail toScheduledEmail(EmailScheduledSend row) {
    if (row == null) {
      return null;
    }
    return toScheduledEmail(row, emailBoxStorage.getDraftByLocalId(row.getUserId(), row.getDraftLocalId()));
  }

  /**
   * A scheduled mail as the view lists it: recipients, subject, a one-line snippet and
   * the conversation id from the draft, date, zone, status and error code from the
   * schedule.
   *
   * @param row the schedule row
   * @param draft its draft, may be null
   * @return the view
   */
  private ScheduledEmail toScheduledEmail(EmailScheduledSend row, Email draft) {
    return toScheduledEmail(row, draft, true);
  }

  /**
   * {@link #toScheduledEmail(EmailScheduledSend, Email)}, naming the draft's mailbox or
   * leaving that to the caller, which names it once per share for a whole page.
   *
   * @param row the schedule row
   * @param draft its draft, may be null
   * @param withMailbox whether to name the draft's mailbox here
   * @return the view
   */
  private ScheduledEmail toScheduledEmail(EmailScheduledSend row, Email draft, boolean withMailbox) {
    ScheduledEmail scheduled = new ScheduledEmail();
    scheduled.setDraftLocalId(row.getDraftLocalId());
    scheduled.setScheduledDate(row.getScheduledDate() == null ? 0 : row.getScheduledDate().getTime());
    scheduled.setTimeZone(row.getTimeZone());
    scheduled.setStatus(row.getStatus());
    scheduled.setLastError(row.getLastError());
    if (draft != null) {
      scheduled.setThreadId(draft.getThreadId());
      scheduled.setTo(draft.getTo());
      scheduled.setSubject(draft.getSubject());
      scheduled.setSnippet(snippet(draft.getContent()));
      if (withMailbox) {
        scheduled.setMailbox(draftMailbox(row.getUserId(), draft.getSendDelegationId()));
      }
    }
    return scheduled;
  }

  /**
   * The shared mailbox a scheduled mail was written in, as the view names it
   * (EXO-90595), or null for the owner's own. A share that ended is still named, marked
   * as no longer shared, since that is why the mail will not go. Never fails the list.
   *
   * @param username the mail's owner
   * @param delegationId the share its draft records, may be null
   * @return the mailbox, or null
   */
  private DraftMailbox draftMailbox(String username, Long delegationId) {
    if (delegationId == null) {
      return null;
    }
    try {
      DraftMailbox mailbox = emailDelegationService.draftMailbox(username, delegationId);
      return mailbox != null ? mailbox : new DraftMailbox(delegationId, null, null, false);
    } catch (RuntimeException e) {
      LOG.debug("The mailbox of a scheduled mail of user {} could not be named", username, e);
      return new DraftMailbox(delegationId, null, null, false);
    }
  }

  /**
   * The draft's text as one short line.
   *
   * @param content the draft's content
   * @return the snippet, possibly empty
   */
  private static String snippet(EmailContent content) {
    if (content == null) {
      return "";
    }
    String text = StringUtils.isNotBlank(content.getExcerpt()) ? content.getExcerpt()
                                                              : org.jsoup.Jsoup.parse(StringUtils.defaultString(content.getBody()))
                                                                               .text();
    return StringUtils.abbreviate(StringUtils.normalizeSpace(text), 200);
  }

  /**
   * The instant before which a claim is taken to have been interrupted.
   *
   * @param now the clock
   * @return the threshold
   */
  private Date staleBefore(Date now) {
    return new Date(now.getTime() - intProperty(STUCK_MINUTES_PROPERTY, DEFAULT_STUCK_MINUTES) * 60_000L);
  }

  /**
   * The rows in flight on this node, as a snapshot.
   *
   * @return the ids
   */
  private Collection<Long> inFlight() {
    return List.copyOf(inFlight);
  }

  /**
   * How many rows can be handed to the pool right now without waiting for a thread.
   *
   * @return the free slots, zero or more
   */
  private int freeSlots() {
    int threads = executor.getCorePoolSize();
    return Math.max(0, threads - executor.getActiveCount() - executor.getQueue().size());
  }

  /**
   * A positive integer JVM property, its default when absent or invalid.
   *
   * @param name the property
   * @param defaultValue the default
   * @return the value
   */
  static int intProperty(String name, int defaultValue) {
    try {
      int value = Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)).trim());
      return value > 0 ? value : defaultValue;
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * The pool's size, read once at boot.
   *
   * @return the thread count
   */
  private static int threads() {
    return intProperty(THREADS_PROPERTY, DEFAULT_THREADS);
  }

  /**
   * A bounded pool of daemon threads, its queue as deep as the pool: a tick never claims
   * more than the pool can start.
   *
   * @param threads the pool size
   * @return the executor
   */
  private ThreadPoolExecutor newExecutor(int threads) {
    return new ThreadPoolExecutor(threads, threads, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(threads), runnable -> {
      Thread thread = new Thread(runnable, THREAD_PREFIX + threadNumber.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
  }
}
