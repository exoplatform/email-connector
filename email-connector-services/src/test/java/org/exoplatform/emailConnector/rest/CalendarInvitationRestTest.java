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
package org.exoplatform.emailConnector.rest;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.SendModeUnavailableException;
import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.service.CalendarInvitationService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;
import jakarta.servlet.Filter;

/**
 * The calendar invitation endpoints (EXO-90840): the caller's own messages only, every
 * refusal mapped to its status, and the codes the reader needs and nothing else.
 */
@SpringBootTest(classes = { CalendarInvitationRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-null-for-primitives=false")
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
class CalendarInvitationRestTest {

  private static final String       PATH = "/email-box/12/invitation";

  private static final String       USER = "simple";

  @MockitoBean
  private CalendarInvitationService calendarInvitationService;

  @Autowired
  private SecurityFilterChain       filterChain;

  @Autowired
  private WebApplicationContext     context;

  private MockMvc                   mockMvc;

  /**
   * MockMvc with the platform's security filters.
   */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain.getFilters().toArray(new Filter[0])).build();
  }

  /**
   * The invitation is read for the caller, and every refusal answers its status, with
   * the code the reader shows.
   *
   * @throws Exception when a request cannot be performed
   */
  @Test
  void readingAnInvitationMapsEveryOutcome() throws Exception {
    CalendarInvitation invitation = new CalendarInvitation();
    invitation.setSummary("<b>Sync</b>");
    invitation.setAnswerable(true);
    when(calendarInvitationService.getInvitation(12L, USER)).thenReturn(invitation);
    mockMvc.perform(get(PATH).with(simpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.summary").value("<b>Sync</b>"))
           .andExpect(jsonPath("$.answerable").value(true));

    when(calendarInvitationService.getInvitation(12L, USER)).thenThrow(new ObjectNotFoundException("whatever"));
    mockMvc.perform(get(PATH).with(simpleUser()))
           .andExpect(status().isNotFound())
           .andExpect(status().reason(CalendarInvitationService.NOT_FOUND));

    when(calendarInvitationService.getInvitation(13L, USER)).thenThrow(new IllegalArgumentException(CalendarInvitationService.TOO_LARGE));
    mockMvc.perform(get("/email-box/13/invitation").with(simpleUser()))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(CalendarInvitationService.TOO_LARGE));

    when(calendarInvitationService.getInvitation(14L, USER)).thenThrow(new IllegalAccessException("User simple not allowed"));
    mockMvc.perform(get("/email-box/14/invitation").with(simpleUser())).andExpect(status().isForbidden());

    when(calendarInvitationService.getInvitation(15L, USER)).thenThrow(new IllegalStateException("Error connecting simple"));
    mockMvc.perform(get("/email-box/15/invitation").with(simpleUser()))
           .andExpect(status().isInternalServerError())
           .andExpect(status().reason((String) null));
  }

  /**
   * The answer is sent for the caller, and every refusal answers its status: the shared
   * mailbox's refusal with its code, the connector's without the sentence naming the
   * user, the doubtful send as such.
   *
   * @throws Exception when a request cannot be performed
   */
  @Test
  void answeringAnInvitationMapsEveryOutcome() throws Exception {
    CalendarInvitation answered = new CalendarInvitation();
    answered.setAnswer(InvitationAnswer.ACCEPTED);
    when(calendarInvitationService.respond(12L, USER, InvitationAnswer.ACCEPTED)).thenReturn(answered);
    reply(PATH, "ACCEPTED").andExpect(status().isOk()).andExpect(jsonPath("$.answer").value("ACCEPTED"));
    verify(calendarInvitationService).respond(12L, USER, InvitationAnswer.ACCEPTED);

    doThrow(new IllegalArgumentException(CalendarInvitationService.CANCELLED)).when(calendarInvitationService)
                                                                             .respond(13L, USER, InvitationAnswer.DECLINED);
    reply("/email-box/13/invitation", "DECLINED").andExpect(status().isBadRequest())
                                                 .andExpect(status().reason(CalendarInvitationService.CANCELLED));

    doThrow(new SendModeUnavailableException(SendModeUnavailableException.REFUSED_BY_SERVER)).when(calendarInvitationService)
                                                                                           .respond(14L, USER, InvitationAnswer.TENTATIVE);
    reply("/email-box/14/invitation", "TENTATIVE").andExpect(status().isBadRequest())
                                                  .andExpect(status().reason(SendModeUnavailableException.REFUSED_BY_SERVER));

    doThrow(new IllegalAccessException(CalendarInvitationService.SEND_NOT_ALLOWED)).when(calendarInvitationService)
                                                                                  .respond(15L, USER, InvitationAnswer.ACCEPTED);
    reply("/email-box/15/invitation", "ACCEPTED").andExpect(status().isForbidden())
                                                 .andExpect(status().reason(CalendarInvitationService.SEND_NOT_ALLOWED));

    doThrow(new IllegalAccessException("User simple not allowed to send")).when(calendarInvitationService)
                                                                          .respond(16L, USER, InvitationAnswer.ACCEPTED);
    reply("/email-box/16/invitation", "ACCEPTED").andExpect(status().isForbidden()).andExpect(status().reason((String) null));

    doThrow(new IllegalStateException(CalendarInvitationService.UNCONFIRMED)).when(calendarInvitationService)
                                                                            .respond(17L, USER, InvitationAnswer.ACCEPTED);
    reply("/email-box/17/invitation", "ACCEPTED").andExpect(status().isInternalServerError())
                                                 .andExpect(status().reason(CalendarInvitationService.UNCONFIRMED));

    doThrow(new IllegalStateException("Error when sending for simple")).when(calendarInvitationService)
                                                                       .respond(18L, USER, InvitationAnswer.ACCEPTED);
    reply("/email-box/18/invitation", "ACCEPTED").andExpect(status().isInternalServerError())
                                                 .andExpect(status().reason(CalendarInvitationService.SEND_FAILED));

    doThrow(new ObjectNotFoundException("gone")).when(calendarInvitationService).respond(19L, USER, InvitationAnswer.ACCEPTED);
    reply("/email-box/19/invitation", "ACCEPTED").andExpect(status().isNotFound());
  }

  /**
   * An answer that is not one of the three is refused before the service is reached.
   *
   * @throws Exception when a request cannot be performed
   */
  @Test
  void anUnknownAnswerIsRefused() throws Exception {
    reply(PATH, "MAYBE").andExpect(status().isBadRequest());
    verifyNoInteractions(calendarInvitationService);
  }

  /**
   * Posts an answer.
   *
   * @param path the invitation's path
   * @param answer the answer
   * @return the result
   * @throws Exception when the request cannot be performed
   */
  private ResultActions reply(String path, String answer) throws Exception {
    return mockMvc.perform(post(path + "/reply").with(simpleUser())
                                                .content("{\"answer\":\"" + answer + "\"}")
                                                .contentType(MediaType.APPLICATION_JSON));
  }

  /**
   * A signed-in user.
   *
   * @return the post processor
   */
  private static RequestPostProcessor simpleUser() {
    return user(USER).password("testPassword").authorities(new SimpleGrantedAuthority("users"));
  }
}
