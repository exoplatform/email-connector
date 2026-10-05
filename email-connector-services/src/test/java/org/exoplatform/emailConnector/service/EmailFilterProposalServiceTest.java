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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.EmailFilterMatchKey;
import org.exoplatform.emailConnector.model.EmailFilterProposal;
import org.exoplatform.emailConnector.model.EmailFilterProposalCount;
import org.exoplatform.emailConnector.model.EmailFilterSuggestionCounts;
import org.exoplatform.emailConnector.model.EmailWaitingSuggestionMail;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.plugin.EmailFilterProposalProvider;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailFilterProposalStorage;
import org.exoplatform.emailConnector.storage.EmailFilterStorage;

/**
 * The suggestions' REST and views over the AI add-on's shared proposals (EXO-90956):
 * every request checked as the caller's own, from their own mailbox, before the provider
 * is asked; the suggestions joined with the caller's own matches only; nothing suggested
 * and every decision refused when no provider is declared.
 */
@ExtendWith(MockitoExtension.class)
public class EmailFilterProposalServiceTest {

  private static final String                         OWNER = "alice";

  @Mock
  private EmailFilterProposalStorage                  emailFilterProposalStorage;

  @Mock
  private EmailFilterStorage                          emailFilterStorage;

  @Mock
  private EmailFilterService                          emailFilterService;

  @Mock
  private ObjectProvider<EmailFilterProposalProvider> proposalProviders;

  @Mock
  private EmailFilterProposalProvider                 provider;

  @Mock
  private EmailBoxStorage                             emailBoxStorage;

  @Mock
  private EmailDelegationService                      emailDelegationService;

  @InjectMocks
  private EmailFilterProposalService                  service;

  /**
   * A provider declared.
   */
  @BeforeEach
  void setUp() {
    lenient().when(proposalProviders.orderedStream()).thenAnswer(invocation -> Stream.of(provider));
  }

  /**
   * Each decision is the caller's, from their own mailbox: a delegation is refused
   * before the provider is asked; the owner is the request's user, with the
   * "don't ask again" choice passed on. Mutant: the mailbox check removed from approve.
   *
   * @throws Exception never
   */
  @Test
  void decisionsAreTheCallersFromTheirOwnMailbox() throws Exception {
    doThrow(new IllegalAccessException("emailConnector.rules.ownMailboxOnly")).when(emailFilterService).checkOwnMailbox(OWNER, 12L);
    assertThrows(IllegalAccessException.class, () -> service.approve(OWNER, 12L, 5L, true));
    assertThrows(IllegalAccessException.class, () -> service.reject(OWNER, 12L, 5L));
    assertThrows(IllegalAccessException.class, () -> service.handOver(OWNER, 12L, 5L));
    verifyNoInteractions(provider);

    EmailFilterProposal done = proposal(5L, 7L, EmailFilterProposal.DONE);
    when(provider.approve(OWNER, 5L, true)).thenReturn(done);
    assertSame(done, service.approve(OWNER, null, 5L, true));
    when(provider.approve(OWNER, 6L, false)).thenReturn(done);
    assertSame(done, service.approve(OWNER, null, 6L));
    service.reject(OWNER, null, 5L);
    verify(provider).reject(OWNER, 5L);
    service.handOver(OWNER, null, 5L);
    verify(provider).handOver(OWNER, 5L);
  }

  /**
   * Without a provider nothing is suggested and every decision is refused, 409 at the
   * REST.
   *
   * @throws Exception never
   */
  @Test
  void withoutAProviderNothingIsSuggested() throws Exception {
    when(proposalProviders.orderedStream()).thenAnswer(invocation -> Stream.empty());
    assertEquals(List.of(), service.getProposalsOfMatches(OWNER, List.of(7L)));
    assertEquals(List.of(), service.getWaitingMails(OWNER, null));
    assertEquals(List.of(), service.getWaitingEmails(OWNER, null));
    assertEquals(List.of(), service.getSuggestionCounts(OWNER, null));
    assertEquals(EmailFilterProposalService.UNAVAILABLE,
                 assertThrows(IllegalStateException.class, () -> service.approve(OWNER, null, 5L)).getMessage());
    assertEquals(EmailFilterProposalService.UNAVAILABLE,
                 assertThrows(IllegalStateException.class, () -> service.reject(OWNER, null, 5L)).getMessage());
  }

  /**
   * A match's cards are the suggestions of the caller's own matches, each with its rule;
   * a suggestion whose match isn't the caller's is never shown.
   */
  @Test
  void theCardsAreThoseOfTheCallersOwnMatches() {
    when(emailFilterStorage.getMatchKeys(OWNER, List.of(7L, 8L))).thenReturn(Map.of(7L, new EmailFilterMatchKey(3L, "<a@x>")));
    when(provider.getProposalsOfMatches(eq(OWNER), any())).thenReturn(List.of(proposal(1L, 7L, EmailFilterProposal.PROPOSED),
                                                                              proposal(2L, 8L, EmailFilterProposal.PROPOSED)));
    EmailFilterMatch mine = new EmailFilterMatch();
    mine.setId(7L);
    EmailFilterMatch other = new EmailFilterMatch();
    other.setId(8L);

    List<EmailFilterMatch> matches = service.withProposals(OWNER, List.of(mine, other));

    assertEquals(List.of(1L), matches.get(0).getProposals().stream().map(EmailFilterProposal::getId).toList());
    assertEquals(3L, matches.get(0).getProposals().get(0).getFilterId());
    assertEquals(List.of(), matches.get(1).getProposals(), "a match not the caller's shows nothing");
    verify(provider).getProposalsOfMatches(OWNER, Set.of(7L));
    assertEquals(List.of(), service.getProposalsOfMatches(" ", List.of(7L)));
  }

  /**
   * The "Suggestions" view lists one row per mail with its waiting count, from the
   * caller's matches with a suggestion waiting; a delegation is refused before anything
   * is read.
   *
   * @throws Exception never
   */
  @Test
  void theWaitingEmailsAreOneRowPerMailWithItsWaitingCount() throws Exception {
    when(provider.getWaitingMatchIds(OWNER)).thenReturn(List.of(7L, 8L, 7L, 9L));
    when(emailFilterStorage.getMatchKeys(OWNER, List.of(7L, 8L, 7L, 9L))).thenReturn(Map.of(7L,
                                                                                         new EmailFilterMatchKey(3L, "<a@x>"),
                                                                                         8L,
                                                                                         new EmailFilterMatchKey(3L, "<b@x>")));
    when(emailDelegationService.getDelegatedFolderKeys(OWNER)).thenReturn(List.of("CUSTOM:9"));
    when(emailBoxStorage.getListedEmailsByMailHeaderIds(eq(OWNER), any(), any())).thenReturn(List.of(listed(11L, "<b@x>"),
                                                                                                     listed(12L, "<a@x>"),
                                                                                                     listed(13L, "<a@x>")));

    List<EmailWaitingSuggestionMail> mails = service.getWaitingEmails(OWNER, null);

    assertEquals(List.of(11L, 12L), mails.stream().map(EmailWaitingSuggestionMail::getEmailId).toList());
    assertEquals(1, mails.get(0).getWaitingCount());
    assertEquals(2, mails.get(1).getWaitingCount(), "two suggestions wait on <a@x>");
    verify(emailBoxStorage).getListedEmailsByMailHeaderIds(OWNER,
                                                           Set.of("<a@x>", "<b@x>"),
                                                           MailFolder.notFavoritedFolders(List.of("CUSTOM:9")));
    assertEquals(Set.of("<a@x>", "<b@x>"), Set.copyOf(service.getWaitingMails(OWNER, null)));

    doThrow(new IllegalAccessException("emailConnector.rules.ownMailboxOnly")).when(emailFilterService).checkOwnMailbox(OWNER, 12L);
    assertThrows(IllegalAccessException.class, () -> service.getWaitingEmails(OWNER, 12L));
    assertThrows(IllegalAccessException.class, () -> service.getWaitingMails(OWNER, 12L));
  }

  /**
   * The counts per rule add up their matches' suggestions by status: approved (running,
   * done or failed), rejected, expired, handed over, waiting; a superseded suggestion or
   * an action run on its own counts as none, another user's match as nothing.
   *
   * @throws Exception never
   */
  @Test
  void theCountsPerRule() throws Exception {
    when(provider.countByMatch(OWNER)).thenReturn(List.of(new EmailFilterProposalCount(7L, "DONE", 2),
                                                          new EmailFilterProposalCount(7L, "FAILED", 1),
                                                          new EmailFilterProposalCount(8L, "REJECTED", 1),
                                                          new EmailFilterProposalCount(8L, "SUPERSEDED", 4),
                                                          new EmailFilterProposalCount(8L, "EXECUTED", 3),
                                                          new EmailFilterProposalCount(8L, "PROPOSED", 2),
                                                          new EmailFilterProposalCount(9L, "PROPOSED", 5)));
    when(emailFilterStorage.getMatchKeys(eq(OWNER), any())).thenReturn(Map.of(7L, new EmailFilterMatchKey(3L, "<a@x>"), 8L, new EmailFilterMatchKey(4L, "<b@x>")));

    assertEquals(List.of(new EmailFilterSuggestionCounts(3L, 3, 0, 0, 0, 0), new EmailFilterSuggestionCounts(4L, 0, 1, 0, 0, 2)),
                 service.getSuggestionCounts(OWNER, null));
    verify(emailFilterService).checkOwnMailbox(OWNER, null);
  }

  /**
   * The former store is read by pages for the move, as the storage gives them.
   *
   * @throws Exception never
   */
  @Test
  void theFormerStoreIsReadForTheMove() throws Exception {
    service.getStoredProposals(5L, 100);
    verify(emailFilterProposalStorage).getPage(5L, 100);
    verify(provider, never()).approve(anyString(), anyLong(), any(Boolean.class));
  }

  /**
   * @param id the suggestion
   * @param matchId its match
   * @param status its status
   * @return the suggestion
   */
  private static EmailFilterProposal proposal(long id, long matchId, String status) {
    EmailFilterProposal proposal = new EmailFilterProposal();
    proposal.setId(id);
    proposal.setMatchId(matchId);
    proposal.setStatus(status);
    return proposal;
  }

  /**
   * A light listed copy of a message, the newest first in the order given.
   *
   * @param id the cached row's id
   * @param mailHeaderId its Message-ID
   * @return the copy
   */
  private static Email listed(long id, String mailHeaderId) {
    Email email = new Email();
    email.setId(id);
    email.setMailRemoteId(id + 1000);
    email.setFolder(MailFolder.INBOX);
    email.setMailHeaderId(mailHeaderId);
    email.setSubject("Subject " + id);
    email.setReceivedDate(new Date(id));
    return email;
  }
}
