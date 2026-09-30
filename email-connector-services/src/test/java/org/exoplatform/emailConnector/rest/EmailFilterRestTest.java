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
package org.exoplatform.emailConnector.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.FilterApplyReport;
import org.exoplatform.emailConnector.model.FilterPreview;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRulesSettings;
import org.exoplatform.emailConnector.service.EmailFilterService;
import org.exoplatform.emailConnector.service.EmailServerRuleService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;

import jakarta.servlet.Filter;
import lombok.SneakyThrows;

/**
 * The user's mail filters, both the server group ({@code /server}, live reads and writes
 * on the caller's own mailbox) and the eXo group (stored, addressed by id): every handler
 * answers, and every refusal the two dispatch helpers -- {@code read}, {@code write} --
 * map to its status.
 */
@SpringBootTest(classes = { EmailFilterRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class EmailFilterRestTest {

  private static final String   FILTERS_PATH = "/email-box/filters"; // NOSONAR

  private static final String   SIMPLE_USER  = "simple";

  static final ObjectMapper     OBJECT_MAPPER;

  static {
    // Workaround when Jackson is defined in shared library with different
    // version and without artifact jackson-datatype-jsr310
    OBJECT_MAPPER = JsonMapper.builder()
                              .configure(JsonReadFeature.ALLOW_MISSING_VALUES, true)
                              .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
                              .build();
    OBJECT_MAPPER.registerModule(new JavaTimeModule());
  }

  @MockitoBean
  private EmailServerRuleService emailServerRuleService;

  @MockitoBean
  private EmailFilterService     emailFilterService;

  @Autowired
  private SecurityFilterChain    filterChain;

  @Autowired
  private WebApplicationContext  context;

  private MockMvc                mockMvc;

  /**
   * The slice's MockMvc, with the security filters the security auto-configuration
   * declared.
   */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain.getFilters().toArray(new Filter[0])).build();
  }

  /**
   * The capabilities probe answers what it reads live, and maps every refusal the
   * service may answer.
   */
  @Test
  void capabilitiesAnswersOkOrTheProbesRefusal() throws Exception {
    // doReturn/doThrow, never when(mock.call()).thenThrow(...): once a stub throws,
    // evaluating the same call again to define the NEXT stub replays that throw instead
    // of just recording the invocation.
    doReturn(capabilities()).when(emailServerRuleService).getCapabilities(anyString(), any());
    mockMvc.perform(get(FILTERS_PATH + "/capabilities").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.supported").value(true));

    doThrow(new ObjectNotFoundException("emailConnector.rules.disabled")).when(emailServerRuleService).getCapabilities(anyString(), any());
    mockMvc.perform(get(FILTERS_PATH + "/capabilities").with(testSimpleUser())).andExpect(status().isNotFound());

    doThrow(new IllegalAccessException("emailConnector.rules.ownMailboxOnly")).when(emailServerRuleService)
                                                                              .getCapabilities(anyString(), any());
    mockMvc.perform(get(FILTERS_PATH + "/capabilities?delegationId=9").with(testSimpleUser())).andExpect(status().isForbidden());

    doThrow(new ServerRuleUnavailableException(ServerRuleUnavailableException.SERVER_UNREACHABLE)).when(emailServerRuleService)
                                                                                                   .getCapabilities(anyString(), any());
    mockMvc.perform(get(FILTERS_PATH + "/capabilities").with(testSimpleUser())).andExpect(status().isBadGateway());
  }

  /**
   * The server group's own read, a live probe of the mail server.
   */
  @Test
  void getServerRulesAnswersTheLiveGroup() throws Exception {
    ServerRulesSettings settings = new ServerRulesSettings();
    settings.setCapabilities(capabilities());
    when(emailServerRuleService.getServerRules(anyString(), any())).thenReturn(settings);

    mockMvc.perform(get(FILTERS_PATH + "/server").with(testSimpleUser())).andExpect(status().isOk());
  }

  /**
   * Creating a server rule answers the group after the write, and every refusal --
   * invalid, unsupported, forbidden, not found, a conflict naming the script, or the
   * server unavailable -- with its own status.
   */
  @Test
  void createServerRuleAnswersOkOrEveryRefusal() throws Exception {
    doReturn(new ServerRulesSettings()).when(emailServerRuleService)
                                       .saveRule(anyString(), any(), isNull(), any(), anyBoolean(), anyBoolean());
    mockMvc.perform(post(FILTERS_PATH + "/server").with(testSimpleUser())
                                                  .content(asJsonString(serverRule()))
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());

    doThrow(new IllegalArgumentException("emailConnector.rules.invalid")).when(emailServerRuleService)
                                                                         .saveRule(anyString(), any(), isNull(), any(), anyBoolean(),
                                                                                   anyBoolean());
    mockMvc.perform(post(FILTERS_PATH + "/server").with(testSimpleUser())
                                                  .content(asJsonString(serverRule()))
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());

    doThrow(new ServerRuleUnsupportedException(ServerRuleUnsupportedException.RULES_UNSUPPORTED)).when(emailServerRuleService)
                                                                                                  .saveRule(anyString(), any(), isNull(),
                                                                                                            any(), anyBoolean(), anyBoolean());
    mockMvc.perform(post(FILTERS_PATH + "/server").with(testSimpleUser())
                                                  .content(asJsonString(serverRule()))
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());

    doThrow(new IllegalAccessException("emailConnector.rules.ownMailboxOnly")).when(emailServerRuleService)
                                                                              .saveRule(anyString(), any(), isNull(), any(), anyBoolean(),
                                                                                        anyBoolean());
    mockMvc.perform(post(FILTERS_PATH + "/server").with(testSimpleUser())
                                                  .content(asJsonString(serverRule()))
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isForbidden());

    doThrow(new ObjectNotFoundException("emailConnector.rules.disabled")).when(emailServerRuleService)
                                                                         .saveRule(anyString(), any(), isNull(), any(), anyBoolean(),
                                                                                   anyBoolean());
    mockMvc.perform(post(FILTERS_PATH + "/server").with(testSimpleUser())
                                                  .content(asJsonString(serverRule()))
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound());

    doThrow(new ServerRuleConflictException(ServerRuleConflictException.SERVER_CONFLICT,
                                            "Vacation.sieve")).when(emailServerRuleService)
                                                              .saveRule(anyString(), any(), isNull(), any(), anyBoolean(), anyBoolean());
    mockMvc.perform(post(FILTERS_PATH + "/server").with(testSimpleUser())
                                                  .content(asJsonString(serverRule()))
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message").value(ServerRuleConflictException.SERVER_CONFLICT))
           .andExpect(jsonPath("$.scriptName").value("Vacation.sieve"));

    doThrow(new ServerRuleUnavailableException(ServerRuleUnavailableException.SERVER_UNREACHABLE)).when(emailServerRuleService)
                                                                                                   .saveRule(anyString(), any(), isNull(),
                                                                                                             any(), anyBoolean(),
                                                                                                             anyBoolean());
    mockMvc.perform(post(FILTERS_PATH + "/server").with(testSimpleUser())
                                                  .content(asJsonString(serverRule()))
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadGateway());
  }

  /**
   * Replacing a server rule reaches the service with the reference from the path.
   */
  @Test
  void updateServerRuleAnswersOk() throws Exception {
    when(emailServerRuleService.saveRule(anyString(), any(), eq("srv-1"), any(), anyBoolean(), anyBoolean())).thenReturn(new ServerRulesSettings());

    mockMvc.perform(put(FILTERS_PATH + "/server/srv-1").with(testSimpleUser())
                                                        .content(asJsonString(serverRule()))
                                                        .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
  }

  /**
   * Deleting a server rule reaches the service with the reference from the path.
   */
  @Test
  void deleteServerRuleAnswersOk() throws Exception {
    when(emailServerRuleService.deleteRule(anyString(), any(), eq("srv-1"), anyBoolean())).thenReturn(new ServerRulesSettings());

    mockMvc.perform(delete(FILTERS_PATH + "/server/srv-1").with(testSimpleUser())).andExpect(status().isOk());
  }

  /**
   * Publishing the server rules again reaches the service.
   */
  @Test
  void publishServerRulesAnswersOk() throws Exception {
    when(emailServerRuleService.publishRules(anyString(), any(), anyBoolean())).thenReturn(new ServerRulesSettings());

    mockMvc.perform(post(FILTERS_PATH + "/server/publish").with(testSimpleUser())).andExpect(status().isOk());
  }

  /**
   * The eXo group's read, and every refusal the {@code read} dispatcher maps: not found,
   * forbidden, and an invalid argument answered as bad request.
   */
  @Test
  void getFiltersAnswersOkOrEveryReadRefusal() throws Exception {
    doReturn(List.of(filter())).when(emailFilterService).getFilters(anyString(), any());
    mockMvc.perform(get(FILTERS_PATH).with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("Acme"));

    doThrow(new ObjectNotFoundException(EmailFilterService.DISABLED)).when(emailFilterService).getFilters(anyString(), any());
    mockMvc.perform(get(FILTERS_PATH).with(testSimpleUser())).andExpect(status().isNotFound());

    doThrow(new IllegalAccessException("emailConnector.rules.ownMailboxOnly")).when(emailFilterService).getFilters(anyString(), any());
    mockMvc.perform(get(FILTERS_PATH + "?delegationId=9").with(testSimpleUser())).andExpect(status().isForbidden());
  }

  /**
   * Creating an eXo filter answers the rule as stored.
   */
  @Test
  void createFilterAnswersOk() throws Exception {
    when(emailFilterService.createFilter(anyString(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(filter());

    mockMvc.perform(post(FILTERS_PATH).with(testSimpleUser())
                                      .content(asJsonString(filter()))
                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.name").value("Acme"));
  }

  /**
   * The drawer's one entry point answers the filter as saved, wherever it runs.
   */
  @Test
  void saveRoutedFilterAnswersOk() throws Exception {
    when(emailFilterService.saveRouted(anyString(), any(), any(), any(), any(), anyBoolean(), anyBoolean())).thenReturn(filter());

    mockMvc.perform(post(FILTERS_PATH + "/routed").with(testSimpleUser())
                                                  .content(asJsonString(filter()))
                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
  }

  /**
   * Replacing an eXo filter reaches the service with the id from the path.
   */
  @Test
  void updateFilterAnswersOk() throws Exception {
    when(emailFilterService.updateFilter(anyString(), any(), eq(12L), any(), anyBoolean(), anyBoolean())).thenReturn(filter());

    mockMvc.perform(put(FILTERS_PATH + "/12").with(testSimpleUser())
                                             .content(asJsonString(filter()))
                                             .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
  }

  /**
   * A successful delete answers 204 with no body; a conflict is answered as the write
   * dispatcher maps it, not mistaken for a success.
   */
  @Test
  void deleteFilterAnswersNoContentOrTheConflict() throws Exception {
    mockMvc.perform(delete(FILTERS_PATH + "/12").with(testSimpleUser())).andExpect(status().isNoContent());

    doThrow(new ServerRuleConflictException(ServerRuleConflictException.MODIFIED_OUTSIDE, "Filters.sieve")).when(emailFilterService)
                                                                                                            .deleteFilter(anyString(), any(),
                                                                                                                          eq(13L),
                                                                                                                          anyBoolean());
    mockMvc.perform(delete(FILTERS_PATH + "/13").with(testSimpleUser()))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.scriptName").value("Filters.sieve"));
  }

  /**
   * Ordering the eXo rules answers them in their new order.
   */
  @Test
  void reorderFiltersAnswersTheNewOrder() throws Exception {
    when(emailFilterService.reorder(anyString(), any(), any())).thenReturn(List.of(filter()));

    mockMvc.perform(put(FILTERS_PATH + "/order").with(testSimpleUser())
                                                .content(asJsonString(List.of(12L, 7L)))
                                                .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].name").value("Acme"));
  }

  /**
   * Previewing a rule answers the sample, and an invalid draft is a bad request -- the
   * {@code read} dispatcher's one refusal not shared with {@code getFilters}.
   */
  @Test
  void previewFilterAnswersTheSampleOrRefusesAnInvalidDraft() throws Exception {
    doReturn(new FilterPreview(3, 3, List.of(), List.of(), false)).when(emailFilterService).preview(anyString(), any(), any());
    mockMvc.perform(post(FILTERS_PATH + "/preview").with(testSimpleUser())
                                                    .content(asJsonString(filter()))
                                                    .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.total").value(3));

    doThrow(new IllegalArgumentException(EmailFilterService.INVALID_KIND)).when(emailFilterService).preview(anyString(), any(), any());
    mockMvc.perform(post(FILTERS_PATH + "/preview").with(testSimpleUser())
                                                    .content(asJsonString(filter()))
                                                    .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }

  /**
   * Applying a rule once answers what the pass did.
   */
  @Test
  void applyFilterAnswersTheReport() throws Exception {
    when(emailFilterService.applyOnce(anyString(), any(), eq(12L), anyBoolean())).thenReturn(new FilterApplyReport(5, 2, 1, 0, 2));

    mockMvc.perform(post(FILTERS_PATH + "/12/apply").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.matched").value(2));
  }

  /**
   * Re-publishing a rule's server half reaches the service with the rule's id.
   */
  @Test
  void republishFilterAnswersOk() throws Exception {
    when(emailFilterService.republishHop(anyString(), any(), eq(12L), anyBoolean(), anyBoolean())).thenReturn(filter());

    mockMvc.perform(post(FILTERS_PATH + "/12/republish").with(testSimpleUser())).andExpect(status().isOk());
  }

  /**
   * A rule's log answers its newest matches.
   */
  @Test
  void getFilterLogAnswersTheMatches() throws Exception {
    when(emailFilterService.getLog(anyString(), any(), eq(12L), eq(100))).thenReturn(List.of(match()));

    mockMvc.perform(get(FILTERS_PATH + "/12/log").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].folder").value("Archive"));
  }

  /**
   * A mail's Automations panel answers what the caller's rules did to it.
   */
  @Test
  void getMailMatchesAnswersTheMailsMatches() throws Exception {
    when(emailFilterService.getMatchesOfMail(anyString(), any(), eq(42L))).thenReturn(List.of(match()));

    mockMvc.perform(get(FILTERS_PATH + "/mail/42").with(testSimpleUser())).andExpect(status().isOk());
  }

  /**
   * Undoing a match answers it afterwards.
   */
  @Test
  void undoMatchAnswersTheMatchAfterTheUndo() throws Exception {
    when(emailFilterService.undo(anyString(), any(), eq(5L), eq("STAR"))).thenReturn(match());

    mockMvc.perform(post(FILTERS_PATH + "/matches/5/undo?action=STAR").with(testSimpleUser())).andExpect(status().isOk());
  }

  /**
   * Retrying a match's assistant answers it queued again.
   */
  @Test
  void retryMatchAnswersTheMatchQueued() throws Exception {
    when(emailFilterService.retry(anyString(), any(), eq(5L))).thenReturn(match());

    mockMvc.perform(post(FILTERS_PATH + "/matches/5/retry").with(testSimpleUser())).andExpect(status().isOk());
  }

  /**
   * The authenticated simple user every call acts as.
   *
   * @return the request post-processor
   */
  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password("password").authorities(new SimpleGrantedAuthority("users"));
  }

  /**
   * The capabilities of a server that supports everything, the fixed vocabulary.
   *
   * @return the capabilities
   */
  private ServerRuleCapabilities capabilities() {
    return new ServerRuleCapabilities(true, null, false, false, ServerRuleCapabilities.VocabularySource.FIXED, Map.of());
  }

  /**
   * A minimal server rule payload.
   *
   * @return the rule
   */
  private ServerRule serverRule() {
    return new ServerRule(null, "Vacation", true, true, List.of(), List.of(), false);
  }

  /**
   * A minimal eXo filter, as the drawer would submit or answer it.
   *
   * @return the filter
   */
  private EmailFilter filter() {
    EmailFilter filter = new EmailFilter();
    filter.setId(12L);
    filter.setName("Acme");
    filter.setEnabled(true);
    filter.setKind(EmailFilter.KIND_EXO);
    filter.setMailboxScope(EmailFilter.SCOPE_OWN);
    filter.setMatchAll(true);
    filter.setConditions(List.of());
    filter.setActions(List.of());
    return filter;
  }

  /**
   * A match, as the log or the mail's panel would answer it.
   *
   * @return the match
   */
  private EmailFilterMatch match() {
    EmailFilterMatch match = new EmailFilterMatch();
    match.setId(5L);
    match.setFilterId(12L);
    match.setMailHeaderId("<one@acme.com>");
    match.setFolder("Archive");
    match.setActions(List.of());
    match.setAgentStatus(EmailFilterMatch.AGENT_NONE);
    return match;
  }

  /**
   * Serializes a payload the way the client would.
   *
   * @param obj the payload
   * @return its JSON
   */
  @SneakyThrows
  private String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }
}
