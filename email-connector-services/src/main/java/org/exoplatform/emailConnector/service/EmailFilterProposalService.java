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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.EmailFilterMatchKey;
import org.exoplatform.emailConnector.model.EmailFilterProposal;
import org.exoplatform.emailConnector.model.EmailFilterProposalCount;
import org.exoplatform.emailConnector.model.EmailFilterSuggestionCounts;
import org.exoplatform.emailConnector.model.EmailWaitingSuggestionMail;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.StoredFilterProposal;
import org.exoplatform.emailConnector.plugin.EmailFilterProposalProvider;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailFilterProposalStorage;
import org.exoplatform.emailConnector.storage.EmailFilterStorage;

/**
 * The tool calls a mail filter's assistant proposed instead of running them, and the
 * owner's decision on each: approve, reject, or hand it to the AI chat.
 * <p>
 * <b>Kept by the AI add-on</b> (EXO-90956): the suggestions are the AI add-on's shared
 * proposals of source type {@code email-filter}, one source per match, read and decided
 * through the {@link EmailFilterProposalProvider} its glue declares. This service keeps
 * the email-connector's own REST and views over them: it checks that a request is the
 * caller's own, from their own mailbox, then joins the suggestions with the matches and
 * the mails they are about. Without a provider -- no AI add-on, or its profile off --
 * nothing is suggested: the lists are empty and a decision is refused
 * {@value #UNAVAILABLE}.
 * <p>
 * <b>Decided by its owner only, once</b>, by the provider: the owner is the request's
 * user, never a value the client sends.
 */
@Service
public class EmailFilterProposalService {

  /** The code of a proposal that does not exist. */
  public static final String  NOT_FOUND   = "emailConnector.filters.proposal.notFound";

  /** The code of a proposal that is not the caller's. */
  public static final String  NOT_YOURS   = "emailConnector.filters.proposal.notYours";

  /** The code of a proposal that no longer waits. */
  public static final String  NOT_PENDING = "emailConnector.filters.proposal.notPending";

  /** The code of a proposal past its expiry. */
  public static final String  EXPIRED     = "emailConnector.filters.proposal.expired";

  /** The code of a decision nothing can take: no AI add-on on this deployment. */
  public static final String  UNAVAILABLE = "emailConnector.filters.proposal.unavailable";

  /** The most suggestions the "Suggestions" view counts at once. */
  static final int            MAX_WAITING = 100;

  @Autowired
  private EmailFilterProposalStorage                  emailFilterProposalStorage;

  @Autowired
  private EmailFilterStorage                          emailFilterStorage;

  @Autowired
  private EmailFilterService                          emailFilterService;

  @Autowired
  private ObjectProvider<EmailFilterProposalProvider> proposalProviders;

  @Autowired
  private EmailBoxStorage                             emailBoxStorage;

  @Autowired
  private EmailDelegationService                      emailDelegationService;

  /**
   * The suggestions of some of the owner's matches: the cards of a mail's Automations
   * panel.
   *
   * @param username the owner
   * @param matchIds the matches
   * @return the suggestions, oldest first, each with its rule
   */
  public List<EmailFilterProposal> getProposalsOfMatches(String username, Collection<Long> matchIds) {
    EmailFilterProposalProvider provider = provider();
    if (provider == null || StringUtils.isBlank(username) || matchIds == null || matchIds.isEmpty()) {
      return List.of();
    }
    Map<Long, EmailFilterMatchKey> keys = emailFilterStorage.getMatchKeys(username, matchIds);
    List<EmailFilterProposal> proposals = new ArrayList<>();
    for (EmailFilterProposal proposal : provider.getProposalsOfMatches(username, keys.keySet())) {
      EmailFilterMatchKey key = keys.get(proposal.getMatchId());
      if (key != null) {
        proposal.setFilterId(key.filterId());
        proposals.add(proposal);
      }
    }
    return proposals;
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
    return List.copyOf(waitingByMail(username).keySet());
  }

  /**
   * The caller's mails with a suggestion still waiting for them, as the mailbox's
   * "Suggestions" view lists them (EXO-90851): one cached copy per message, newest first,
   * with how many suggestions wait on it. The copy is one the user can open from a list:
   * never in Trash, Spam, All Mail or Drafts, nor in a mailbox somebody shared with them.
   * A message with no such copy cached is not listed. Of the caller's own mailbox only.
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
    Map<String, Integer> waiting = waitingByMail(username);
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
   * Approves a suggestion of the caller's and runs it, as the caller.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the suggestion
   * @return the suggestion once run
   * @throws ObjectNotFoundException when the feature is off, no mailbox is connected, or
   *           there is no such suggestion
   * @throws IllegalAccessException when the request comes from someone else's mailbox,
   *           the caller may not use their connector, or the suggestion is not theirs
   * @throws IllegalStateException with a message code when it no longer waits, or
   *           {@value #UNAVAILABLE} when nothing can decide it
   */
  public EmailFilterProposal approve(String username, Long delegationId, long id) throws ObjectNotFoundException,
                                                                                  IllegalAccessException {
    return approve(username, delegationId, id, false);
  }

  /**
   * Approves a suggestion of the caller's and runs it, as the caller; with
   * {@code allowForFilter}, its rule may run the tool without asking from now on.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the suggestion
   * @param allowForFilter whether its rule may run the tool without asking from now on
   * @return the suggestion once run
   * @throws ObjectNotFoundException as {@link #approve(String, Long, long)}
   * @throws IllegalAccessException as {@link #approve(String, Long, long)}
   * @throws IllegalStateException as {@link #approve(String, Long, long)}
   */
  public EmailFilterProposal approve(String username,
                                     Long delegationId,
                                     long id,
                                     boolean allowForFilter) throws ObjectNotFoundException, IllegalAccessException {
    emailFilterService.checkOwnMailbox(username, delegationId);
    return requireProvider().approve(username, id, allowForFilter);
  }

  /**
   * Rejects a suggestion of the caller's: it never runs.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the suggestion
   * @return the suggestion, rejected
   * @throws ObjectNotFoundException as {@link #approve(String, Long, long)}
   * @throws IllegalAccessException as {@link #approve(String, Long, long)}
   * @throws IllegalStateException as {@link #approve(String, Long, long)}
   */
  public EmailFilterProposal reject(String username, Long delegationId, long id) throws ObjectNotFoundException,
                                                                                 IllegalAccessException {
    emailFilterService.checkOwnMailbox(username, delegationId);
    return requireProvider().reject(username, id);
  }

  /**
   * Hands a suggestion of the caller's to the AI chat, where the caller goes on: it never
   * runs from here again, whatever the chat does.
   *
   * @param username the caller, from the request's session
   * @param delegationId the share the request was made from; any value is refused
   * @param id the suggestion
   * @return the suggestion, handed over
   * @throws ObjectNotFoundException as {@link #approve(String, Long, long)}
   * @throws IllegalAccessException as {@link #approve(String, Long, long)}
   * @throws IllegalStateException as {@link #approve(String, Long, long)}
   */
  public EmailFilterProposal handOver(String username, Long delegationId, long id) throws ObjectNotFoundException,
                                                                                   IllegalAccessException {
    emailFilterService.checkOwnMailbox(username, delegationId);
    return requireProvider().handOver(username, id);
  }

  /**
   * What the caller decided on each of their rules' suggestions: per rule, how many were
   * approved, rejected, expired unanswered, continued in the chat, and how many wait.
   * Kept as long as the rules' log (their matches' retention); a superseded suggestion
   * and an action run on its own count as none of them.
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
    EmailFilterProposalProvider provider = provider();
    if (provider == null) {
      return List.of();
    }
    List<EmailFilterProposalCount> counts = provider.countByMatch(username);
    Map<Long, EmailFilterMatchKey> keys = emailFilterStorage.getMatchKeys(username, counts.stream().map(EmailFilterProposalCount::matchId).toList());
    Map<Long, Map<String, Long>> byFilter = new TreeMap<>();
    for (EmailFilterProposalCount count : counts) {
      EmailFilterMatchKey key = keys.get(count.matchId());
      if (key != null) {
        byFilter.computeIfAbsent(key.filterId(), filterId -> new HashMap<>()).merge(count.status(), count.count(), Long::sum);
      }
    }
    return byFilter.entrySet().stream().map(entry -> {
      Map<String, Long> byStatus = entry.getValue();
      long approved = byStatus.getOrDefault(EmailFilterProposal.RUNNING, 0L) + byStatus.getOrDefault(EmailFilterProposal.DONE, 0L)
          + byStatus.getOrDefault(EmailFilterProposal.FAILED, 0L);
      return new EmailFilterSuggestionCounts(entry.getKey(),
                                             approved,
                                             byStatus.getOrDefault(EmailFilterProposal.REJECTED, 0L),
                                             byStatus.getOrDefault(EmailFilterProposal.EXPIRED, 0L),
                                             byStatus.getOrDefault(EmailFilterProposal.HANDED_OVER, 0L),
                                             byStatus.getOrDefault(EmailFilterProposal.PROPOSED, 0L));
    }).toList();
  }

  /**
   * A page of the suggestions of the email-connector's former store, every user's, by
   * id: what the move to the AI add-on's shared proposals reads (EXO-90956).
   *
   * @param afterId the id the page starts after, 0 for the first page
   * @param limit the page size
   * @return the suggestions with their owners
   */
  public List<StoredFilterProposal> getStoredProposals(long afterId, int limit) {
    return emailFilterProposalStorage.getPage(afterId, limit);
  }

  /**
   * How many suggestions wait on each of the owner's mails, by Message-ID.
   *
   * @param username the owner
   * @return the counts, by Message-ID
   */
  private Map<String, Integer> waitingByMail(String username) {
    EmailFilterProposalProvider provider = provider();
    if (provider == null) {
      return Map.of();
    }
    List<Long> waitingMatches = provider.getWaitingMatchIds(username);
    if (waitingMatches.isEmpty()) {
      return Map.of();
    }
    Map<Long, EmailFilterMatchKey> keys = emailFilterStorage.getMatchKeys(username, waitingMatches);
    Map<String, Integer> waiting = new LinkedHashMap<>();
    waitingMatches.stream()
                  .limit(MAX_WAITING)
                  .map(keys::get)
                  .filter(Objects::nonNull)
                  .map(EmailFilterMatchKey::mailHeaderId)
                  .filter(StringUtils::isNotBlank)
                  .forEach(mailHeaderId -> waiting.merge(mailHeaderId, 1, Integer::sum));
    return waiting;
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
   * @return the provider the glue declares, null when there is none
   */
  private EmailFilterProposalProvider provider() {
    return proposalProviders == null ? null : proposalProviders.orderedStream().findFirst().orElse(null);
  }

  /**
   * @return the provider the glue declares
   * @throws IllegalStateException {@value #UNAVAILABLE} when there is none
   */
  private EmailFilterProposalProvider requireProvider() {
    EmailFilterProposalProvider provider = provider();
    if (provider == null) {
      throw new IllegalStateException(UNAVAILABLE);
    }
    return provider;
  }

}
