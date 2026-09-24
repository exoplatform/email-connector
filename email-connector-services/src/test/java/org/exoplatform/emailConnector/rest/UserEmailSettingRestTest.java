
/**
 * Copyright (C) 2025 eXo Platform SAS
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.EnumMap;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.DelegationGrantee;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.EmailSignature;
import org.exoplatform.emailConnector.model.EmailSignatureLogo;
import org.exoplatform.emailConnector.model.GrantedDelegations;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.rest.model.DelegationInviteRequest;
import org.exoplatform.emailConnector.rest.model.DelegationPreferencesRequest;
import org.exoplatform.emailConnector.service.EmailDelegationService;
import org.exoplatform.emailConnector.model.ReadReceiptPolicy;
import org.exoplatform.emailConnector.model.ReadReceiptSettings;
import org.exoplatform.emailConnector.model.SharedMailboxEntry;
import org.exoplatform.emailConnector.model.SharedMailboxFolder;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailSignatureService;
import org.exoplatform.emailConnector.service.ReadReceiptService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import io.meeds.spring.web.security.PortalAuthenticationManager;
import io.meeds.spring.web.security.WebSecurityConfiguration;

import jakarta.servlet.Filter;
import lombok.SneakyThrows;

@SpringBootTest(classes = { UserEmailSettingRest.class, PortalAuthenticationManager.class })
@ContextConfiguration(classes = { WebSecurityConfiguration.class })
// The platform's REST wire contract (application-common.properties): a primitive absent
// from a body reads as its default, as the read-receipt preferences rely on.
@TestPropertySource(properties = "spring.jackson.deserialization.fail-on-null-for-primitives=false")
@AutoConfigureWebMvc
@AutoConfigureMockMvc(addFilters = false)
@ExtendWith(MockitoExtension.class)
public class UserEmailSettingRestTest {

  private static final String USER_EMAIL_SETTING_PATH = "/user-email-setting"; // NOSONAR

  private static final String SIMPLE_USER             = "simple";

  private static final String TEST_PASSWORD           = "testPassword";

  static final ObjectMapper   OBJECT_MAPPER;

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
  private UserEmailSettingService userEmailSettingService;

  @MockitoBean
  private EmailSignatureService   emailSignatureService;

  @MockitoBean
  private ReadReceiptService      readReceiptService;

  @MockitoBean
  private EmailDelegationService  emailDelegationService;

  @Autowired
  private SecurityFilterChain     filterChain;

  @Autowired
  private WebApplicationContext   context;

  private MockMvc                 mockMvc;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).addFilters(filterChain.getFilters().toArray(new Filter[0])).build();
  }

  @Test
  void connectUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(put(USER_EMAIL_SETTING_PATH
        + "?broadcast=false").with(testSimpleUser())
                             .content(asJsonString(userEmailSetting()))
                             .contentType(MediaType.APPLICATION_JSON)
                             .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
  }

  @Test
  void deleteUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(delete(USER_EMAIL_SETTING_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void getUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(get(USER_EMAIL_SETTING_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  @Test
  void getUserEmailConnectors() throws Exception {
    ResultActions response = mockMvc.perform(get(USER_EMAIL_SETTING_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  // ---------------------------------------------------------------------------------
  // Mailbox delegation: the caller's name goes to the service, the service's refusals
  // come back as the statuses the contract promises, code as message.
  // ---------------------------------------------------------------------------------

  /**
   * Both listings answer the service's models under the caller's own name; the
   * received listing forwards its discover flag.
   */
  @Test
  void delegationListings() throws Exception {
    DelegationGrantee bob = DelegationGrantee.of(MailboxAce.ofLetters("bob@acme.com", MailboxRights.of("lrswite")), "bob", null)
                                             .withExtendableRoles(List.of(FolderRole.JUNK));
    when(emailDelegationService.getGrantedDelegations(SIMPLE_USER)).thenReturn(new GrantedDelegations(MailboxAclCapabilities.imap(true, true),
                                                                                                    "simple@acme.com",
                                                                                                    List.of(bob)));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/granted").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.capabilities.supported").value(true))
           .andExpect(jsonPath("$.ownerMailbox").value("simple@acme.com"))
           // EXO-90548: what an Extend would add reaches the settings row.
           .andExpect(jsonPath("$.grantees[0].extendableRoles[0]").value("JUNK"));

    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(5L);
    delegation.setStatus(DelegationStatus.ACCEPTED);
    delegation.setRights("lrs");
    when(emailDelegationService.getReceivedDelegations(SIMPLE_USER, false)).thenReturn(List.of(delegation));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/received?discover=false").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value(5))
           .andExpect(jsonPath("$[0].affordances.markRead").value(true))
           .andExpect(jsonPath("$[0].affordances.delete").value(false));
    verify(emailDelegationService).getReceivedDelegations(SIMPLE_USER, false);
  }

  /**
   * Changing access hands the preset to the service under the caller's name; a row that
   * is not the caller's answers 404, an invalid preset 400 with its code.
   */
  @Test
  void changingAccessIsTheCallersAndMapsTheRefusals() throws Exception {
    EmailDelegation changed = new EmailDelegation();
    changed.setId(5L);
    changed.setPreset(DelegationPreset.READER);
    when(emailDelegationService.changePreset(SIMPLE_USER, 5L, DelegationPreset.READER)).thenReturn(changed);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/5/preset").with(testSimpleUser())
                                                                        .content("{\"preset\":\"READER\"}")
                                                                        .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.preset").value("READER"));
    verify(emailDelegationService).changePreset(SIMPLE_USER, 5L, DelegationPreset.READER);

    when(emailDelegationService.changePreset(SIMPLE_USER, 6L, DelegationPreset.EDITOR)).thenThrow(new ObjectNotFoundException("emailConnector.delegation.notFound"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/6/preset").with(testSimpleUser())
                                                                        .content("{\"preset\":\"EDITOR\"}")
                                                                        .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound());

    when(emailDelegationService.changePreset(SIMPLE_USER, 7L, DelegationPreset.CUSTOM)).thenThrow(new IllegalArgumentException("emailConnector.delegation.presetInvalid"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/7/preset").with(testSimpleUser())
                                                                        .content("{\"preset\":\"CUSTOM\"}")
                                                                        .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.delegation.presetInvalid"));
  }

  /**
   * EXO-90548 -- "Extend access" is the caller's, as owner: the extended row comes back
   * with what it now covers and what could not be shared, never with the owner's own
   * folder names; a share that is not the caller's is 404, one that cannot be extended
   * 400 with its code.
   */
  @Test
  void extendIsTheOwnersAndSaysWhatItCovers() throws Exception {
    EmailDelegation extended = new EmailDelegation();
    extended.setId(5L);
    extended.setGrantedRoles("INBOX,SENT,ARCHIVE,JUNK");
    extended.setOwnerRoleFolders(new EnumMap<>(Map.of(FolderRole.TRASH, "Corbeille", FolderRole.SENT, "Sent")));
    when(emailDelegationService.extend(SIMPLE_USER, 5L)).thenReturn(extended);
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/delegations/5/extend").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.grantedRoles").value("INBOX,SENT,ARCHIVE,JUNK"))
           .andExpect(jsonPath("$.rolesNotShared[0]").value("TRASH"))
           .andExpect(jsonPath("$.inboxOnly").value(false))
           .andExpect(jsonPath("$.ownerRoleFolders").doesNotExist());

    when(emailDelegationService.extend(SIMPLE_USER, 6L)).thenThrow(new ObjectNotFoundException("emailConnector.delegation.notFound"));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/delegations/6/extend").with(testSimpleUser())).andExpect(status().isNotFound());

    when(emailDelegationService.extend(SIMPLE_USER, 7L)).thenThrow(new IllegalArgumentException("emailConnector.delegation.notChangeable"));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/delegations/7/extend").with(testSimpleUser()))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.delegation.notChangeable"));
  }

  /**
   * The mail drawer's switcher reads the caller's shared mailboxes under the caller's
   * own name, and each entry reaches the client with the folder key the drawer lists
   * and the affordances its chrome is drawn from.
   */
  @Test
  void sharedMailboxesAreTheCallersAndCarryWhatTheSwitcherDraws() throws Exception {
    when(emailDelegationService.getSharedMailboxes(SIMPLE_USER)).thenReturn(List.of(new SharedMailboxEntry(5L,
                                                                                                           "alice",
                                                                                                           "Alice Martin",
                                                                                                           "alice@acme.com",
                                                                                                           DelegationPreset.READER,
                                                                                                           "lrs",
                                                                                                           MailboxRights.of("lrs")
                                                                                                                        .affordances(),
                                                                                                           "CUSTOM:12",
                                                                                                           3,
                                                                                                           List.of(new SharedMailboxFolder("CUSTOM:14",
                                                                                                                                           FolderRole.TRASH,
                                                                                                                                           "Trash",
                                                                                                                                           "lrs",
                                                                                                                                           MailboxRights.of("lrs")
                                                                                                                                                        .affordances(),
                                                                                                                                           true)),
                                                                                                           true,
                                                                                                           true)));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/mailboxes").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].delegationId").value(5))
           .andExpect(jsonPath("$[0].ownerFullName").value("Alice Martin"))
           .andExpect(jsonPath("$[0].preset").value("READER"))
           .andExpect(jsonPath("$[0].folderKey").value("CUSTOM:12"))
           .andExpect(jsonPath("$[0].unreadCount").value(3))
           .andExpect(jsonPath("$[0].affordances.markRead").value(true))
           .andExpect(jsonPath("$[0].affordances.delete").value(false))
           // EXO-90548: the share's other folders, each with its own controls.
           .andExpect(jsonPath("$[0].folders[0].key").value("CUSTOM:14"))
           .andExpect(jsonPath("$[0].folders[0].role").value("TRASH"))
           .andExpect(jsonPath("$[0].folders[0].readable").value(true))
           .andExpect(jsonPath("$[0].folders[0].affordances.delete").value(false))
           // What the band says: from the share, not from the folders found.
           .andExpect(jsonPath("$[0].inboxOnly").value(true))
           // EXO-90551: whether the composer's copy into the owner's Sent will be filed.
           .andExpect(jsonPath("$[0].sentCopy").value(true));
    verify(emailDelegationService).getSharedMailboxes(SIMPLE_USER);
  }

  /**
   * Inviting hands the grantee and the preset to the service under the caller's name;
   * the service's refusals map to 400 (a message code), 401, and 502 (the mail server
   * would not).
   */
  @Test
  void inviteDelegationAndItsRefusals() throws Exception {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(9L);
    delegation.setStatus(DelegationStatus.PENDING);
    when(emailDelegationService.invite(SIMPLE_USER, "bob", DelegationPreset.EDITOR)).thenReturn(delegation);
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/delegations").with(testSimpleUser())
                                                                  .content(asJsonString(new DelegationInviteRequest("bob", DelegationPreset.EDITOR)))
                                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(9))
           .andExpect(jsonPath("$.status").value("PENDING"));

    when(emailDelegationService.invite(SIMPLE_USER, "nobody", DelegationPreset.READER))
                                                                                       .thenThrow(new IllegalArgumentException(EmailDelegationService.GRANTEE_NOT_CONNECTED_MESSAGE));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/delegations").with(testSimpleUser())
                                                                  .content(asJsonString(new DelegationInviteRequest("nobody", DelegationPreset.READER)))
                                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(EmailDelegationService.GRANTEE_NOT_CONNECTED_MESSAGE));

    when(emailDelegationService.invite(SIMPLE_USER, "carol", DelegationPreset.READER))
                                                                                      .thenThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED,
                                                                                                                         "SETACL refused"));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/delegations").with(testSimpleUser())
                                                                  .content(asJsonString(new DelegationInviteRequest("carol", DelegationPreset.READER)))
                                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadGateway())
           .andExpect(status().reason(MailboxAclException.SERVER_REFUSED));

    when(emailDelegationService.invite(SIMPLE_USER, "dave", DelegationPreset.READER)).thenThrow(new IllegalAccessException("not connected"));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/delegations").with(testSimpleUser())
                                                                  .content(asJsonString(new DelegationInviteRequest("dave", DelegationPreset.READER)))
                                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isUnauthorized());
  }

  /**
   * The grantee's verbs: accept answers 410 with the code when the share is gone and
   * 404 for a row that is not the caller's; decline, leave and preferences go through
   * under the caller's name.
   */
  @Test
  void acceptDeclineLeaveAndPreferences() throws Exception {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(5L);
    delegation.setStatus(DelegationStatus.ACCEPTED);
    when(emailDelegationService.accept(SIMPLE_USER, 5L)).thenReturn(delegation);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/5/accept").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.status").value("ACCEPTED"));

    when(emailDelegationService.accept(SIMPLE_USER, 6L)).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/6/accept").with(testSimpleUser()))
           .andExpect(status().isGone())
           .andExpect(status().reason(DelegationRevokedException.REVOKED));

    when(emailDelegationService.accept(SIMPLE_USER, 7L)).thenThrow(new ObjectNotFoundException(EmailDelegationService.NOT_FOUND_MESSAGE));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/7/accept").with(testSimpleUser()))
           .andExpect(status().isNotFound());

    when(emailDelegationService.accept(SIMPLE_USER, 8L)).thenThrow(new IllegalArgumentException(EmailDelegationService.TOO_MANY_MESSAGE));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/8/accept").with(testSimpleUser()))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(EmailDelegationService.TOO_MANY_MESSAGE));

    when(emailDelegationService.decline(SIMPLE_USER, 5L)).thenReturn(delegation);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/5/decline").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailDelegationService).decline(SIMPLE_USER, 5L);

    when(emailDelegationService.leave(SIMPLE_USER, 5L)).thenReturn(delegation);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/5/leave").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailDelegationService).leave(SIMPLE_USER, 5L);

    when(emailDelegationService.updatePreferences(SIMPLE_USER, 5L, true, null, null)).thenReturn(delegation);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/5/preferences").with(testSimpleUser())
                                                                               .content(asJsonString(new DelegationPreferencesRequest(true, null)))
                                                                               .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailDelegationService).updatePreferences(SIMPLE_USER, 5L, true, null, null);

    // EXO-90554: the search toggle travels alone.
    when(emailDelegationService.updatePreferences(SIMPLE_USER, 5L, null, null, false)).thenReturn(delegation);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/5/preferences").with(testSimpleUser())
                                                                               .content("{\"searchIncluded\":false}")
                                                                               .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailDelegationService).updatePreferences(SIMPLE_USER, 5L, null, null, false);
  }

  /**
   * Revoke: the owner's verb, 200, 404 for a row that is not the caller's as owner,
   * 502 when the server refuses DELETEACL.
   */
  @Test
  void revokeDelegationAndItsRefusals() throws Exception {
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/delegations/5").with(testSimpleUser())).andExpect(status().isOk());
    verify(emailDelegationService).revoke(SIMPLE_USER, 5L);

    doThrow(new ObjectNotFoundException(EmailDelegationService.NOT_FOUND_MESSAGE)).when(emailDelegationService).revoke(SIMPLE_USER, 6L);
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/delegations/6").with(testSimpleUser())).andExpect(status().isNotFound());

    doThrow(new MailboxAclException(MailboxAclException.SERVER_REFUSED, "NO [NOPERM]")).when(emailDelegationService).revoke(SIMPLE_USER, 7L);
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/delegations/7").with(testSimpleUser()))
           .andExpect(status().isBadGateway())
           .andExpect(status().reason(MailboxAclException.SERVER_REFUSED));

    // #443-1: a share of a mailbox the owner is no longer connected to is a 400 with its
    // code, not a server error.
    doThrow(new IllegalArgumentException(EmailDelegationService.NOT_CHANGEABLE_MESSAGE)).when(emailDelegationService).revoke(SIMPLE_USER, 8L);
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/delegations/8").with(testSimpleUser()))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(EmailDelegationService.NOT_CHANGEABLE_MESSAGE));
  }

  /**
   * The signature round trip at the HTTP level: reading answers the service's
   * model, storing hands the body to the service under the caller's own name.
   *
   * @throws Exception when the mock HTTP plumbing misbehaves
   */
  @Test
  void getAndSaveEmailSignature() throws Exception {
    when(emailSignatureService.getEmailSignature(SIMPLE_USER)).thenReturn(new EmailSignature(true, null, "<p>me</p>", false, null));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/signature").with(testSimpleUser()))
           .andExpect(status().isOk());
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/signature").with(testSimpleUser())
                                                               .content(asJsonString(new EmailSignature(true,
                                                                                                        "<p>mine</p>",
                                                                                                        null,
                                                                                                        false, null)))
                                                               .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailSignatureService).saveEmailSignature(eq(SIMPLE_USER), any(EmailSignature.class));
  }

  /**
   * The service's size-cap refusal comes back as the 400 the exception contract
   * promises, message code and all — what lets the settings screen say WHY.
   *
   * @throws Exception when the mock HTTP plumbing misbehaves
   */
  @Test
  void aTooLongSignatureAnswers400() throws Exception {
    doThrow(new IllegalArgumentException("emailConnector.signature.tooLong")).when(emailSignatureService)
                                                                             .saveEmailSignature(eq(SIMPLE_USER),
                                                                                                 any(EmailSignature.class));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/signature").with(testSimpleUser())
                                                               .content(asJsonString(new EmailSignature(true,
                                                                                                        "<p>huge</p>",
                                                                                                        null,
                                                                                                        false, null)))
                                                               .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }

  /**
   * The signature image: bytes with an honest content type when there is one,
   * a plain 404 when there is none — never a broken 200.
   *
   * @throws Exception when the mock HTTP plumbing misbehaves
   */
  @Test
  void getSignatureImage() throws Exception {
    when(emailSignatureService.getSignatureLogo(SIMPLE_USER)).thenReturn(new EmailSignatureLogo(new byte[] { 1, 2 },
                                                                                                "image/png",
                                                                                                "logo"));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/signature/image").with(testSimpleUser()))
           .andExpect(status().isOk());
    when(emailSignatureService.getSignatureLogo(SIMPLE_USER)).thenReturn(null);
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/signature/image").with(testSimpleUser()))
           .andExpect(status().isNotFound());
  }

  /**
   * Replacing and resetting the signature image, including the 400 a dead
   * upload id earns.
   *
   * @throws Exception when the mock HTTP plumbing misbehaves
   */
  @Test
  void saveAndDeleteSignatureImage() throws Exception {
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/signature/image?uploadId=up1").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailSignatureService).saveSignatureLogo(SIMPLE_USER, "up1");
    doThrow(new IllegalArgumentException("emailConnector.signature.logo.uploadGone")).when(emailSignatureService)
                                                                                     .saveSignatureLogo(SIMPLE_USER, "gone");
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/signature/image?uploadId=gone").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/signature/image").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailSignatureService).deleteSignatureLogo(SIMPLE_USER);
  }

  private RequestPostProcessor testSimpleUser() {
    return user(SIMPLE_USER).password(TEST_PASSWORD).authorities(new SimpleGrantedAuthority("users"));
  }

  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", "testEmail", "testPassword", null, null, 0, 0L, null, null, null, true);
  }

  @SneakyThrows
  private String asJsonString(final Object obj) {
    return OBJECT_MAPPER.writeValueAsString(obj);
  }

  /**
   * The read-receipt preferences are read and written for the authenticated caller,
   * and a refused ALWAYS answers 400 with its code.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void readReceiptPreferences() throws Exception {
    when(readReceiptService.getSettings(SIMPLE_USER)).thenReturn(new ReadReceiptSettings(true, ReadReceiptPolicy.ASK, true));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/read-receipts").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.requestByDefault").value(true))
           .andExpect(jsonPath("$.responsePolicy").value("ASK"))
           .andExpect(jsonPath("$.alwaysAllowed").value(true));

    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/read-receipts").with(testSimpleUser())
                                                                   .content("{\"requestByDefault\":false,\"responsePolicy\":\"NEVER\"}")
                                                                   .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(readReceiptService).saveSettings(SIMPLE_USER, new ReadReceiptSettings(false, ReadReceiptPolicy.NEVER, false));

    when(readReceiptService.saveSettings(eq(SIMPLE_USER), any())).thenThrow(new IllegalArgumentException(ReadReceiptService.NOT_ALLOWED));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/read-receipts").with(testSimpleUser())
                                                                   .content("{\"responsePolicy\":\"ALWAYS\"}")
                                                                   .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }
}
