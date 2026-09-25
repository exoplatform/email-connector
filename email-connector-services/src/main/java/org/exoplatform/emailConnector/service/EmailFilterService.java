/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.emailConnector.event.NewInboxMailEvent;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.AppliedAction;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FilterAction;
import org.exoplatform.emailConnector.model.FilterApplyReport;
import org.exoplatform.emailConnector.model.FilterPreview;
import org.exoplatform.emailConnector.model.HopRef;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.notification.plugin.EmailFilterNotificationPlugin;
import org.exoplatform.emailConnector.service.filters.EmailFilterMail;
import org.exoplatform.emailConnector.service.filters.FilterConditionEvaluator;
import org.exoplatform.emailConnector.service.filters.FilterConditionEvaluator.Result;
import org.exoplatform.emailConnector.service.filters.FilterRunContext;
import org.exoplatform.emailConnector.storage.EmailFilterStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The rules eXo runs itself on the owner's own inbox, after each sync: their CRUD, the
 * run on new mail, the post-actions, the log and its undo, and the preview and one-off
 * pass over the mail eXo already keeps.
 * <p>
 * <b>The hop.</b> A rule that must also run at delivery ({@link EmailFilter#KIND_HOP}) has
 * a server half, published through {@link EmailServerRuleService#reconcileHops}: a server
 * rule with the same conditions whose one action is the keyword
 * {@value ServerRule#TAG_PREFIX}{@code <id>}, under the reference
 * {@value HopRef#REF_PREFIX}{@code <id>} -- the rule's own id, which the stored
 * {@code SERVER_RULE_REF} and {@code TAG_KEYWORD} hold. Every write that touches a hop
 * reconciles the whole set of the owner's hops, <b>server first</b>: when the server
 * refuses, nothing is stored in eXo. A new hop needs its id before the server write, and
 * on MySQL an id exists only once inserted, so it is inserted switched off -- no keyword
 * anyone listens to -- and switched on only after the server accepted its half, or
 * deleted when it did not.
 * <p>
 * <b>The run.</b> {@link #applyToNewMail} evaluates the owner's rules of the mailbox's scope
 * in their order; a hop rule is triggered by its keyword and still checks its conditions,
 * since anyone who may flag the mailbox's mail -- a delegate with {@code w} -- may set the
 * keyword too. A match is recorded once per rule and mail (the unique key); its
 * post-actions run in the same pass, batched per rule, or wait for the assistant when the
 * rule has one. A rule that files the mail stops the rules after it, and the mail is left
 * out of the new-mail announcement.
 * <p>
 * Every verb acts as the owner, on their own mailbox, through the mailbox's own
 * ACL-checked methods; a request made from a shared mailbox is refused.
 */
@Service
public class EmailFilterService {

  private static final Log            LOG                        = ExoLogger.getLogger(EmailFilterService.class);

  /** The deployment's switch for eXo rules, on by default. */
  public static final String          ENABLED_PROPERTY           = "email.connector.filters.exo.enabled";

  /** The deployment's switch for the assistant action, on by default. */
  public static final String          AGENT_ENABLED_PROPERTY     = "exo.email.filters.agent.enabled";

  /** How many matches of one owner may wait for the assistant. */
  public static final String          MAX_PENDING_PROPERTY       = "exo.email.filters.agent.maxPending";

  /** How many assistant runs one owner's rules may queue in a day. */
  public static final String          DAILY_CAP_PROPERTY         = "exo.email.filters.agent.dailyCap";

  /** How many of the newest mails a one-off pass may queue for the assistant. */
  public static final String          RETROACTIVE_MAX_PROPERTY   = "exo.email.filters.agent.retroactiveMax";

  /** How many days the matches are kept. */
  public static final String          RETENTION_PROPERTY         = "exo.email.filters.log.retentionDays";

  /** The feature is switched off. */
  public static final String          DISABLED                   = "emailConnector.filters.disabled";

  /** No such rule of the caller's. */
  public static final String          NOT_FOUND                  = "emailConnector.filters.notFound";

  /** No such match of the caller's. */
  public static final String          MATCH_NOT_FOUND            = "emailConnector.filters.match.notFound";

  /** The owner has as many rules as allowed. */
  public static final String          TOO_MANY                   = "emailConnector.filters.tooMany";

  /** An invalid kind. */
  public static final String          INVALID_KIND               = "emailConnector.filters.kind.invalid";

  /** A scope other than the owner's own mailbox. */
  public static final String          INVALID_SCOPE              = "emailConnector.filters.scope.invalid";

  /** A category that is not one of the owner's. */
  public static final String          UNKNOWN_CATEGORY           = "emailConnector.filters.category.unknown";

  /** An invalid assistant action. */
  public static final String          INVALID_AGENT              = "emailConnector.filters.agent.invalid";

  /** An order that is not every rule of the owner's, once. */
  public static final String          INVALID_ORDER              = "emailConnector.filters.order.invalid";

  /** A verb about a hop, on a rule that is not one. */
  public static final String          NOT_A_HOP                  = "emailConnector.filters.notHop";

  /** A one-off pass of a rule that is switched off. */
  public static final String          NOT_ENABLED                = "emailConnector.filters.notEnabled";

  /** The mail an undo acts on is not where eXo can see it yet. */
  public static final String          UNDO_NOT_YET               = "emailConnector.filters.undo.notYet";

  /** An action nothing can undo. */
  public static final String          UNDO_UNSUPPORTED           = "emailConnector.filters.undo.unsupported";

  /** An action eXo could not apply, when the mailbox gave no reason of its own. */
  public static final String          ACTION_FAILED              = "emailConnector.filters.action.failed";

  /** Some of a batch's mails were not filed. */
  public static final String          PARTIAL                    = "emailConnector.filters.action.partial";

  /** The rule a match's deferred actions belong to was deleted. */
  public static final String          FILTER_DELETED             = "emailConnector.filters.deleted";

  /** The mail a match's deferred actions act on left the inbox. */
  public static final String          MAIL_GONE                  = "emailConnector.filters.mailGone";

  /** A stored rule the sync cannot read. */
  public static final String          UNREADABLE                 = "emailConnector.filters.unreadable";

  /** The most rules one owner may have. */
  public static final int             MAX_FILTERS                = 50;

  /** The longest assistant instruction accepted. */
  public static final int             MAX_INSTRUCTION_LENGTH     = 2000;

  /** The most lines a rule's log answers. */
  public static final int             MAX_LOG                    = 100;

  /** The most mails a preview shows. */
  public static final int             PREVIEW_SAMPLE             = 10;

  /** How long before a rule became active a mail may have arrived and still be new to it: the clocks' grace. */
  static final long                   RECEIVED_GRACE_MS          = 10 * 60_000L;

  /** The longest assistant answer kept. */
  private static final int            MAX_AGENT_OUTPUT_LENGTH    = 65_536;

  /** The fields only eXo evaluates: never on a rule the server runs too. */
  private static final Set<String>    EXO_ONLY_FIELDS            = Set.of(FilterConditionEvaluator.BODY,
                                                                          FilterConditionEvaluator.SUBJECT_OR_BODY,
                                                                          FilterConditionEvaluator.HAS_ATTACHMENT);

  /** The fields eXo cannot evaluate on a mail it already keeps. */
  private static final List<String>   NOT_PREVIEWABLE            = List.of(ServerRule.HEADER, ServerRule.MESSAGE_SIZE);

  /** The assistant statuses of a queued run, counted by the daily cap. */
  private static final List<String>   QUEUED_STATUSES            = List.of(EmailFilterMatch.AGENT_PENDING,
                                                                           EmailFilterMatch.AGENT_RUNNING,
                                                                           EmailFilterMatch.AGENT_DONE,
                                                                           EmailFilterMatch.AGENT_FAILED);

  private static final Pattern        AGENT_NAME                 = Pattern.compile("[A-Za-z0-9._-]{1,200}");

  @Autowired
  private EmailFilterStorage          emailFilterStorage;

  @Autowired
  private EmailServerRuleService      emailServerRuleService;

  @Autowired
  private EmailBoxService             emailBoxService;

  @Autowired
  private EmailFolderService          emailFolderService;

  @Autowired
  private UserEmailSettingService     userEmailSettingService;

  @Autowired
  private ListenerService             listenerService;

  private Clock                       clock                      = Clock.systemUTC();

  /**
   * Replaces the clock.
   *
   * @param newClock the clock
   */
  void setClock(Clock newClock) {
    this.clock = newClock;
  }

  /**
   * The caller's eXo rules, in the order they run.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the rules
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   */
  public List<EmailFilter> getFilters(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    checkOwnMailbox(username, delegationId);
    return emailFilterStorage.getFilters(username);
  }

  /**
   * Creates one of the caller's eXo rules, last in the order. A rule that also runs at
   * delivery publishes its server half first; when the server refuses, nothing is
   * stored.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param input the rule as the form sent it
   * @param consent true when the caller agreed, in this request, that eXo manages rules
   *          on their mail server; needed once, by the first rule with a server half
   * @param republish true to overwrite eXo's script although it changed outside eXo
   * @return the rule as stored
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws IllegalArgumentException with a message code for an invalid value, a folder
   *           or category that is not the caller's, a missing consent, or too many rules
   * @throws ServerRuleUnavailableException when the server half cannot be written
   * @throws ServerRuleConflictException when another client's script is in the way, or
   *           eXo's script changed outside eXo
   * @throws ServerRuleUnsupportedException when the connector cannot hold the server half
   */
  public EmailFilter createFilter(String username,
                                  Long delegationId,
                                  EmailFilter input,
                                  boolean consent,
                                  boolean republish) throws ObjectNotFoundException,
                                                     IllegalAccessException,
                                                     ServerRuleUnavailableException,
                                                     ServerRuleConflictException,
                                                     ServerRuleUnsupportedException {
    checkOwnMailbox(username, delegationId);
    if (emailFilterStorage.getFilters(username).size() >= MAX_FILTERS) {
      throw new IllegalArgumentException(TOO_MANY);
    }
    EmailFilter filter = validated(username, input);
    filter.setId(null);
    filter.setPosition(emailFilterStorage.nextPosition(username));
    Date now = now();
    filter.setActiveSince(now.getTime());
    if (!EmailFilter.KIND_HOP.equals(filter.getKind())) {
      return emailFilterStorage.save(username, filter, now);
    }
    boolean wanted = filter.isEnabled();
    filter.setEnabled(false);
    EmailFilter provisional = emailFilterStorage.save(username, filter, now);
    try {
      EmailFilter hop = withHopNames(provisional);
      hop.setEnabled(wanted);
      hop.setActiveSince(now.getTime());
      if (wanted) {
        publishHops(username, hop, consent, republish);
      }
      return emailFilterStorage.save(username, hop, now);
    } catch (ObjectNotFoundException | IllegalAccessException | ServerRuleUnavailableException | ServerRuleConflictException
        | ServerRuleUnsupportedException | RuntimeException e) {
      // The server did not take the rule's half: the rule is not created.
      emailFilterStorage.delete(provisional.getId(), username);
      throw e;
    }
  }

  /**
   * Replaces one of the caller's eXo rules; it keeps its place. When the rule has, or
   * had, a server half, the server is written first, and nothing is stored when it
   * refuses.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the rule
   * @param input the rule as the form sent it
   * @param consent as for {@link #createFilter}
   * @param republish as for {@link #createFilter}
   * @return the rule as stored
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           the rule is not the caller's
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws IllegalArgumentException with a message code, as for {@link #createFilter}
   * @throws ServerRuleUnavailableException when the server half cannot be written
   * @throws ServerRuleConflictException when another client's script is in the way, or
   *           eXo's script changed outside eXo
   * @throws ServerRuleUnsupportedException when the connector cannot hold the server half
   */
  public EmailFilter updateFilter(String username,
                                  Long delegationId,
                                  long id,
                                  EmailFilter input,
                                  boolean consent,
                                  boolean republish) throws ObjectNotFoundException,
                                                     IllegalAccessException,
                                                     ServerRuleUnavailableException,
                                                     ServerRuleConflictException,
                                                     ServerRuleUnsupportedException {
    checkOwnMailbox(username, delegationId);
    EmailFilter existing = ownFilter(username, id);
    EmailFilter filter = validated(username, input);
    filter.setId(id);
    filter.setPosition(existing.getPosition());
    filter.setLastError(null);
    // Switched back on, the rule is new to the mail from now; an edit keeps its start.
    boolean reEnabled = filter.isEnabled() && !existing.isEnabled();
    filter.setActiveSince(reEnabled || existing.getActiveSince() == null ? Long.valueOf(clock.millis()) : existing.getActiveSince());
    if (EmailFilter.KIND_HOP.equals(filter.getKind())) {
      filter = withHopNames(filter);
    }
    if (isLiveHop(filter)) {
      publishHops(username, filter, consent, republish);
    } else if (isLiveHop(existing)) {
      removeHop(username, existing, republish);
    }
    return emailFilterStorage.save(username, filter, now());
  }

  /**
   * Deletes one of the caller's eXo rules; its log stays. A rule with a server half
   * removes it from the server first.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the rule
   * @param republish as for {@link #createFilter}
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           the rule is not the caller's
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws ServerRuleUnavailableException when the server half cannot be removed
   * @throws ServerRuleConflictException when eXo's script changed outside eXo
   */
  public void deleteFilter(String username,
                           Long delegationId,
                           long id,
                           boolean republish) throws ObjectNotFoundException,
                                              IllegalAccessException,
                                              ServerRuleUnavailableException,
                                              ServerRuleConflictException {
    checkOwnMailbox(username, delegationId);
    EmailFilter existing = ownFilter(username, id);
    if (isLiveHop(existing)) {
      removeHop(username, existing, republish);
    }
    emailFilterStorage.delete(id, username);
    LOG.info("Mail filter {} deleted by user {}", id, username);
  }

  /**
   * Orders the caller's eXo rules.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param orderedIds every rule of the caller's, once, in the new order
   * @return the rules, in their new order
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws IllegalArgumentException {@value #INVALID_ORDER} for anything but a
   *           permutation of the caller's rules
   */
  public List<EmailFilter> reorder(String username,
                                   Long delegationId,
                                   List<Long> orderedIds) throws ObjectNotFoundException, IllegalAccessException {
    checkOwnMailbox(username, delegationId);
    Set<Long> current = new HashSet<>();
    emailFilterStorage.getFilters(username).forEach(filter -> current.add(filter.getId()));
    if (orderedIds == null || orderedIds.size() != current.size() || !current.equals(new HashSet<>(orderedIds))) {
      throw new IllegalArgumentException(INVALID_ORDER);
    }
    emailFilterStorage.reorder(username, orderedIds, now());
    return emailFilterStorage.getFilters(username);
  }

  /**
   * Publishes the server half of one of the caller's rules again: after it was removed
   * or edited on the server, which eXo never repairs silently.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the rule
   * @param consent as for {@link #createFilter}
   * @param republish true to overwrite eXo's script although it changed outside eXo
   * @return the rule
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           the rule is not the caller's
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws IllegalArgumentException {@value #NOT_A_HOP} for a rule without a server
   *           half, or switched off
   * @throws ServerRuleUnavailableException when the server half cannot be written
   * @throws ServerRuleConflictException when another client's script is in the way, or
   *           eXo's script changed outside eXo
   * @throws ServerRuleUnsupportedException when the connector cannot hold the server half
   */
  public EmailFilter republishHop(String username,
                                  Long delegationId,
                                  long id,
                                  boolean consent,
                                  boolean republish) throws ObjectNotFoundException,
                                                     IllegalAccessException,
                                                     ServerRuleUnavailableException,
                                                     ServerRuleConflictException,
                                                     ServerRuleUnsupportedException {
    checkOwnMailbox(username, delegationId);
    EmailFilter filter = ownFilter(username, id);
    if (!isLiveHop(filter)) {
      throw new IllegalArgumentException(NOT_A_HOP);
    }
    publishHops(username, filter, consent, republish);
    return filter;
  }

  /**
   * What a rule would match among the mail eXo keeps of the caller's inbox, before it is
   * saved. Works for a server rule too ({@link EmailFilter#KIND_SERVER}): eXo evaluates
   * the same vocabulary, and says the count is the server's approximation.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param draft the rule's conditions, kind and match mode; its actions are not read
   * @return the count and a sample
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws IllegalArgumentException with a message code for an invalid condition
   */
  public FilterPreview preview(String username, Long delegationId, EmailFilter draft) throws ObjectNotFoundException,
                                                                                     IllegalAccessException {
    checkOwnMailbox(username, delegationId);
    if (draft == null) {
      throw new IllegalArgumentException(ServerRule.INVALID);
    }
    String kind = StringUtils.defaultIfBlank(draft.getKind(), EmailFilter.KIND_EXO).toUpperCase(Locale.ROOT);
    if (!EmailFilter.KINDS.contains(kind) && !EmailFilter.KIND_SERVER.equals(kind)) {
      throw new IllegalArgumentException(INVALID_KIND);
    }
    List<ServerRule.Condition> conditions = validatedConditions(draft.getConditions(), kind);
    List<Email> window = emailBoxService.getCachedInbox(username);
    int total = 0;
    List<FilterPreview.Row> sample = new ArrayList<>();
    for (Email email : window) {
      if (FilterConditionEvaluator.evaluate(conditions, draft.isMatchAll(), cachedMail(username, email)) == Result.TRUE) {
        total++;
        if (sample.size() < PREVIEW_SAMPLE) {
          sample.add(new FilterPreview.Row(email.getId(),
                                           email.getSubject(),
                                           email.getSender() == null ? null : email.getSender().getAddress(),
                                           email.getReceivedDate() == null ? null : email.getReceivedDate().getTime()));
        }
      }
    }
    List<String> notPreviewable = conditions.stream()
                                            .map(ServerRule.Condition::field)
                                            .filter(NOT_PREVIEWABLE::contains)
                                            .distinct()
                                            .toList();
    return new FilterPreview(total, window.size(), sample, notPreviewable, !EmailFilter.KIND_EXO.equals(kind));
  }

  /**
   * Runs one of the caller's rules once over the mail eXo already keeps of their inbox,
   * as the owner: its post-actions on every match it has not handled yet, logged and
   * undoable; its assistant only when asked, on the newest matches, within the caps.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the rule
   * @param withAgent true to queue the assistant on the newest matches too
   * @return what the pass did
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           the rule is not the caller's
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws IllegalArgumentException {@value #NOT_ENABLED} for a rule switched off, or
   *           {@value #UNREADABLE} for one eXo cannot read
   */
  public FilterApplyReport applyOnce(String username,
                                     Long delegationId,
                                     long id,
                                     boolean withAgent) throws ObjectNotFoundException, IllegalAccessException {
    checkOwnMailbox(username, delegationId);
    EmailFilter filter = ownFilter(username, id);
    if (!filter.isEnabled()) {
      throw new IllegalArgumentException(NOT_ENABLED);
    }
    if (!isRunnable(filter)) {
      throw new IllegalArgumentException(UNREADABLE);
    }
    List<Email> window = emailBoxService.getCachedInbox(username);
    Run run = new Run(username, now(), false, withAgent);
    for (Email email : window) {
      if (StringUtils.isBlank(email.getMailHeaderId()) || email.getMailRemoteId() == null) {
        continue;
      }
      evaluate(run, List.of(filter), cachedMail(username, email));
    }
    execute(run);
    LOG.info("Mail filter {} of user {} applied to the cached inbox: {} matched, {} queued", id, username, run.matched, run.queued.size());
    return new FilterApplyReport(window.size(), run.matched, run.alreadyHandled, run.queued.size(), run.notApplicable);
  }

  /**
   * Runs the owner's rules on the mail a sync of their own inbox just cached. Called by
   * the sync, on its thread, before the mail is announced; never throws.
   *
   * @param username the mailbox's owner
   * @param mails the mails just cached, with what the sync read of them
   * @param context which mailbox they are in; nothing runs on any but the owner's own
   *          inbox
   * @return the UIDs of the mails a rule filed away, to leave out of the announcement
   */
  public Set<Long> applyToNewMail(String username, List<NewInboxMailEvent.InboxMail> mails, FilterRunContext context) {
    if (!isEnabled() || context == null || !context.isOwnInbox() || mails == null || mails.isEmpty()) {
      return Set.of();
    }
    try {
      if (emailFilterStorage.countEnabled(username) == 0) {
        return Set.of();
      }
      List<EmailFilter> filters = runnableFilters(username, context);
      if (filters.isEmpty()) {
        return Set.of();
      }
      Run run = new Run(username, now(), true, true);
      for (NewInboxMailEvent.InboxMail inboxMail : mails) {
        Email email = emailBoxService.getEmailByMailRemoteIdAndUserId(inboxMail.uid(),
                                                                      username,
                                                                      MailFolder.INBOX,
                                                                      true,
                                                                      true,
                                                                      false,
                                                                      false);
        if (email == null || StringUtils.isBlank(email.getMailHeaderId())) {
          continue;
        }
        evaluate(run, filters, new EmailFilterMail(email, null, inboxMail.keywords(), inboxMail.headers(), inboxMail.sizeKb()));
      }
      execute(run);
      prune(username);
      return run.filed;
    } catch (Exception e) {
      LOG.warn("The mail filters of user {} failed on {} new mail(s)", username, mails.size(), e);
      return Set.of();
    }
  }

  /**
   * Runs the post-actions a match kept for after its assistant: called by the assistant's
   * handler once the run is over, whatever its outcome, so a rule's deterministic half is
   * never lost to a failed or capped assistant. Idempotent: a match whose post-actions ran
   * is answered as it is.
   *
   * @param matchId the match
   * @param username the rule's owner, the handler's acting user
   * @return the match after the post-actions
   * @throws ObjectNotFoundException when the match is not this user's
   * @throws IllegalAccessException when the owner may no longer read their mailbox
   */
  public EmailFilterMatch applyPostActions(long matchId, String username) throws ObjectNotFoundException, IllegalAccessException {
    EmailFilterMatch match = ownMatch(username, matchId);
    if (!EmailFilterMatch.POST_PENDING_AGENT.equals(match.getPostActionsState())) {
      return named(username, match);
    }
    Optional<EmailFilter> filter = emailFilterStorage.getFilter(match.getFilterId(), username);
    Email email = filter.isPresent() ? emailBoxService.getOwnEmailByMailHeaderId(username, match.getMailHeaderId(), MailFolder.INBOX)
                                     : null;
    if (filter.isEmpty() || email == null) {
      match.setPostActionsState(EmailFilterMatch.POST_DONE);
      match.setLastError(filter.isEmpty() ? FILTER_DELETED : MAIL_GONE);
      return named(username, emailFilterStorage.updateMatch(match, username));
    }
    Run run = new Run(username, now(), false, false);
    run.immediate.computeIfAbsent(filter.get(), key -> new ArrayList<>()).add(new Entry(match, email));
    execute(run);
    return named(username, emailFilterStorage.getMatch(matchId, username).orElse(match));
  }

  /**
   * Records what the assistant made of a match: its handler's write, as the owner.
   *
   * @param matchId the match
   * @param username the rule's owner
   * @param status {@code RUNNING}, {@code DONE} or {@code FAILED}
   * @param conversationId the assistant's conversation, to open it in the chat
   * @param output the assistant's answer as the handler applied it
   * @param error the reason of a failure
   * @return the match
   * @throws ObjectNotFoundException when the match is not this user's
   * @throws IllegalArgumentException {@value ServerRule#INVALID} for another status
   */
  public EmailFilterMatch saveAgentOutcome(long matchId,
                                           String username,
                                           String status,
                                           String conversationId,
                                           String output,
                                           String error) throws ObjectNotFoundException {
    if (!List.of(EmailFilterMatch.AGENT_RUNNING, EmailFilterMatch.AGENT_DONE, EmailFilterMatch.AGENT_FAILED).contains(status)) {
      throw new IllegalArgumentException(ServerRule.INVALID);
    }
    EmailFilterMatch match = ownMatch(username, matchId);
    match.setAgentStatus(status);
    match.setAgentDate(clock.millis());
    if (conversationId != null) {
      match.setAgentConversationId(StringUtils.left(conversationId, 64));
    }
    if (!EmailFilterMatch.AGENT_RUNNING.equals(status)) {
      match.setAgentAttempts(match.getAgentAttempts() + 1);
      match.setAgentOutput(StringUtils.left(output, MAX_AGENT_OUTPUT_LENGTH));
      match.setLastError(error);
    }
    return named(username, emailFilterStorage.updateMatch(match, username));
  }

  /**
   * The owner's matches waiting for the assistant, oldest first: what its handler takes
   * up in one run.
   *
   * @param username the owner
   * @param limit how many, at most {@value #MAX_LOG}
   * @return the matches
   */
  public List<EmailFilterMatch> listPendingAgentMatches(String username, int limit) {
    return emailFilterStorage.getMatchesByAgentStatus(username,
                                                      EmailFilterMatch.AGENT_PENDING,
                                                      Math.max(1, Math.min(limit, MAX_LOG)));
  }

  /**
   * The rule of a match, for the assistant's handler: which assistant, which
   * instruction, which outputs.
   *
   * @param username the owner
   * @param filterId the rule
   * @return the rule, or empty when it was deleted
   */
  public Optional<EmailFilter> getFilterOfMatch(String username, long filterId) {
    return emailFilterStorage.getFilter(filterId, username);
  }

  /**
   * Undoes what a match did to its mail: one action, or every action that can be undone
   * -- a move put back, a category taken off, a read mark or a star withdrawn, a mail
   * brought back from Junk or Trash. Nothing is undone twice.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param matchId the match
   * @param actionType the action to undo, null for every one
   * @return the match after the undo
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           the match is not the caller's
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws IllegalArgumentException {@value #UNDO_NOT_YET} when the mail is not yet
   *           where eXo can see it, {@value #UNDO_UNSUPPORTED} for an action nothing
   *           undoes, or the mailbox's own code
   */
  public EmailFilterMatch undo(String username,
                               Long delegationId,
                               long matchId,
                               String actionType) throws ObjectNotFoundException, IllegalAccessException {
    checkOwnMailbox(username, delegationId);
    EmailFilterMatch match = ownMatch(username, matchId);
    List<AppliedAction> actions = new ArrayList<>(match.getActions() == null ? List.of() : match.getActions());
    String filedInto = actions.stream()
                              .filter(action -> action.ok() && !action.undone() && FilterAction.FILING.contains(action.type()))
                              .map(AppliedAction::folderKey)
                              .findFirst()
                              .orElse(null);
    // The flags and the category first, while the mail is still where the filing put it;
    // the filing last.
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < actions.size(); i++) {
      if (!FilterAction.FILING.contains(actions.get(i).type())) {
        order.add(i);
      }
    }
    for (int i = 0; i < actions.size(); i++) {
      if (FilterAction.FILING.contains(actions.get(i).type())) {
        order.add(i);
      }
    }
    boolean undoneAny = false;
    RuntimeException firstFailure = null;
    for (int index : order) {
      AppliedAction action = actions.get(index);
      if (!action.ok() || action.undone() || actionType != null && !actionType.equals(action.type())) {
        continue;
      }
      try {
        if (undoOne(username, match, action, filedInto)) {
          actions.set(index, action.asUndone());
          undoneAny = true;
        } else if (actionType != null) {
          throw new IllegalArgumentException(UNDO_UNSUPPORTED);
        }
      } catch (RuntimeException e) {
        // What was undone before stays recorded; the rest is reported.
        if (firstFailure == null) {
          firstFailure = e;
        }
      }
    }
    if (undoneAny) {
      match.setActions(actions);
      match = emailFilterStorage.updateMatch(match, username);
    }
    if (firstFailure != null) {
      throw firstFailure;
    }
    if (!undoneAny) {
      return named(username, match);
    }
    LOG.info("Mail filter match {} undone by user {} ({})", matchId, username, actionType == null ? "all" : actionType);
    return named(username, match);
  }

  /**
   * Queues the assistant again on a match whose run is over: "Run again", the one way an
   * assistant runs twice on one mail. Its post-actions, when they ran, do not run again.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param matchId the match
   * @return the match, queued
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           the match is not the caller's
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   * @throws IllegalArgumentException {@value #INVALID_AGENT} for a match without an
   *           assistant, or whose assistant is still due
   */
  public EmailFilterMatch retry(String username, Long delegationId, long matchId) throws ObjectNotFoundException,
                                                                                  IllegalAccessException {
    checkOwnMailbox(username, delegationId);
    EmailFilterMatch match = ownMatch(username, matchId);
    if (StringUtils.isBlank(match.getAgentNameId()) || !EmailFilterMatch.AGENT_TERMINAL.contains(match.getAgentStatus())) {
      throw new IllegalArgumentException(INVALID_AGENT);
    }
    match.setAgentStatus(EmailFilterMatch.AGENT_PENDING);
    match.setAgentAttempts(0);
    match.setLastError(null);
    EmailFilterMatch queued = emailFilterStorage.updateMatch(match, username);
    requestAgent(username, List.of(queued.getId()));
    return named(username, queued);
  }

  /**
   * What the caller's rules did to one of their mails: its Automations panel.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param emailId the cached mail's id
   * @return the matches, newest first
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           no such mail
   * @throws IllegalAccessException when the request comes from someone else's mailbox, the
   *           caller may not use their connector, or the mail is not theirs
   */
  public List<EmailFilterMatch> getMatchesOfMail(String username, Long delegationId, long emailId) throws ObjectNotFoundException,
                                                                                                   IllegalAccessException {
    checkOwnMailbox(username, delegationId);
    Email email = emailBoxService.getOwnedEmailById(emailId, username);
    if (email == null || StringUtils.isBlank(email.getMailHeaderId())) {
      throw new ObjectNotFoundException(MATCH_NOT_FOUND);
    }
    return named(username, emailFilterStorage.getMatchesOfMail(username, email.getMailHeaderId()));
  }

  /**
   * The newest matches of one of the caller's rules.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the rule
   * @param limit how many, at most {@value #MAX_LOG}
   * @return the matches, newest first
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           the rule is not the caller's
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   */
  public List<EmailFilterMatch> getLog(String username, Long delegationId, long id, int limit) throws ObjectNotFoundException,
                                                                                               IllegalAccessException {
    checkOwnMailbox(username, delegationId);
    EmailFilter filter = ownFilter(username, id);
    List<EmailFilterMatch> log = emailFilterStorage.getLog(username, id, Math.max(1, Math.min(limit, MAX_LOG)));
    log.forEach(match -> match.setFilterName(filter.getName()));
    return log;
  }

  /**
   * Tells the owner that a rule matched their mail: one notification per rule per pass,
   * or one line of the assistant's for one mail.
   *
   * @param username the owner
   * @param filterName the rule's name
   * @param count how many mails it matched
   * @param line the assistant's one line, or null
   */
  public void notifyOwner(String username, String filterName, int count, String line) {
    try {
      NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                       .append(EmailFilterNotificationPlugin.RECEIVER, username)
                                                       .append(EmailFilterNotificationPlugin.FILTER_NAME, filterName)
                                                       .append(EmailFilterNotificationPlugin.COUNT, String.valueOf(count))
                                                       .append(EmailFilterNotificationPlugin.LINE, StringUtils.defaultString(line));
      ctx.getNotificationExecutor()
         .with(ctx.makeCommand(PluginKey.key(NotificationConstants.EMAIL_FILTER_NOTIFICATION_PLUGIN)))
         .execute(ctx);
    } catch (RuntimeException | LinkageError e) {
      LOG.warn("User {} could not be notified about the mail filter '{}'", username, filterName, e);
    }
  }

  /**
   * Evaluates the rules on one mail, in order, and records each match.
   *
   * @param run the pass
   * @param filters the rules, runnable, in order
   * @param mail the mail
   */
  private void evaluate(Run run, List<EmailFilter> filters, EmailFilterMail mail) {
    Email email = mail.email();
    for (EmailFilter filter : filters) {
      if (run.sync && receivedBefore(email, filter)) {
        // The sync also caches mail that is not new: the whole window after a reset or a
        // reconnection. A rule acts at sync on mail that arrived since it became active;
        // the one-off pass is how it reaches older mail.
        continue;
      }
      if (run.sync && EmailFilter.KIND_HOP.equals(filter.getKind())
          && !mail.keywords().contains(filter.getTagKeyword().toLowerCase(Locale.ROOT))) {
        // A hop is triggered by the keyword its server half sets: on new mail, nothing
        // else runs it.
        continue;
      }
      Result result = FilterConditionEvaluator.evaluate(filter.getConditions(), filter.isMatchAll(), mail);
      if (result != Result.TRUE) {
        if (result == Result.UNKNOWN) {
          run.notApplicable++;
        }
        if (run.sync && EmailFilter.KIND_HOP.equals(filter.getKind())) {
          // The keyword is there and the conditions do not hold: someone else set it.
          LOG.info("Mail filter {} of user {}: keyword {} found on a mail its conditions do not match; ignored",
                   filter.getId(),
                   run.username,
                   filter.getTagKeyword());
        }
        continue;
      }
      Optional<EmailFilterMatch> created = emailFilterStorage.createMatch(newMatch(filter, email, run), run.username);
      if (created.isEmpty()) {
        run.alreadyHandled++;
      } else {
        run.add(filter, created.get(), email);
      }
      if (filter.isStopProcessing() || filter.files()) {
        break;
      }
    }
  }

  /**
   * Whether a mail arrived before a rule became active -- created, or switched back on --
   * allowing for the clocks of eXo and the mail server: such a mail is not new to the
   * rule. A rename, an edit or a reorder does not move that start.
   *
   * @param email the mail
   * @param filter the rule
   * @return true when the mail predates the rule's start by more than the grace
   */
  static boolean receivedBefore(Email email, EmailFilter filter) {
    Long saved = filter.getActiveSince() != null ? filter.getActiveSince() : filter.getCreatedDate();
    return saved != null && email.getReceivedDate() != null && email.getReceivedDate().getTime() < saved - RECEIVED_GRACE_MS;
  }

  /**
   * A new match of a rule on a mail, before its post-actions.
   *
   * @param filter the rule
   * @param email the mail
   * @param run the pass
   * @return the match
   */
  private EmailFilterMatch newMatch(EmailFilter filter, Email email, Run run) {
    EmailFilterMatch match = new EmailFilterMatch();
    match.setFilterId(filter.getId());
    match.setMailHeaderId(email.getMailHeaderId());
    match.setMailRemoteId(email.getMailRemoteId());
    match.setFolder(MailFolder.INBOX);
    match.setSubject(email.getSubject());
    match.setMatchedDate(run.now.getTime());
    match.setActions(List.of());
    match.setPostActionsState(EmailFilterMatch.POST_DONE);
    match.setAgentStatus(EmailFilterMatch.AGENT_NONE);
    match.setAgentNameId(filter.getAgentNameId());
    return match;
  }

  /**
   * Applies what a pass decided: the post-actions of its immediate matches, batched per
   * rule, the counters, one notification per rule, the assistant's request.
   *
   * @param run the pass
   */
  private void execute(Run run) {
    for (Map.Entry<EmailFilter, List<Entry>> group : run.immediate.entrySet()) {
      EmailFilter filter = group.getKey();
      List<Entry> entries = group.getValue();
      Map<Long, List<AppliedAction>> applied = new HashMap<>();
      entries.forEach(entry -> applied.put(entry.email().getMailRemoteId(), new ArrayList<>()));
      boolean filed = false;
      for (FilterAction action : postActions(filter)) {
        filed = apply(run, filter, action, entries, applied) || filed;
      }
      for (Entry entry : entries) {
        EmailFilterMatch match = entry.match();
        List<AppliedAction> done = new ArrayList<>(match.getActions() == null ? List.of() : match.getActions());
        done.addAll(applied.get(entry.email().getMailRemoteId()));
        match.setActions(done);
        match.setPostActionsState(EmailFilterMatch.POST_DONE);
        emailFilterStorage.updateMatch(match, run.username);
        if (filed) {
          run.filed.add(entry.email().getMailRemoteId());
        }
      }
    }
    run.counts.forEach((filterId, count) -> emailFilterStorage.addMatches(filterId, run.username, count, run.now));
    run.notifications.forEach((name, count) -> notifyOwner(run.username, name, count, null));
    requestAgent(run.username, run.queued);
  }

  /**
   * The post-actions of a rule, in the order they apply: the ones that address the mail
   * in the inbox first, the filing last.
   *
   * @param filter the rule
   * @return the actions, the assistant left out
   */
  private static List<FilterAction> postActions(EmailFilter filter) {
    List<FilterAction> ordered = new ArrayList<>();
    filter.getActions().stream().filter(action -> !FilterAction.AGENT.equals(action.type()) && !action.files()).forEach(ordered::add);
    filter.getActions().stream().filter(FilterAction::files).forEach(ordered::add);
    return ordered;
  }

  /**
   * Applies one post-action of a rule to a batch of its mails, as the owner, and records
   * the outcome per mail.
   *
   * @param run the pass
   * @param filter the rule
   * @param action the action
   * @param entries the batch
   * @param applied the outcome, per mail UID
   * @return true when the action filed every mail of the batch away
   */
  private boolean apply(Run run,
                        EmailFilter filter,
                        FilterAction action,
                        List<Entry> entries,
                        Map<Long, List<AppliedAction>> applied) {
    String username = run.username;
    List<Long> uids = entries.stream().map(entry -> entry.email().getMailRemoteId()).toList();
    try {
      switch (action.type()) {
      case FilterAction.ADD_CATEGORY -> {
        emailBoxService.linkEmailsToCategory(uids, action.categoryId(), username);
        entries.forEach(entry -> record(applied,
                                        entry,
                                        new AppliedAction(action.type(), true, null, null, null, action.categoryId(), null, null, null, false)));
      }
      case FilterAction.MARK_READ -> {
        List<Long> unread = entries.stream().filter(entry -> !entry.email().isRead()).map(entry -> entry.email().getMailRemoteId()).toList();
        int failures = unread.isEmpty() ? 0 : emailBoxService.updateEmailReadStatus(unread, username, MailFolder.INBOX, true, true);
        entries.forEach(entry -> record(applied,
                                        entry,
                                        new AppliedAction(action.type(),
                                                          failures == 0,
                                                          failures == 0 ? null : ACTION_FAILED,
                                                          null,
                                                          null,
                                                          null,
                                                          entry.email().isRead(),
                                                          null,
                                                          null,
                                                          false)));
      }
      case FilterAction.STAR -> {
        List<Long> unstarred = entries.stream()
                                      .filter(entry -> !entry.email().isStarred())
                                      .map(entry -> entry.email().getMailRemoteId())
                                      .toList();
        int failures = unstarred.isEmpty() ? 0 : emailBoxService.updateEmailStarredStatus(unstarred, username, MailFolder.INBOX, true, true);
        entries.forEach(entry -> record(applied,
                                        entry,
                                        new AppliedAction(action.type(),
                                                          failures == 0,
                                                          failures == 0 ? null : ACTION_FAILED,
                                                          null,
                                                          null,
                                                          null,
                                                          null,
                                                          entry.email().isStarred(),
                                                          null,
                                                          false)));
      }
      case FilterAction.NOTIFY -> {
        run.notifications.merge(filter.getName(), entries.size(), Integer::sum);
        entries.forEach(entry -> record(applied, entry, AppliedAction.applied(action.type())));
      }
      case FilterAction.MOVE_TO_FOLDER, FilterAction.MARK_JUNK, FilterAction.DELETE -> {
        return file(username, action, entries, applied);
      }
      default -> entries.forEach(entry -> record(applied, entry, AppliedAction.failed(action.type(), ACTION_FAILED)));
      }
    } catch (IllegalAccessException | RuntimeException e) {
      String reason = reasonOf(e);
      LOG.warn("Mail filter {} of user {}: action {} failed on {} mail(s): {}", filter.getId(), username, action.type(), uids.size(), reason);
      LOG.debug("Mail filter action failure", e);
      entries.forEach(entry -> record(applied, entry, AppliedAction.failed(action.type(), reason)));
    }
    return false;
  }

  /**
   * Files a batch of mails away: a move, a move to Junk or to Trash.
   *
   * @param username the owner
   * @param action the filing action
   * @param entries the batch
   * @param applied the outcome, per mail UID
   * @return true when every mail was filed
   * @throws IllegalAccessException when the owner may not act on their mailbox
   */
  private boolean file(String username,
                       FilterAction action,
                       List<Entry> entries,
                       Map<Long, List<AppliedAction>> applied) throws IllegalAccessException {
    List<Long> uids = entries.stream().map(entry -> entry.email().getMailRemoteId()).toList();
    String target;
    int failures;
    switch (action.type()) {
    case FilterAction.MOVE_TO_FOLDER -> {
      target = action.folderKey();
      failures = emailBoxService.moveToFolder(uids, username, MailFolder.INBOX, target);
    }
    case FilterAction.MARK_JUNK -> {
      target = MailFolder.JUNK;
      failures = emailBoxService.markAsJunk(uids, username, MailFolder.INBOX);
    }
    default -> {
      target = MailFolder.TRASH;
      failures = emailBoxService.deleteEmail(uids, username, MailFolder.INBOX);
    }
    }
    boolean all = failures == 0;
    boolean none = failures >= uids.size();
    String folder = target;
    entries.forEach(entry -> record(applied,
                                    entry,
                                    new AppliedAction(action.type(),
                                                      !none,
                                                      all ? null : none ? ACTION_FAILED : PARTIAL,
                                                      folder,
                                                      MailFolder.INBOX,
                                                      null,
                                                      null,
                                                      null,
                                                      null,
                                                      false)));
    return all;
  }

  /**
   * Undoes one action of a match.
   *
   * @param username the owner
   * @param match the match
   * @param action the action
   * @param filedInto the folder the match filed the mail into and has not been undone,
   *          or null
   * @return true when undone; false for an action nothing undoes
   * @throws IllegalAccessException when the owner may not act on their mailbox
   */
  private boolean undoOne(String username, EmailFilterMatch match, AppliedAction action, String filedInto) throws IllegalAccessException {
    String headerId = match.getMailHeaderId();
    switch (action.type()) {
    case FilterAction.MOVE_TO_FOLDER -> {
      int failures = emailBoxService.undoMove(List.of(headerId),
                                              username,
                                              action.folderKey(),
                                              StringUtils.defaultIfBlank(action.originFolder(), MailFolder.INBOX));
      return failures == 0;
    }
    case FilterAction.MARK_JUNK -> {
      Email email = located(username, headerId, MailFolder.JUNK);
      return emailBoxService.restoreFromJunk(List.of(email.getMailRemoteId()), username) == 0;
    }
    case FilterAction.DELETE -> {
      Email email = located(username, headerId, MailFolder.TRASH);
      return emailBoxService.restoreEmail(List.of(email.getMailRemoteId()), username) == 0;
    }
    case FilterAction.ADD_CATEGORY -> {
      String folder = filedInto == null ? MailFolder.INBOX : filedInto;
      Email email = located(username, headerId, folder);
      emailBoxService.unlinkEmailsFromCategory(List.of(email.getMailRemoteId()), action.categoryId(), username, folder);
      return true;
    }
    case FilterAction.MARK_READ -> {
      if (!Boolean.FALSE.equals(action.wasRead())) {
        return true;
      }
      String folder = filedInto == null ? MailFolder.INBOX : filedInto;
      Email email = located(username, headerId, folder);
      return emailBoxService.updateEmailReadStatus(List.of(email.getMailRemoteId()), username, folder, false, true) == 0;
    }
    case FilterAction.STAR -> {
      if (!Boolean.FALSE.equals(action.wasStarred())) {
        return true;
      }
      String folder = filedInto == null ? MailFolder.INBOX : filedInto;
      Email email = located(username, headerId, folder);
      return emailBoxService.updateEmailStarredStatus(List.of(email.getMailRemoteId()), username, folder, false, true) == 0;
    }
    default -> {
      return false;
    }
    }
  }

  /**
   * The owner's cached row of a mail in a folder.
   *
   * @param username the owner
   * @param headerId the mail's Message-ID
   * @param folder the folder
   * @return the row
   * @throws IllegalAccessException when the owner may not read their mailbox
   * @throws IllegalArgumentException {@value #UNDO_NOT_YET} when the folder's cache holds
   *           none yet
   */
  private Email located(String username, String headerId, String folder) throws IllegalAccessException {
    Email email = emailBoxService.getOwnEmailByMailHeaderId(username, headerId, folder);
    if (email == null || email.getMailRemoteId() == null) {
      throw new IllegalArgumentException(UNDO_NOT_YET);
    }
    return email;
  }

  /**
   * Makes the server hold the hops of every live hop rule of the owner's, with a rule's
   * new version in place of its stored one.
   *
   * @param username the owner
   * @param changed the rule written, live
   * @param consent as for {@link #createFilter}
   * @param republish as for {@link #createFilter}
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the caller may not use their connector
   * @throws ServerRuleUnavailableException when the server cannot be written
   * @throws ServerRuleConflictException when another client's script is in the way, or
   *           eXo's script changed outside eXo
   * @throws ServerRuleUnsupportedException when the connector cannot hold a hop
   */
  private void publishHops(String username,
                           EmailFilter changed,
                           boolean consent,
                           boolean republish) throws ObjectNotFoundException,
                                              IllegalAccessException,
                                              ServerRuleUnavailableException,
                                              ServerRuleConflictException,
                                              ServerRuleUnsupportedException {
    List<HopRef> hops = hopsBut(username, changed.getId());
    hops.add(hopOf(changed));
    emailServerRuleService.reconcileHops(username, hops, republish, consent, true);
  }

  /**
   * Removes a rule's hop from the server. A connector that holds no rules, or a deployment
   * that switched server rules off, holds no hop to remove: the eXo half goes anyway.
   *
   * @param username the owner
   * @param removed the rule whose hop goes
   * @param republish as for {@link #createFilter}
   * @throws ObjectNotFoundException when no mailbox is connected
   * @throws IllegalAccessException when the caller may not use their connector
   * @throws ServerRuleUnavailableException when the server cannot be written
   * @throws ServerRuleConflictException when eXo's script changed outside eXo
   */
  private void removeHop(String username, EmailFilter removed, boolean republish) throws ObjectNotFoundException,
                                                                                  IllegalAccessException,
                                                                                  ServerRuleUnavailableException,
                                                                                  ServerRuleConflictException {
    try {
      emailServerRuleService.reconcileHops(username, hopsBut(username, removed.getId()), republish, false, false);
    } catch (ServerRuleUnsupportedException e) {
      LOG.debug("No hop to remove for rule {} of user {}: {}", removed.getId(), username, e.getMessage());
    } catch (ObjectNotFoundException e) {
      if (!EmailServerRuleService.DISABLED.equals(e.getMessage())) {
        throw e;
      }
    }
  }

  /**
   * The hops of the owner's live hop rules, but one.
   *
   * @param username the owner
   * @param exceptId the rule left out, or null
   * @return the hops, modifiable
   */
  private List<HopRef> hopsBut(String username, Long exceptId) {
    List<HopRef> hops = new ArrayList<>();
    for (EmailFilter filter : emailFilterStorage.getFilters(username)) {
      if (!Objects.equals(filter.getId(), exceptId) && isLiveHop(filter) && isRunnable(filter)) {
        hops.add(hopOf(filter));
      }
    }
    return hops;
  }

  /**
   * The server half of a hop rule.
   *
   * @param filter the rule, named
   * @return the hop
   */
  static HopRef hopOf(EmailFilter filter) {
    return new HopRef(filter.getServerRuleRef(),
                      filter.getName(),
                      filter.isMatchAll(),
                      filter.getConditions(),
                      filter.getTagKeyword(),
                      filter.isStopProcessing());
  }

  /**
   * A hop rule with the names of its two halves, both from its id.
   *
   * @param filter the rule, with its id
   * @return the rule, {@code SERVER_RULE_REF} and {@code TAG_KEYWORD} set
   */
  static EmailFilter withHopNames(EmailFilter filter) {
    filter.setServerRuleRef(HopRef.REF_PREFIX + filter.getId());
    filter.setTagKeyword(ServerRule.TAG_PREFIX + filter.getId());
    return filter;
  }

  /**
   * Whether a rule has a server half the server should hold.
   *
   * @param filter the rule
   * @return true for an enabled hop rule with its names
   */
  private static boolean isLiveHop(EmailFilter filter) {
    return EmailFilter.KIND_HOP.equals(filter.getKind()) && filter.isEnabled() && filter.getTagKeyword() != null
        && filter.getServerRuleRef() != null;
  }

  /**
   * The owner's enabled rules of a mailbox's scope that eXo can run, in order. A stored
   * rule eXo cannot read any more is switched off, with the reason, once.
   *
   * @param username the owner
   * @param context the mailbox
   * @return the rules
   */
  private List<EmailFilter> runnableFilters(String username, FilterRunContext context) {
    List<EmailFilter> runnable = new ArrayList<>();
    for (EmailFilter filter : emailFilterStorage.getFilters(username)) {
      if (!filter.isEnabled() || !context.mailboxScope().equals(filter.getMailboxScope())) {
        continue;
      }
      if (isRunnable(filter)) {
        runnable.add(filter);
      } else {
        LOG.warn("Mail filter {} of user {} cannot be read; it is switched off", filter.getId(), username);
        emailFilterStorage.disableWithError(filter.getId(), username, UNREADABLE, now());
      }
    }
    return runnable;
  }

  /**
   * Whether a stored rule passes the validation a save applies, its folders and
   * categories aside (those are checked by the mailbox when the action runs).
   *
   * @param filter the rule
   * @return true when eXo can run it
   */
  private static boolean isRunnable(EmailFilter filter) {
    try {
      if (filter.getConditions() == null || filter.getActions() == null || filter.getActions().isEmpty()
          || !EmailFilter.KINDS.contains(filter.getKind())
          || EmailFilter.KIND_HOP.equals(filter.getKind()) && filter.getTagKeyword() == null) {
        return false;
      }
      validatedConditions(filter.getConditions(), filter.getKind());
      return true;
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /**
   * Validates and normalises a rule as the form sent it: its name, kind, scope,
   * conditions and actions. The ids, counters, names of the server half and the order
   * are the service's, never the form's.
   *
   * @param username the owner, whose folders and categories the actions must name
   * @param input the rule
   * @return a new rule, normalised
   * @throws IllegalArgumentException with a message code
   */
  EmailFilter validated(String username, EmailFilter input) {
    if (input == null) {
      throw new IllegalArgumentException(ServerRule.INVALID);
    }
    String name = StringUtils.trimToEmpty(input.getName());
    if (name.isEmpty() || name.length() > ServerRule.MAX_NAME_LENGTH || hasControl(name, false)) {
      throw new IllegalArgumentException(ServerRule.INVALID_NAME);
    }
    String kind = StringUtils.defaultIfBlank(input.getKind(), EmailFilter.KIND_EXO).trim().toUpperCase(Locale.ROOT);
    if (!EmailFilter.KINDS.contains(kind)) {
      throw new IllegalArgumentException(INVALID_KIND);
    }
    if (input.getMailboxScope() != null && !EmailFilter.SCOPE_OWN.equals(input.getMailboxScope())) {
      throw new IllegalArgumentException(INVALID_SCOPE);
    }
    List<FilterAction> actions = validatedActions(username, input.getActions());
    EmailFilter filter = new EmailFilter();
    filter.setName(name);
    filter.setEnabled(input.isEnabled());
    filter.setKind(kind);
    filter.setMailboxScope(EmailFilter.SCOPE_OWN);
    filter.setMatchAll(input.isMatchAll());
    filter.setConditions(validatedConditions(input.getConditions(), kind));
    filter.setActions(actions);
    filter.setStopProcessing(input.isStopProcessing());
    filter.setAgentNameId(actions.stream()
                                 .filter(action -> FilterAction.AGENT.equals(action.type()))
                                 .map(FilterAction::agentNameId)
                                 .findFirst()
                                 .orElse(null));
    return filter;
  }

  /**
   * Validates a rule's conditions against the vocabulary of its kind: a rule the server
   * runs too ({@link EmailFilter#KIND_HOP}, {@link EmailFilter#KIND_SERVER}) takes the
   * server's vocabulary only; one eXo alone runs takes the body and attachment fields
   * too, and not the size, which eXo does not keep.
   *
   * @param conditions the conditions
   * @param kind the rule's kind
   * @return the conditions, normalised
   * @throws IllegalArgumentException {@value ServerRule#INVALID_CONDITION}
   */
  static List<ServerRule.Condition> validatedConditions(List<ServerRule.Condition> conditions, String kind) {
    if (conditions == null || conditions.isEmpty() || conditions.size() > ServerRule.MAX_CONDITIONS) {
      throw new IllegalArgumentException(ServerRule.INVALID_CONDITION);
    }
    boolean exoOnly = EmailFilter.KIND_EXO.equals(kind);
    List<ServerRule.Condition> clean = new ArrayList<>();
    for (ServerRule.Condition condition : conditions) {
      if (condition == null || condition.field() == null || condition.operator() == null) {
        throw new IllegalArgumentException(ServerRule.INVALID_CONDITION);
      }
      String field = condition.field().trim().toUpperCase(Locale.ROOT);
      String operator = condition.operator().trim().toUpperCase(Locale.ROOT);
      if (EXO_ONLY_FIELDS.contains(field)) {
        if (!exoOnly) {
          throw new IllegalArgumentException(ServerRule.INVALID_CONDITION);
        }
        clean.add(exoCondition(field, operator, condition.value()));
      } else if (ServerRule.MESSAGE_SIZE.equals(field) && exoOnly) {
        throw new IllegalArgumentException(ServerRule.INVALID_CONDITION);
      } else {
        // The server's vocabulary, validated by the very rule a server write goes through.
        ServerRule probe = new ServerRule(null,
                                          "probe",
                                          true,
                                          true,
                                          List.of(condition),
                                          List.of(new ServerRule.Action(ServerRule.MARK_READ, null, null, null)),
                                          false);
        clean.add(probe.validated().conditions().get(0));
      }
    }
    return clean;
  }

  /**
   * Validates a condition only eXo evaluates.
   *
   * @param field the field, upper-case
   * @param operator the operator, upper-case
   * @param value the value
   * @return the condition, normalised
   * @throws IllegalArgumentException {@value ServerRule#INVALID_CONDITION}
   */
  private static ServerRule.Condition exoCondition(String field, String operator, String value) {
    if (FilterConditionEvaluator.HAS_ATTACHMENT.equals(field)) {
      if (!ServerRule.IS_TRUE.equals(operator) && !ServerRule.IS_FALSE.equals(operator)) {
        throw new IllegalArgumentException(ServerRule.INVALID_CONDITION);
      }
      return new ServerRule.Condition(field, operator, null, null);
    }
    String text = StringUtils.trimToEmpty(value);
    if (!ServerRule.TEXT_OPERATORS.contains(operator) || text.isEmpty() || text.length() > ServerRule.MAX_VALUE_LENGTH
        || hasControl(text, false)) {
      throw new IllegalArgumentException(ServerRule.INVALID_CONDITION);
    }
    return new ServerRule.Condition(field, operator, null, text);
  }

  /**
   * Validates a rule's actions: known types, each at most once, one filing at most, the
   * folder one of the owner's own mirrored folders or Archive, the category one of the
   * owner's, the assistant named and its instruction bounded. Every post-action of a rule
   * with an assistant waits for it.
   *
   * @param username the owner
   * @param actions the actions
   * @return the actions, normalised
   * @throws IllegalArgumentException with a message code
   */
  private List<FilterAction> validatedActions(String username, List<FilterAction> actions) {
    if (actions == null || actions.isEmpty()) {
      throw new IllegalArgumentException(ServerRule.INVALID_ACTION);
    }
    Set<String> types = new LinkedHashSet<>();
    List<FilterAction> clean = new ArrayList<>();
    for (FilterAction action : actions) {
      String type = action == null || action.type() == null ? null : action.type().trim().toUpperCase(Locale.ROOT);
      if (type == null || !FilterAction.TYPES.contains(type) || !types.add(type)) {
        throw new IllegalArgumentException(ServerRule.INVALID_ACTION);
      }
      clean.add(switch (type) {
      case FilterAction.MOVE_TO_FOLDER -> new FilterAction(type, ownFolder(username, action.folderKey()), null, null, null, null, null);
      case FilterAction.ADD_CATEGORY -> new FilterAction(type, null, ownCategory(username, action.categoryId()), null, null, null, null);
      case FilterAction.AGENT -> agentAction(action);
      default -> new FilterAction(type, null, null, null, null, null, null);
      });
    }
    if (clean.stream().filter(FilterAction::files).count() > 1) {
      throw new IllegalArgumentException(ServerRule.INVALID_ACTION);
    }
    String when = types.contains(FilterAction.AGENT) ? FilterAction.AFTER_AGENT : FilterAction.IMMEDIATE;
    return clean.stream().map(action -> FilterAction.AGENT.equals(action.type()) ? action : action.withWhen(when)).toList();
  }

  /**
   * Validates the assistant action.
   *
   * @param action the action
   * @return the action, normalised
   * @throws IllegalArgumentException {@value #INVALID_AGENT}
   */
  private static FilterAction agentAction(FilterAction action) {
    String agent = StringUtils.trimToEmpty(action.agentNameId());
    String instruction = StringUtils.trimToEmpty(action.instruction());
    if (!AGENT_NAME.matcher(agent).matches() || instruction.length() > MAX_INSTRUCTION_LENGTH || hasControl(instruction, true)) {
      throw new IllegalArgumentException(INVALID_AGENT);
    }
    List<String> outputs = new ArrayList<>();
    for (String output : action.outputs()) {
      String clean = output == null ? null : output.trim().toUpperCase(Locale.ROOT);
      if (!FilterAction.AGENT_OUTPUTS.contains(clean)) {
        throw new IllegalArgumentException(INVALID_AGENT);
      }
      if (!outputs.contains(clean)) {
        outputs.add(clean);
      }
    }
    return new FilterAction(FilterAction.AGENT, null, null, agent, instruction, outputs, null);
  }

  /**
   * The key of a folder a rule may file into: one of the owner's own custom folders they
   * mirror, or Archive -- never a shared mailbox's folder.
   *
   * @param username the owner
   * @param key the folder's eXo key
   * @return the key
   * @throws IllegalArgumentException {@code emailConnector.folder.unknown} or
   *           {@code emailConnector.folder.notMirrored}
   */
  private String ownFolder(String username, String key) {
    String folderKey = StringUtils.trimToNull(key);
    if (MailFolder.ARCHIVE.equals(folderKey)) {
      return folderKey;
    }
    if (folderKey == null || !MailFolder.isCustom(folderKey)) {
      throw new IllegalArgumentException(EmailFolderService.UNKNOWN_FOLDER_MESSAGE);
    }
    EmailFolder folder = emailFolderService.getFolderByKey(username, folderKey);
    if (folder.isMissing() || folder.getDelegationId() != null) {
      throw new IllegalArgumentException(EmailFolderService.UNKNOWN_FOLDER_MESSAGE);
    }
    if (!folder.isSyncEnabled()) {
      throw new IllegalArgumentException(EmailServerRuleService.FOLDER_NOT_MIRRORED);
    }
    return folderKey;
  }

  /**
   * A category a rule may put on a mail: one of the owner's available ones.
   *
   * @param username the owner
   * @param categoryId the category
   * @return the id
   * @throws IllegalArgumentException {@value #UNKNOWN_CATEGORY}
   */
  private Long ownCategory(String username, Long categoryId) {
    if (categoryId == null) {
      throw new IllegalArgumentException(UNKNOWN_CATEGORY);
    }
    List<EmailCategory> available = emailBoxService.getAvailableEmailCategories(username, Locale.ENGLISH);
    if (available == null || available.stream().noneMatch(category -> category.getId() == categoryId)) {
      throw new IllegalArgumentException(UNKNOWN_CATEGORY);
    }
    return categoryId;
  }

  /**
   * Checks, in order, that the feature is on, the request is about the caller's own
   * mailbox, a mailbox is connected, and the caller may still use its connector.
   *
   * @param username the caller
   * @param delegationId the share the request was made from; any value is refused
   * @throws ObjectNotFoundException when the feature is off, or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use the connector
   */
  private void checkOwnMailbox(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    if (!isEnabled()) {
      throw new ObjectNotFoundException(DISABLED);
    }
    if (delegationId != null) {
      throw new IllegalAccessException(EmailServerRuleService.OWN_MAILBOX_ONLY);
    }
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    if (setting == null || StringUtils.isBlank(setting.getEmailConnectorId())) {
      throw new ObjectNotFoundException(EmailServerRuleService.NOT_CONNECTED);
    }
    if (!userEmailSettingService.canConnect(Long.parseLong(setting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(EmailServerRuleService.NOT_ALLOWED);
    }
  }

  /**
   * One rule of the caller's.
   *
   * @param username the caller
   * @param id the rule
   * @return the rule
   * @throws ObjectNotFoundException {@value #NOT_FOUND} when it is not the caller's
   */
  private EmailFilter ownFilter(String username, long id) throws ObjectNotFoundException {
    return emailFilterStorage.getFilter(id, username).orElseThrow(() -> new ObjectNotFoundException(NOT_FOUND));
  }

  /**
   * One match of the caller's.
   *
   * @param username the caller
   * @param id the match
   * @return the match
   * @throws ObjectNotFoundException {@value #MATCH_NOT_FOUND} when it is not the caller's
   */
  private EmailFilterMatch ownMatch(String username, long id) throws ObjectNotFoundException {
    return emailFilterStorage.getMatch(id, username).orElseThrow(() -> new ObjectNotFoundException(MATCH_NOT_FOUND));
  }

  /**
   * A match with its rule's name.
   *
   * @param username the owner
   * @param match the match
   * @return the match
   */
  private EmailFilterMatch named(String username, EmailFilterMatch match) {
    return named(username, List.of(match)).get(0);
  }

  /**
   * Matches with their rules' names; a rule deleted since leaves its name null.
   *
   * @param username the owner
   * @param matches the matches
   * @return the matches
   */
  private List<EmailFilterMatch> named(String username, List<EmailFilterMatch> matches) {
    Map<Long, String> names = new HashMap<>();
    emailFilterStorage.getFilters(username).forEach(filter -> names.put(filter.getId(), filter.getName()));
    matches.forEach(match -> match.setFilterName(names.get(match.getFilterId())));
    return matches;
  }

  /**
   * A cached mail as the conditions read it, loading its whole row only when a condition
   * needs its recipients or body.
   *
   * @param username the owner
   * @param email the listed row
   * @return the mail
   */
  private EmailFilterMail cachedMail(String username, Email email) {
    return new EmailFilterMail(email, () -> emailBoxService.getEmailById(email.getId(), username), Set.of(), null, null);
  }

  /**
   * Asks for the assistant on the given matches: a request, answered by whatever glue a
   * deployment has, and by nothing on one without; the matches then wait.
   *
   * @param username the owner
   * @param matchIds the matches queued
   */
  private void requestAgent(String username, List<Long> matchIds) {
    if (matchIds.isEmpty()) {
      return;
    }
    try {
      listenerService.broadcast(EmailConnectorUtils.FILTER_AGENT_REQUESTED, username, List.copyOf(matchIds));
    } catch (Exception e) {
      LOG.warn("The assistant could not be asked for {} mail filter match(es) of user {}", matchIds.size(), username, e);
    }
  }

  /**
   * Deletes the owner's matches past the retention.
   *
   * @param username the owner
   */
  private void prune(String username) {
    int days = intProperty(RETENTION_PROPERTY, 90);
    if (days > 0) {
      emailFilterStorage.pruneMatches(username, Date.from(clock.instant().minus(days, ChronoUnit.DAYS)));
    }
  }

  /**
   * Whether the assistant may be queued on one more match of the owner's.
   *
   * @param run the pass
   * @return the status of the new match: pending, or skipped with the reason
   */
  private String agentStatus(Run run) {
    if (!Boolean.parseBoolean(System.getProperty(AGENT_ENABLED_PROPERTY, "true").trim())) {
      return EmailFilterMatch.AGENT_SKIPPED_DISABLED;
    }
    if (run.pendingBefore < 0) {
      run.pendingBefore = emailFilterStorage.countByAgentStatus(run.username, EmailFilterMatch.AGENT_PENDING);
      Instant startOfDay = clock.instant().truncatedTo(ChronoUnit.DAYS);
      run.queuedToday = emailFilterStorage.countQueuedSince(run.username, QUEUED_STATUSES, Date.from(startOfDay));
    }
    long queuedNow = run.queued.size();
    if (run.pendingBefore + queuedNow >= intProperty(MAX_PENDING_PROPERTY, 200)
        || run.queuedToday + queuedNow >= intProperty(DAILY_CAP_PROPERTY, 100)) {
      return EmailFilterMatch.AGENT_SKIPPED_CAP;
    }
    return EmailFilterMatch.AGENT_PENDING;
  }

  /**
   * An integer system property.
   *
   * @param name the property
   * @param defaultValue its default
   * @return the value, the default when unset or unreadable
   */
  private static int intProperty(String name, int defaultValue) {
    try {
      return Integer.parseInt(System.getProperty(name, String.valueOf(defaultValue)).trim());
    } catch (NumberFormatException e) {
      return defaultValue;
    }
  }

  /**
   * Whether eXo rules are switched on.
   *
   * @return the deployment's switch
   */
  private static boolean isEnabled() {
    return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "true").trim());
  }

  /**
   * The message code of a refusal, or the generic one.
   *
   * @param e the refusal
   * @return a code
   */
  private static String reasonOf(Exception e) {
    String message = e.getMessage();
    return message != null && message.startsWith("emailConnector.") && message.length() < 200 ? message : ACTION_FAILED;
  }

  /**
   * Records an action's outcome for one mail.
   *
   * @param applied the outcomes, per UID
   * @param entry the mail
   * @param action the outcome
   */
  private static void record(Map<Long, List<AppliedAction>> applied, Entry entry, AppliedAction action) {
    applied.computeIfAbsent(entry.email().getMailRemoteId(), key -> new ArrayList<>()).add(action);
  }

  /**
   * Whether a text holds a control character, a line break allowed or not.
   *
   * @param text the text
   * @param lineBreaks whether line breaks and tabs are allowed
   * @return true when one is found
   */
  private static boolean hasControl(String text, boolean lineBreaks) {
    return text.chars()
               .anyMatch(c -> (Character.isISOControl(c) && !(lineBreaks && (c == '\n' || c == '\r' || c == '\t')))
                   || Character.getType(c) == Character.LINE_SEPARATOR || Character.getType(c) == Character.PARAGRAPH_SEPARATOR);
  }

  /**
   * Now.
   *
   * @return the date
   */
  private Date now() {
    return Date.from(clock.instant());
  }

  /**
   * One match and the mail it matched.
   *
   * @param match the match
   * @param email the mail, as cached
   */
  private record Entry(EmailFilterMatch match, Email email) {
  }

  /**
   * One pass of rules over some mails: what was matched, what must be applied, and what
   * must be told.
   */
  private final class Run {

    /** The owner. */
    private final String                        username;

    /** The pass's date. */
    private final Date                          now;

    /** True for the sync's pass over new mail, false for a pass over cached mail. */
    private final boolean                       sync;

    /** Whether the assistant may be queued in this pass. */
    private final boolean                       withAgent;

    /** The matches whose post-actions apply in this pass, per rule, in rule order. */
    private final Map<EmailFilter, List<Entry>> immediate     = new LinkedHashMap<>();

    /** The new matches per rule, for the counters. */
    private final Map<Long, Long>               counts        = new LinkedHashMap<>();

    /** How many mails each notifying rule matched, by rule name. */
    private final Map<String, Integer>          notifications = new LinkedHashMap<>();

    /** The matches queued for the assistant. */
    private final List<Long>                    queued        = new ArrayList<>();

    /** The UIDs a rule filed away. */
    private final Set<Long>                     filed         = new HashSet<>();

    /** How many new matches. */
    private int                                 matched;

    /** How many matches a rule had handled before. */
    private int                                 alreadyHandled;

    /** How many mails a condition could not decide. */
    private int                                 notApplicable;

    /** How many of the assistant's runs the pass offered, for the one-off cap. */
    private int                                 agentOffered;

    /** The owner's pending matches before the pass; -1 until read. */
    private long                                pendingBefore = -1;

    /** The owner's runs queued today before the pass. */
    private long                                queuedToday;

    /**
     * A pass.
     *
     * @param username the owner
     * @param now the pass's date
     * @param sync true for the sync's pass
     * @param withAgent whether the assistant may be queued
     */
    private Run(String username, Date now, boolean sync, boolean withAgent) {
      this.username = username;
      this.now = now;
      this.sync = sync;
      this.withAgent = withAgent;
    }

    /**
     * Takes a new match into the pass: queued for the assistant, its post-actions waiting
     * for it, or its post-actions applied in this pass.
     *
     * @param filter the rule
     * @param match the match, stored
     * @param email the mail
     */
    private void add(EmailFilter filter, EmailFilterMatch match, Email email) {
      matched++;
      counts.merge(filter.getId(), 1L, Long::sum);
      if (filter.hasAgent() && withAgent && (sync || agentOffered++ < intProperty(RETROACTIVE_MAX_PROPERTY, 50))) {
        String status = agentStatus(this);
        match.setAgentStatus(status);
        if (EmailFilterMatch.AGENT_PENDING.equals(status)) {
          match.setPostActionsState(EmailFilterMatch.POST_PENDING_AGENT);
          queued.add(emailFilterStorage.updateMatch(match, username).getId());
          return;
        }
      }
      immediate.computeIfAbsent(filter, key -> new ArrayList<>()).add(new Entry(match, email));
    }
  }
}
