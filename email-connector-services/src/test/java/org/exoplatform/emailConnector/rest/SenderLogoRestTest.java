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

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureWebMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.service.SenderLogoService;
import org.exoplatform.emailConnector.utils.SenderLogoUtils;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;

import jakarta.servlet.Filter;

/**
 * The sender logo endpoint (EXO-90893): authenticated users only, the logo served as
 * the type of its bytes, unsniffed and unable to run anything, and each answer of the
 * service keeping its status.
 */
@SpringBootTest(classes = { SenderLogoRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
class SenderLogoRestTest {

  private static final String   PATH = "/email-box/sender-logo/";

  private static final String   SVG  = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>";

  @MockitoBean
  private SenderLogoService     senderLogoService;

  @Autowired
  private SecurityFilterChain   filterChain;

  @Autowired
  private WebApplicationContext context;

  private MockMvc               mockMvc;

  /** Builds the MockMvc with the platform's security filters. */
  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain.getFilters().toArray(new Filter[0])).build();
  }

  /**
   * An authenticated user gets the logo, as the type of its bytes, never sniffed,
   * under a policy that forbids scripts and external loads, kept a day by the browser.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void anAuthenticatedUserGetsTheLogo() throws Exception {
    byte[] svg = SVG.getBytes(StandardCharsets.UTF_8);
    when(senderLogoService.getLogo("brand.example")).thenReturn(new SenderLogo(svg, SenderLogoUtils.SVG, SenderLogo.SOURCE_BIMI, 1L));

    mockMvc.perform(get(PATH + "brand.example").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(header().string(HttpHeaders.CONTENT_TYPE, SenderLogoUtils.SVG))
           .andExpect(header().string("X-Content-Type-Options", "nosniff"))
           .andExpect(header().string("Content-Security-Policy", SenderLogoRest.CONTENT_SECURITY_POLICY))
           .andExpect(header().string("Content-Security-Policy", Matchers.containsString("default-src 'none'")))
           .andExpect(header().string("Content-Security-Policy", Matchers.containsString("sandbox")))
           .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("private")))
           .andExpect(content().bytes(svg));
  }

  /**
   * No logo is a 404, and a path that is no domain a 400 with its message code.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void eachAnswerKeepsItsStatus() throws Exception {
    mockMvc.perform(get(PATH + "plain.example").with(testSimpleUser())).andExpect(status().isNotFound());
    when(senderLogoService.getLogo("localhost")).thenThrow(new IllegalArgumentException(SenderLogoService.INVALID_DOMAIN));
    mockMvc.perform(get(PATH + "localhost").with(testSimpleUser())).andExpect(status().isBadRequest());
  }

  /**
   * A caller who is not signed in is refused before the service is asked: nothing is
   * fetched on an anonymous request.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void anAnonymousCallerIsRefused() throws Exception {
    mockMvc.perform(get(PATH + "brand.example")).andExpect(status().is4xxClientError());
    verify(senderLogoService, never()).getLogo(anyString());
  }

  /**
   * A signed-in user of the platform.
   *
   * @return the request post-processor
   */
  private RequestPostProcessor testSimpleUser() {
    return user("simple").password("testPassword").authorities(new SimpleGrantedAuthority("users"));
  }
}
