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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.EmailFilterProposal;
import org.exoplatform.emailConnector.model.EmailFilterSuggestionCounts;
import org.exoplatform.emailConnector.model.EmailWaitingSuggestionMail;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.plugin.EmailFilterAgentHandler;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailFilterProposalStorage;
import org.exoplatform.emailConnector.storage.EmailFilterStorage;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The tool calls a mail filter's assistant proposed instead of running them, and the
 * owner's decision on each: approve, reject, or hand it to the AI chat.
 * <p>
 * <b>Recorded while the assistant runs.</b> Only the handler of a match whose assistant
 * is {@code RUNNING} records a call, for the match's owner; a run records at most
 * {@value #DEFAULT_MAX_PER_RUN} calls ({@value #MAX_PER_RUN_PROPERTY}), a mailbox holds at
 * most {@value #DEFAULT_MAX_PENDING} waiting ones ({@value #MAX_PENDING_PROPERTY}), and one
 * call is recorded once per match. A call waits {@value #DEFAULT_TTL_DAYS} days
 * ({@value #TTL_DAYS_PROPERTY}), then expires.
 * <p>
 * <b>Decided by its owner only, once.</b> Every decision moves the status from
 * {@code PROPOSED} by a conditional UPDATE scoped to the owner and to an unexpired row:
 * a second click, another tab or another node finds it moved and is refused. An approved
 * call runs as the owner, through the handler, which runs it on the platform's own tool
 * path; how it ended is written on the row. A handed-over call never runs from here.
 */
@Service
public class EmailFilterProposalService {

  /** How many calls one run of a mail's assistant may record. */
  public static final String  MAX_PER_RUN_PROPERTY   = "exo.email.filters.agent.tools.maxProposalsPerMail";

  /** How many waiting calls a mailbox may hold. */
  public static final String  MAX_PENDING_PROPERTY   = "exo.email.filters.agent.tools.maxPendingPerMailbox";

  /** How many days a call waits for its owner. */
  public static final String  TTL_DAYS_PROPERTY      = "exo.email.filters.agent.tools.proposalTtlDays";

  /** No such proposal. */
  public static final String  NOT_FOUND              = "emailConnector.filters.proposal.notFound";

  /** Someone else's proposal. */
  public static final String  NOT_YOURS              = "emailConnector.filters.proposal.notYours";

  /** A proposal already decided. */
  public static final String  NOT_PENDING            = "emailConnector.filters.proposal.notPending";

  /** A proposal past its expiry. */
  public static final String  EXPIRED                = "emailConnector.filters.proposal.expired";

  /** A run that recorded as many calls as it may. */
  public static final String  RUN_CAP                = "emailConnector.filters.proposal.runCap";

  /** A mailbox holding as many waiting calls as it may. */
  public static final String  PENDING_CAP            = "emailConnector.filters.proposal.pendingCap";

  /** A call recorded for a match whose assistant is not running. */
  public static final String  NOT_RUNNING            = "emailConnector.filters.proposal.notRunning";

  /** A call without a valid tool name, or with too long arguments. */
  public static final String  INVALID                = "emailConnector.filters.proposal.invalid";

  /** No handler runs tools on this deployment. */
  public static final String  UNAVAILABLE            = "emailConnector.filters.proposal.unavailable";

  /** An approved call whose run never came back: its node died, or it never ended. */
  public static final String  INTERRUPTED            = "emailConnector.filters.proposal.interrupted";

  /** How long an approved call may run before it is taken for abandoned, in minutes. */
  static final long           RUNNING_CEILING_MINUTES = 15;

  /** A call whose tool failed without a message of its own. */
  public static final String  TOOL_FAILED            = "emailConnector.filters.proposal.toolFailed";

  /** The default of {@value #MAX_PER_RUN_PROPERTY}. */
  static final int            DEFAULT_MAX_PER_RUN    = 3;

  /** The default of {@value #MAX_PENDING_PROPERTY}. */
  static final int            DEFAULT_MAX_PENDING    = 50;

  /** The default of {@value #TTL_DAYS_PROPERTY}. */
  static final int            DEFAULT_TTL_DAYS       = 14;

  /** The longest arguments recorded, in characters. */
  static final int            MAX_ARGUMENTS_LENGTH   = 65_536;

  /** A tool's name, as the platform's tool definitions write them. */
  private static final Pattern TOOL_NAME             = Pattern.compile("[A-Za-z0-9_.-]{1,200}");

  /** The logger. */
  private static final Log    LOG                    = ExoLogger.getLogger(EmailFilterProposalService.class);

  @Autowired
  private EmailFilterProposalStorage              emailFilterProposalStorage;

  @Autowired
  private EmailFilterStorage                      emailFilterStorage;

  @Autowired
  private EmailFilterService                      emailFilterService;

  @Autowired
  private ObjectProvider<EmailFilterAgentHandler> agentHandlers;

  @Autowired
  private EmailFilterSuggestionDigest             suggestionDigest;

  @Autowired
  private EmailBoxStorage                         emailBoxStorage;

  @Autowired
  private EmailDelegationService                  emailDelegationService;

  private Clock                                   clock                = Clock.systemUTC();

  /**
   * Records a tool call a match's assistant made, for the match's owner to decide: the
   * handler's write, while that assistant runs. A call the match already holds is not
   * recorded again: the proposal it holds is answered, and one a later run superseded is
   * put back to wait, as a new one.
   *
   * @param username the owner, as whom the assistant runs
   * @param matchId the match
   * @param conversationId the run's conversation
   * @param toolName the tool's name
   * @param toolTitle the tool's title, from its definition; may be null
   * @param toolDescription the tool's description, from its definition; may be null
   * @param arguments the arguments, as the model gave them
   * @return the proposal
   * @throws ObjectNotFoundException {@value EmailFilterService#MATCH_NOT_FOUND} when the
   *           match is not this user's
   * @throws IllegalArgumentException {@value #INVALID} for a tool name that is none, or
   *           arguments longer than {@value #MAX_ARGUMENTS_LENGTH} characters
   * @throws IllegalStateException {@value #NOT_RUNNING} when the match's assistant is not
   *           running, {@value #RUN_CAP} or {@value #PENDING_CAP} past a cap
   */
  public EmailFilterProposal createProposal(String username,
                                            long matchId,
                                            String conversationId,
                                            String toolName,
                                            String toolTitle,
                                            String toolDescription,
                                            String arguments) throws ObjectNotFoundException {
    if (toolName == null || !TOOL_NAME.matcher(toolName).matches() || StringUtils.length(arguments) > MAX_ARGUMENTS_LENGTH
        || StringUtils.isBlank(conversationId)) {
      throw new IllegalArgumentException(INVALID);
    }
    EmailFilterMatch match = emailFilterStorage.getMatch(matchId, username)
                                               .orElseThrow(() -> new ObjectNotFoundException(EmailFilterService.MATCH_NOT_FOUND));
    if (!EmailFilterMatch.AGENT_RUNNING.equals(match.getAgentStatus())) {
      throw new IllegalStateException(NOT_RUNNING);
    }
    String callArguments = StringUtils.defaultIfBlank(arguments, "{}");
    Optional<EmailFilterProposal> existing = emailFilterProposalStorage.getByCall(username, matchId, toolName, callArguments);
    if (existing.isPresent() && !isSuperseded(existing.get())) {
      return existing.get();
    }
    checkCaps(username, matchId, conversationId);
    long now = clock.millis();
    EmailFilterProposal proposal = existing.orElseGet(EmailFilterProposal::new);
    proposal.setMatchId(matchId);
    proposal.setFilterId(match.getFilterId());
    proposal.setToolName(toolName);
    proposal.setToolTitle(StringUtils.trimToNull(toolTitle));
    proposal.setToolDescription(StringUtils.trimToNull(toolDescription));
    proposal.setArguments(callArguments);
    proposal.setRationale(null);
    proposal.setStatus(EmailFilterProposal.PROPOSED);
    proposal.setCreatedDate(now);
    proposal.setExpiresDate(now + ChronoUnit.DAYS.getDuration().toMillis() * ttlDays());
    proposal.setDecidedDate(null);
    proposal.setConversationId(StringUtils.left(conversationId, 64));
    proposal.setResult(null);
    proposal.setLastError(null);
    if (existing.isPresent()) {
      return emailFilterProposalStorage.update(proposal, username)
                                       .orElseThrow(() -> new ObjectNotFoundException(NOT_FOUND));
    }
    return emailFilterProposalStorage.create(proposal, username)
                                     .or(() -> emailFilterProposalStorage.getByCall(username, matchId, toolName, callArguments))
                                     .orElseThrow(() -> new IllegalStateException(NOT_PENDING));
  }

  /**
   * Writes the assistant's one-line reasons on the calls its run recorded: the run's
   * write, once its answer is read. A reason for another match's proposal, or for one no
   * longer waiting, is ignored.
   *
   * @param username the owner
   * @param matchId the match
   * @param rationales the reasons, by proposal id
   * @return how many were written
   */
  public int setRationales(String username, long matchId, Map<Long, String> rationales) {
    if (rationales == null || rationales.isEmpty()) {
      return 0;
    }
    int written = 0;
    for (Map.Entry<Long, String> entry : rationales.entrySet()) {
      String why = StringUtils.trimToNull(entry.getValue());
      // One conditional UPDATE of the reason alone: an approval that moved the row in
      // between is never undone by this write.
      if (why != null && entry.getKey() != null && emailFilterProposalStorage.setRationale(entry.getKey(), username, matchId, why)) {
        written++;
      }
    }
    return written;
  }

  /**
   * Expires the calls a match's assistant proposed and that still wait: a new run of it
   * supersedes them. The ones already decided stay as they are.
   *
   * @param username the owner
   * @param matchId the match
   * @return how many
   */
  public int supersede(String username, long matchId) {
    return emailFilterProposalStorage.expireOfMatch(username, matchId, EmailFilterProposal.SUPERSEDED, new Date(clock.millis()));
  }

  /**
   * The proposals of some of the owner's matches, the expired ones marked so first, and
   * an approved call still running past {@value #RUNNING_CEILING_MINUTES} minutes failed
   * {@value #INTERRUPTED}: the cards of a mail's Automations panel.
   *
   * @param username the owner
   * @param matchIds the matches
   * @return the proposals, oldest first
   */
  public List<EmailFilterProposal> getProposalsOfMatches(String username, Collection<Long> matchIds) {
    if (matchIds == null || matchIds.isEmpty()) {
      return List.of();
    }
    Date now = new Date(clock.millis());
    expireDue(username, now);
    emailFilterProposalStorage.failStaleRunning(username,
                                                INTERRUPTED,
                                                new Date(now.getTime() - ChronoUnit.MINUTES.getDuration().toMillis() * RUNNING_CEILING_MINUTES));
    return emailFilterProposalStorage.getByMatches(username, matchIds);
  }

  /**
   * The Message-IDs of the caller's mails with a suggestion still waiting for them: what
   * the mailbox list marks, once per load. Of the caller's own mailbox only.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the Message-IDs, each once
   * @throws ObjectNotFoundException when the feature is off or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   */
  public List<String> getWaitingMails(String username, Long delegationId) throws ObjectNotFoundException, IllegalAccessException {
    emailFilterService.checkOwnMailbox(username, delegationId);
    return emailFilterProposalStorage.getWaitingMailHeaderIds(username, new Date(clock.millis()));
  }

  /**
   * The caller's mails with a suggestion still waiting for them, as the mailbox's
   * "Suggestions" view lists them (EXO-90851): one cached copy per message, newest first,
   * with how many suggestions wait on it. The copy is one the user can open from a list:
   * never in Trash, Spam, All Mail or Drafts, nor in a mailbox somebody shared with them
   * -- the folders the Favorites leave out, for the same reasons. A message with no such
   * copy cached is not listed. Of the caller's own mailbox only; two reads, whatever the
   * number of mails, which the mailbox's pending cap bounds.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the mails, newest first
   * @throws ObjectNotFoundException when the feature is off or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   */
  public List<EmailWaitingSuggestionMail> getWaitingEmails(String username, Long delegationId) throws ObjectNotFoundException,
                                                                                               IllegalAccessException {
    emailFilterService.checkOwnMailbox(username, delegationId);
    Map<String, Integer> waiting = new HashMap<>();
    emailFilterProposalStorage.getWaitingMailHeaderIds(username, new Date(clock.millis()))
                              .stream()
                              .filter(StringUtils::isNotBlank)
                              .forEach(mailHeaderId -> waiting.merge(mailHeaderId, 1, Integer::sum));
    if (waiting.isEmpty()) {
      return List.of();
    }
    List<String> excludedFolders = MailFolder.notFavoritedFolders(emailDelegationService.getDelegatedFolderKeys(username));
    Map<String, EmailWaitingSuggestionMail> listed = new LinkedHashMap<>();
    for (Email email : emailBoxStorage.getListedEmailsByMailHeaderIds(username, waiting.keySet(), excludedFolders)) {
      // Newest copy first: a message filed in two folders is listed once, by its newest.
      listed.computeIfAbsent(email.getMailHeaderId(), mailHeaderId -> waitingMail(email, waiting.get(mailHeaderId)));
    }
    return new ArrayList<>(listed.values());
  }

  /**
   * Puts on each match the calls its assistant proposed.
   *
   * @param username the owner
   * @param matches the owner's matches
   * @return the matches
   */
  public List<EmailFilterMatch> withProposals(String username, List<EmailFilterMatch> matches) {
    if (matches == null || matches.isEmpty()) {
      return matches;
    }
    List<EmailFilterProposal> proposals = getProposalsOfMatches(username, matches.stream().map(EmailFilterMatch::getId).toList());
    matches.forEach(match -> match.setProposals(proposals.stream()
                                                         .filter(proposal -> Objects.equals(proposal.getMatchId(), match.getId()))
                                                         .toList()));
    return matches;
  }

  /**
   * Approves a proposal of the caller's and runs it, as the caller, through the handler.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the proposal
   * @return the proposal once run: {@code DONE} with the tool's answer, or
   *         {@code FAILED} with its reason
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           there is no such proposal
   * @throws IllegalAccessException when the request comes from someone else's mailbox,
   *           the caller may not use their connector, or the proposal is not theirs
   * @throws IllegalStateException {@value #EXPIRED} or {@value #NOT_PENDING} when it no
   *           longer waits
   */
  public EmailFilterProposal approve(String username, Long delegationId, long id) throws ObjectNotFoundException,
                                                                                  IllegalAccessException {
    EmailFilterProposal proposal = claim(username, delegationId, id, EmailFilterProposal.RUNNING);
    EmailFilterAgentHandler handler = agentHandlers == null ? null : agentHandlers.stream().findFirst().orElse(null);
    if (handler == null) {
      emailFilterProposalStorage.finish(id, username, EmailFilterProposal.FAILED, null, UNAVAILABLE);
      return reread(username, id);
    }
    boolean finished = false;
    try {
      String result = handler.executeProposal(username, proposal);
      finished = emailFilterProposalStorage.finish(id, username, EmailFilterProposal.DONE, result, null);
    } catch (Exception e) { // NOSONAR whatever the tool did, the row says how it ended
      LOG.info("The tool '{}' proposed by a mail filter's assistant failed for user {}: {}",
               proposal.getToolName(),
               username,
               e.getMessage());
      LOG.debug("The failure of the proposal {}", id, e);
      finished = emailFilterProposalStorage.finish(id,
                                                   username,
                                                   EmailFilterProposal.FAILED,
                                                   null,
                                                   StringUtils.defaultIfBlank(e.getMessage(), TOOL_FAILED));
    } finally {
      if (!finished) {
        // An Error, or a write that failed: the row never stays RUNNING.
        emailFilterProposalStorage.finish(id, username, EmailFilterProposal.FAILED, null, INTERRUPTED);
      }
      refreshDigest(username);
    }
    return reread(username, id);
  }

  /**
   * Rejects a proposal of the caller's: it never runs.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the proposal
   * @return the proposal, {@code REJECTED}
   * @throws ObjectNotFoundException as {@link #approve}
   * @throws IllegalAccessException as {@link #approve}
   * @throws IllegalStateException as {@link #approve}
   */
  public EmailFilterProposal reject(String username, Long delegationId, long id) throws ObjectNotFoundException,
                                                                                 IllegalAccessException {
    claim(username, delegationId, id, EmailFilterProposal.REJECTED);
    refreshDigest(username);
    return reread(username, id);
  }

  /**
   * Hands a proposal of the caller's to the AI chat, where the caller goes on: it never
   * runs from here again, whatever the chat does.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the proposal
   * @return the proposal, {@code HANDED_OVER}
   * @throws ObjectNotFoundException as {@link #approve}
   * @throws IllegalAccessException as {@link #approve}
   * @throws IllegalStateException as {@link #approve}
   */
  public EmailFilterProposal handOver(String username, Long delegationId, long id) throws ObjectNotFoundException,
                                                                                   IllegalAccessException {
    claim(username, delegationId, id, EmailFilterProposal.HANDED_OVER);
    refreshDigest(username);
    return reread(username, id);
  }

  /**
   * Tells the owner how many suggestions wait, once a run of their assistant proposed
   * some: the one digest notification ({@link EmailFilterSuggestionDigest#publish}),
   * never one per suggestion. The run's write, after its proposals are recorded.
   *
   * @param username the owner
   */
  public void notifyWaiting(String username) {
    suggestionDigest.publish(username, countWaiting(username));
  }

  /**
   * Brings the owner's digest to the count that waits now, when suggestions stopped
   * waiting without a decision -- a new run of the assistant superseded them and proposed
   * nothing. Nothing is sent; the digest is removed when nothing waits. Never throws.
   *
   * @param username the owner
   */
  public void refreshWaiting(String username) {
    try {
      suggestionDigest.recount(username, countWaiting(username));
    } catch (RuntimeException e) {
      LOG.warn("The digest of the waiting suggestions of user {} could not be recounted", username, e);
    }
  }

  /**
   * What the caller decided on each of their rules' suggestions: per rule, how many were
   * approved, rejected, expired unanswered, continued in the chat, and how many wait --
   * the numbers that tell whether the assistant is worth running on more mail. Kept as
   * long as the rules' log (their matches' retention).
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @return the counts, one per rule that has any
   * @throws ObjectNotFoundException when the feature is off or no mailbox is connected
   * @throws IllegalAccessException when the request comes from someone else's mailbox, or
   *           the caller may not use their connector
   */
  public List<EmailFilterSuggestionCounts> getSuggestionCounts(String username, Long delegationId) throws ObjectNotFoundException,
                                                                                                  IllegalAccessException {
    emailFilterService.checkOwnMailbox(username, delegationId);
    expireDue(username, new Date(clock.millis()));
    return emailFilterProposalStorage.countByFilter(username);
  }

  /**
   * How many of the owner's suggestions wait, the expired ones marked so first. Its
   * callers write the digest with the count themselves, so the expiry here does not.
   *
   * @param username the owner
   * @return the count
   */
  private long countWaiting(String username) {
    emailFilterProposalStorage.expireDue(username, new Date(clock.millis()));
    return emailFilterProposalStorage.countByStatus(username, EmailFilterProposal.PROPOSED);
  }

  /**
   * One row of the "Suggestions" view, out of a cached copy of the mail.
   *
   * @param email the cached copy, as the light listed read gives it
   * @param waitingCount how many suggestions wait on the mail
   * @return the row
   */
  private static EmailWaitingSuggestionMail waitingMail(Email email, int waitingCount) {
    EmailWaitingSuggestionMail mail = new EmailWaitingSuggestionMail();
    mail.setEmailId(email.getId());
    mail.setMailRemoteId(email.getMailRemoteId());
    mail.setFolder(email.getFolder());
    mail.setMailHeaderId(email.getMailHeaderId());
    mail.setThreadId(email.getThreadId());
    mail.setSubject(email.getSubject());
    mail.setSender(email.getSender());
    mail.setReceivedDate(email.getReceivedDate());
    mail.setRead(email.isRead());
    mail.setStarred(email.isStarred());
    mail.setWaitingCount(waitingCount);
    return mail;
  }

  /**
   * Expires the owner's proposals past their date and, when some were, brings the digest
   * to the count that still waits: a digest never tells suggestions that expired.
   *
   * @param username the owner
   * @param now the time
   * @return how many were expired
   */
  private int expireDue(String username, Date now) {
    int expired = emailFilterProposalStorage.expireDue(username, now);
    if (expired > 0) {
      try {
        suggestionDigest.recount(username, emailFilterProposalStorage.countByStatus(username, EmailFilterProposal.PROPOSED));
      } catch (RuntimeException e) {
        LOG.warn("The digest of the waiting suggestions of user {} could not be recounted", username, e);
      }
    }
    return expired;
  }

  /**
   * Brings the owner's digest to what waits now, after a decision. Never throws.
   *
   * @param username the owner
   */
  private void refreshDigest(String username) {
    try {
      suggestionDigest.refresh(username, countWaiting(username));
    } catch (RuntimeException e) {
      LOG.warn("The digest of the waiting suggestions of user {} could not be refreshed", username, e);
    }
  }

  /**
   * Moves a waiting proposal of the caller's to a decision, once: checks, in order, the
   * caller's mailbox, that the proposal exists, that it is theirs, then claims it.
   *
   * @param username the caller
   * @param delegationId the share the request was made from
   * @param id the proposal
   * @param to the decision's status
   * @return the proposal as it was before the claim
   * @throws ObjectNotFoundException when there is no such proposal
   * @throws IllegalAccessException when it is not the caller's
   * @throws IllegalStateException when it no longer waits
   */
  private EmailFilterProposal claim(String username, Long delegationId, long id, String to) throws ObjectNotFoundException,
                                                                                            IllegalAccessException {
    emailFilterService.checkOwnMailbox(username, delegationId);
    if (!emailFilterProposalStorage.exists(id)) {
      throw new ObjectNotFoundException(NOT_FOUND);
    }
    EmailFilterProposal proposal = emailFilterProposalStorage.get(id, username)
                                                             .orElseThrow(() -> new IllegalAccessException(NOT_YOURS));
    Date now = new Date(clock.millis());
    if (!emailFilterProposalStorage.claim(id, username, EmailFilterProposal.PROPOSED, to, now)) {
      expireDue(username, now);
      EmailFilterProposal current = reread(username, id);
      throw new IllegalStateException(EmailFilterProposal.EXPIRED.equals(current.getStatus()) ? EXPIRED : NOT_PENDING);
    }
    return proposal;
  }

  /**
   * Refuses one more call past the caps of the run or of the mailbox.
   *
   * @param username the owner
   * @param matchId the match
   * @param conversationId the run's conversation
   * @throws IllegalStateException {@value #RUN_CAP} or {@value #PENDING_CAP}
   */
  private void checkCaps(String username, long matchId, String conversationId) {
    if (emailFilterProposalStorage.countByRun(username, matchId, StringUtils.left(conversationId, 64))
        >= intProperty(MAX_PER_RUN_PROPERTY, DEFAULT_MAX_PER_RUN)) {
      throw new IllegalStateException(RUN_CAP);
    }
    expireDue(username, new Date(clock.millis()));
    if (emailFilterProposalStorage.countByStatus(username, EmailFilterProposal.PROPOSED)
        >= intProperty(MAX_PENDING_PROPERTY, DEFAULT_MAX_PENDING)) {
      throw new IllegalStateException(PENDING_CAP);
    }
  }

  /**
   * A proposal of the owner's, read again.
   *
   * @param username the owner
   * @param id the proposal
   * @return the proposal
   * @throws ObjectNotFoundException when it vanished
   */
  private EmailFilterProposal reread(String username, long id) throws ObjectNotFoundException {
    return emailFilterProposalStorage.get(id, username).orElseThrow(() -> new ObjectNotFoundException(NOT_FOUND));
  }

  /**
   * Whether a proposal was set aside by a later run of its assistant.
   *
   * @param proposal the proposal
   * @return true for an expired one with that reason
   */
  private static boolean isSuperseded(EmailFilterProposal proposal) {
    return EmailFilterProposal.EXPIRED.equals(proposal.getStatus()) && EmailFilterProposal.SUPERSEDED.equals(proposal.getLastError());
  }

  /**
   * How many days a call waits ({@value #TTL_DAYS_PROPERTY}).
   *
   * @return at least 1
   */
  private static int ttlDays() {
    return Math.max(1, intProperty(TTL_DAYS_PROPERTY, DEFAULT_TTL_DAYS));
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
   * Replaces the clock, for tests.
   *
   * @param clock the clock
   */
  void setClock(Clock clock) {
    this.clock = clock;
  }
}
