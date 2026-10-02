
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
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
import java.util.Set;

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

import org.exoplatform.emailConnector.exception.ManagedConnectionLockedException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxAclException;
import org.exoplatform.emailConnector.exception.ForwardingRefusedException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.AbsenceSettings;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.AbsenceStatus;
import org.exoplatform.emailConnector.model.OwnerAbsenceStatus;
import org.exoplatform.emailConnector.model.DelegationFolder;
import org.exoplatform.emailConnector.model.DelegationFolders;
import org.exoplatform.emailConnector.model.DelegationGrantee;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.EmailSignature;
import org.exoplatform.emailConnector.model.EmailSignatureLogo;
import org.exoplatform.emailConnector.model.FolderAccess;
import org.exoplatform.emailConnector.model.FolderAccessChange;
import org.exoplatform.emailConnector.model.FolderAccessResult;
import org.exoplatform.emailConnector.model.FolderAccessUpdate;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.model.GrantedDelegations;
import org.exoplatform.emailConnector.model.MailboxAce;
import org.exoplatform.emailConnector.model.MailboxAclCapabilities;
import org.exoplatform.emailConnector.model.MailboxRights;
import org.exoplatform.emailConnector.model.SendMode;
import org.exoplatform.emailConnector.model.ReadReceiptPolicy;
import org.exoplatform.emailConnector.model.ReadReceiptSettings;
import org.exoplatform.emailConnector.model.RemoteContentSettings;
import org.exoplatform.emailConnector.model.SharedMailboxEntry;
import org.exoplatform.emailConnector.model.SharedMailboxFolder;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.rest.model.DelegationFoldersRequest;
import org.exoplatform.emailConnector.rest.model.DelegationSendModeRequest;
import org.exoplatform.emailConnector.rest.model.DelegationInviteRequest;
import org.exoplatform.emailConnector.rest.model.DelegationPreferencesRequest;
import org.exoplatform.emailConnector.service.EmailAbsenceService;
import org.exoplatform.emailConnector.service.EmailForwardingService;
import org.exoplatform.emailConnector.service.EmailScheduledSendService;
import org.exoplatform.emailConnector.model.UndoSendSettings;
import org.exoplatform.emailConnector.service.EmailDelegationService;
import org.exoplatform.emailConnector.service.EmailSignatureService;
import org.exoplatform.emailConnector.service.EmailSecurityService;
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
  private EmailSecurityService    emailSecurityService;

  @MockitoBean
  private EmailDelegationService  emailDelegationService;

  @MockitoBean
  private EmailAbsenceService     emailAbsenceService;

  @MockitoBean
  private EmailForwardingService  emailForwardingService;

  @MockitoBean
  private EmailScheduledSendService emailScheduledSendService;

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
    // A hand-written body: the shared mapper honours WRITE_ONLY and would leave the
    // password out, while a browser's PUT carries it.
    ResultActions response = mockMvc.perform(put(USER_EMAIL_SETTING_PATH
        + "?broadcast=false").with(testSimpleUser())
                             .content("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail\",\"emailPassword\":\"testPassword\"}")
                             .contentType(MediaType.APPLICATION_JSON)
                             .accept(MediaType.APPLICATION_JSON));
    response.andExpect(status().isOk());
    verify(userEmailSettingService).connectUserEmailSetting(argThat(setting -> "testPassword".equals(setting.getEmailPassword())),
                                                            eq(SIMPLE_USER),
                                                            eq(false));
  }

  @Test
  void deleteUserEmailSetting() throws Exception {
    ResultActions response = mockMvc.perform(delete(USER_EMAIL_SETTING_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
    verify(userEmailSettingService).disconnectUserEmailSetting(SIMPLE_USER);
    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /**
   * EXO-90836. A governed user's disconnection, typed connection and one-click
   * connection elsewhere answer 403 with their code carried in the 403 body.
   */
  @Test
  void aGovernedUsersConnectionChangesAnswer403WithTheirCode() throws Exception {
    doThrow(new ManagedConnectionLockedException()).when(userEmailSettingService).disconnectUserEmailSetting(SIMPLE_USER);
    doThrow(new ManagedConnectionLockedException()).when(userEmailSettingService)
                                                    .connectUserEmailSetting(any(), eq(SIMPLE_USER), eq(false));
    doThrow(new ManagedConnectionLockedException()).when(userEmailSettingService).connectThroughProvider(3L, SIMPLE_USER);

    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH).with(testSimpleUser()))
           .andExpect(status().isForbidden())
           .andExpect(status().reason(ManagedConnectionLockedException.MESSAGE_CODE));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "?broadcast=false").with(testSimpleUser())
                                                                     .content(asJsonString(userEmailSetting()))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isForbidden())
           .andExpect(status().reason(ManagedConnectionLockedException.MESSAGE_CODE));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/connect?emailConnectorId=3").with(testSimpleUser()))
           .andExpect(status().isForbidden())
           .andExpect(status().reason(ManagedConnectionLockedException.MESSAGE_CODE));
  }

  /** EXO-90836. The settings read says whether managed mode keeps the user, and on which connector. */
  @Test
  void theSettingsReadSaysWhetherManagedModeKeepsTheUser() throws Exception {
    UserEmailSetting stored = userEmailSetting();
    stored.setManaged(true);
    stored.setManagedConnectorId(7L);
    when(userEmailSettingService.getUserEmailSettingWithManagedMode(SIMPLE_USER)).thenReturn(stored);

    mockMvc.perform(get(USER_EMAIL_SETTING_PATH).with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.managed").value(true))
           .andExpect(jsonPath("$.managedConnectorId").value(7));
  }

  @Test
  void getUserEmailSetting() throws Exception {
    when(userEmailSettingService.getUserEmailSettingWithManagedMode(SIMPLE_USER)).thenReturn(new UserEmailSetting());
    ResultActions response = mockMvc.perform(get(USER_EMAIL_SETTING_PATH).with(testSimpleUser()));
    response.andExpect(status().isOk());
  }

  /**
   * EXO-90610. The settings read never carries the password; it says whether one is
   * stored.
   */
  @Test
  void theSettingsReadNeverSendsThePasswordBack() throws Exception {
    UserEmailSetting stored = userEmailSetting();
    stored.setPasswordStored(true);
    when(userEmailSettingService.getUserEmailSettingWithManagedMode(SIMPLE_USER)).thenReturn(stored);

    mockMvc.perform(get(USER_EMAIL_SETTING_PATH).with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.emailPassword").doesNotExist())
           .andExpect(jsonPath("$.passwordStored").value(true))
           .andExpect(jsonPath("$.emailAddress").value(stored.getEmailAddress()));
  }

  /** EXO-90610. A connection refused for lack of a password answers 400 with its code. */
  @Test
  void aConnectionWithNoApplicablePasswordAnswers400() throws Exception {
    doThrow(new IllegalArgumentException(UserEmailSettingService.PASSWORD_REQUIRED)).when(userEmailSettingService)
                                                                                    .connectUserEmailSetting(any(),
                                                                                                             eq(SIMPLE_USER),
                                                                                                             eq(false));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "?broadcast=false").with(testSimpleUser())
                                                                     .content(asJsonString(userEmailSetting()))
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .accept(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());
  }

  @Test
  void getUserEmailConnectors() throws Exception {
    EmailConnector connector = new EmailConnector();
    connector.setId(1L);
    connector.setName("Company mail");
    when(userEmailSettingService.getUserEmailConnectors(any(), eq(SIMPLE_USER))).thenReturn(List.of(connector));

    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/connectors").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[0].id").value(1))
           .andExpect(jsonPath("$[0].name").value("Company mail"));
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
                                                                                                                                           true,
                                                                                                                                           "Trash",
                                                                                                                                           "/")),
                                                                                                           true,
                                                                                                           true,
                                                                                                           List.of(SendMode.ON_BEHALF))));
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
           .andExpect(jsonPath("$[0].sentCopy").value(true))
           // EXO-90582: the shapes the delegate can write in the owner's name in now.
           .andExpect(jsonPath("$[0].sendModes[0]").value("ON_BEHALF"));
    verify(emailDelegationService).getSharedMailboxes(SIMPLE_USER);
  }

  /**
   * Inviting hands the grantee and the preset to the service under the caller's name;
   * the service's refusals map to 400 (a message code), 403, and 502 (the mail server
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
           .andExpect(status().isForbidden());
  }

  /**
   * EXO-90556 -- the owner's folder choice at invitation travels to the service, keyed by
   * role; without one, the invitation is the one it always was.
   */
  @Test
  void inviteCarriesTheOwnersFolderChoice() throws Exception {
    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(9L);
    Map<FolderRole, FolderAccess> choice = Map.of(FolderRole.TRASH, FolderAccess.NONE);
    when(emailDelegationService.invite(SIMPLE_USER, "bob", DelegationPreset.EDITOR, choice)).thenReturn(delegation);

    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/delegations").with(testSimpleUser())
                                                                  .content("{\"granteeUsername\":\"bob\",\"preset\":\"EDITOR\",\"folderAccess\":{\"TRASH\":\"NONE\"}}")
                                                                  .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value(9));
    verify(emailDelegationService).invite(SIMPLE_USER, "bob", DelegationPreset.EDITOR, choice);
    verify(emailDelegationService, never()).invite(anyString(), anyString(), any());
  }

  /**
   * EXO-90556 -- the owner's folder lists and the per-folder save go to the service under
   * the caller's name, the save's per-folder outcomes answered 200; the refusals map as
   * the other owner verbs: 404 for a row that is not the caller's, 400 with the code, 502
   * when the server would not.
   */
  @Test
  void theOwnersFolderListsAndSave() throws Exception {
    DelegationFolder sent = new DelegationFolder("Sent", "Sent", "/", null, 0, FolderRole.SENT, FolderAccess.READER, null, true, true);
    when(emailDelegationService.getOwnFolders(SIMPLE_USER)).thenReturn(new DelegationFolders(List.of(sent), true));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/folders").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.folders[0].folder").value("Sent"))
           .andExpect(jsonPath("$.truncated").value(true));

    when(emailDelegationService.getFolderAccess(SIMPLE_USER, 5L)).thenReturn(new DelegationFolders(List.of(sent), false));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/5/folders").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.folders[0].access").value("READER"))
           .andExpect(jsonPath("$.folders[0].role").value("SENT"));
    when(emailDelegationService.getFolderAccess(SIMPLE_USER, 6L)).thenThrow(new ObjectNotFoundException(EmailDelegationService.NOT_FOUND_MESSAGE));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/6/folders").with(testSimpleUser())).andExpect(status().isNotFound());

    EmailDelegation delegation = new EmailDelegation();
    delegation.setId(5L);
    List<FolderAccessChange> changes = List.of(new FolderAccessChange("Sent", FolderAccess.NONE), new FolderAccessChange("Projects", FolderAccess.EDITOR));
    when(emailDelegationService.setFolderAccess(SIMPLE_USER, 5L, changes))
                                                                       .thenReturn(new FolderAccessUpdate(delegation,
                                                                                                          List.of(new FolderAccessResult("Sent",
                                                                                                                                         FolderAccess.NONE,
                                                                                                                                         FolderAccessResult.Outcome.DONE),
                                                                                                                  new FolderAccessResult("Projects",
                                                                                                                                         FolderAccess.EDITOR,
                                                                                                                                         FolderAccessResult.Outcome.REFUSED))));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/5/folders").with(testSimpleUser())
                                                                           .content(asJsonString(new DelegationFoldersRequest(changes)))
                                                                           .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.delegation.id").value(5))
           .andExpect(jsonPath("$.results[1].outcome").value("REFUSED"));
    verify(emailDelegationService).setFolderAccess(SIMPLE_USER, 5L, changes);

    when(emailDelegationService.setFolderAccess(eq(SIMPLE_USER), eq(7L), any()))
                                                                              .thenThrow(new IllegalArgumentException(EmailDelegationService.FOLDER_UNKNOWN_MESSAGE));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/7/folders").with(testSimpleUser())
                                                                           .content(asJsonString(new DelegationFoldersRequest(changes)))
                                                                           .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(EmailDelegationService.FOLDER_UNKNOWN_MESSAGE));
    when(emailDelegationService.setFolderAccess(eq(SIMPLE_USER), eq(8L), any()))
                                                                              .thenThrow(new MailboxAclException(MailboxAclException.UNREACHABLE,
                                                                                                                 "down"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/8/folders").with(testSimpleUser())
                                                                           .content(asJsonString(new DelegationFoldersRequest(changes)))
                                                                           .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadGateway())
           .andExpect(status().reason(MailboxAclException.UNREACHABLE));
  }

  /**
   * EXO-90582 -- the owner's consent to a grantee writing in her name: the mode goes to
   * the service as sent, under the caller's name, and the row comes back with it; each
   * refusal answers the status the contract promises, with its code. The owner's and the
   * delegate's lists carry what the drawer and the switcher read.
   */
  @Test
  void theOwnersSendModeConsent() throws Exception {
    EmailDelegation consented = new EmailDelegation();
    consented.setId(5L);
    consented.setStatus(DelegationStatus.ACCEPTED);
    consented.setSendMode(SendMode.ON_BEHALF);
    when(emailDelegationService.setSendMode(SIMPLE_USER, 5L, "ON_BEHALF")).thenReturn(consented);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/5/send-mode").with(testSimpleUser())
                                                                              .content(asJsonString(new DelegationSendModeRequest("ON_BEHALF")))
                                                                              .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.sendMode").value("ON_BEHALF"));
    verify(emailDelegationService).setSendMode(SIMPLE_USER, 5L, "ON_BEHALF");

    Map<Long, Object> refusals = Map.of(6L,
                                        new IllegalArgumentException(EmailDelegationService.SEND_MODE_INVALID_MESSAGE),
                                        7L,
                                        new IllegalArgumentException(EmailDelegationService.SEND_MODE_UNSUPPORTED_MESSAGE),
                                        8L,
                                        new IllegalArgumentException(EmailDelegationService.NOT_CHANGEABLE_MESSAGE),
                                        9L,
                                        new MailboxAclException(MailboxAclException.UNREACHABLE, "down"),
                                        10L,
                                        new ObjectNotFoundException(EmailDelegationService.NOT_FOUND_MESSAGE),
                                        11L,
                                        new IllegalAccessException("emailConnector.notConnected"));
    for (Map.Entry<Long, Object> refusal : refusals.entrySet()) {
      when(emailDelegationService.setSendMode(eq(SIMPLE_USER), eq(refusal.getKey()), any())).thenThrow((Throwable) refusal.getValue());
    }
    assertSendModeAnswer(6L, 400, EmailDelegationService.SEND_MODE_INVALID_MESSAGE);
    assertSendModeAnswer(7L, 400, EmailDelegationService.SEND_MODE_UNSUPPORTED_MESSAGE);
    assertSendModeAnswer(8L, 400, EmailDelegationService.NOT_CHANGEABLE_MESSAGE);
    assertSendModeAnswer(9L, 502, MailboxAclException.UNREACHABLE);
    assertSendModeAnswer(10L, 404, null);
    assertSendModeAnswer(11L, 403, null);

    DelegationGrantee bob = DelegationGrantee.of(MailboxAce.ofLetters("bob@acme.com", MailboxRights.of("lrswite")), "bob", consented)
                                             .withConsentContext("Bob Martin", true);
    when(emailDelegationService.getGrantedDelegations(SIMPLE_USER)).thenReturn(new GrantedDelegations(MailboxAclCapabilities.imap(true,
                                                                                                                                  true,
                                                                                                                                  Set.of(SendMode.ON_BEHALF,
                                                                                                                                         SendMode.AS)),
                                                                                                    "simple@acme.com",
                                                                                                    List.of(bob)));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/granted").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.capabilities.sendModes.length()").value(2))
           .andExpect(jsonPath("$.capabilities.sendModeOnServer").value(false))
           .andExpect(jsonPath("$.grantees[0].granteeFullName").value("Bob Martin"))
           .andExpect(jsonPath("$.grantees[0].ownerSentCopy").value(true))
           .andExpect(jsonPath("$.grantees[0].delegation.sendMode").value("ON_BEHALF"));
  }

  /**
   * One refused PUT of a send mode, and the status and code it answers.
   *
   * @param id the delegation id the service refuses
   * @param status the status expected
   * @param reason the code expected, null when the status says it all
   * @throws Exception when the request cannot be performed
   */
  private void assertSendModeAnswer(long id, int status, String reason) throws Exception {
    ResultActions response = mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/" + id + "/send-mode").with(testSimpleUser())
                                                                                                             .content(asJsonString(new DelegationSendModeRequest("AS")))
                                                                                                             .contentType(MediaType.APPLICATION_JSON))
                                     .andExpect(status().is(status));
    if (reason != null) {
      response.andExpect(status().reason(reason));
    }
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
   * The remaining refusals of the delegation verbs map to the add-on's statuses: no
   * connected mailbox is 403, a server that would not answer is 502 with its code, a row
   * that is not the caller's is 404, a verb out of its state is 400 with its code.
   */
  @Test
  void delegationRefusalsMapToTheirStatuses() throws Exception {
    when(emailDelegationService.getGrantedDelegations(SIMPLE_USER)).thenThrow(new IllegalAccessException("not connected"),
                                                                              new MailboxAclException(MailboxAclException.UNREACHABLE,
                                                                                                      "timeout"));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/granted").with(testSimpleUser()))
           .andExpect(status().isForbidden());
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/delegations/granted").with(testSimpleUser()))
           .andExpect(status().isBadGateway())
           .andExpect(status().reason(MailboxAclException.UNREACHABLE));

    doThrow(new IllegalAccessException("not connected")).when(emailDelegationService).revoke(SIMPLE_USER, 8L);
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/delegations/8").with(testSimpleUser())).andExpect(status().isForbidden());

    when(emailDelegationService.accept(SIMPLE_USER, 9L)).thenThrow(new IllegalAccessException("not connected"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/9/accept").with(testSimpleUser()))
           .andExpect(status().isForbidden());

    when(emailDelegationService.accept(SIMPLE_USER, 10L)).thenThrow(new MailboxAclException(MailboxAclException.UNREACHABLE, "timeout"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/10/accept").with(testSimpleUser()))
           .andExpect(status().isBadGateway())
           .andExpect(status().reason(MailboxAclException.UNREACHABLE));

    when(emailDelegationService.decline(SIMPLE_USER, 6L)).thenThrow(new ObjectNotFoundException(EmailDelegationService.NOT_FOUND_MESSAGE));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/6/decline").with(testSimpleUser())).andExpect(status().isNotFound());
    when(emailDelegationService.decline(SIMPLE_USER, 7L)).thenThrow(new IllegalArgumentException("emailConnector.delegation.notPending"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/7/decline").with(testSimpleUser()))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.delegation.notPending"));

    when(emailDelegationService.leave(SIMPLE_USER, 6L)).thenThrow(new ObjectNotFoundException(EmailDelegationService.NOT_FOUND_MESSAGE));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/6/leave").with(testSimpleUser())).andExpect(status().isNotFound());
    when(emailDelegationService.leave(SIMPLE_USER, 7L)).thenThrow(new IllegalArgumentException("emailConnector.delegation.notAccepted"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/7/leave").with(testSimpleUser()))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.delegation.notAccepted"));

    when(emailDelegationService.updatePreferences(SIMPLE_USER, 6L, null, true, null))
                                                                               .thenThrow(new ObjectNotFoundException(EmailDelegationService.NOT_FOUND_MESSAGE));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/delegations/6/preferences").with(testSimpleUser())
                                                                               .content(asJsonString(new DelegationPreferencesRequest(null, true)))
                                                                               .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound());
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

  /** The one-click connect acts for the authenticated user only, never for a name the client sends. */
  @Test
  @SneakyThrows
  void connectThroughProviderConnectsTheCaller() {
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/connect?emailConnectorId=1").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(userEmailSettingService).connectThroughProvider(1L, SIMPLE_USER);
  }

  /** A user who may not connect this connector gets a 403, as the endpoint documents. */
  @Test
  @SneakyThrows
  void connectThroughProviderAnswers403WhenTheUserMayNotConnect() {
    doThrow(new IllegalAccessException("not allowed")).when(userEmailSettingService).connectThroughProvider(1L, SIMPLE_USER);
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/connect?emailConnectorId=1").with(testSimpleUser()))
           .andExpect(status().isForbidden());
  }

  /** A provider that expects the user to type something is a 400: the browser shows the form. */
  @Test
  @SneakyThrows
  void connectThroughProviderAnswers400WhenTheProviderAsksTheUser() {
    doThrow(new IllegalArgumentException("asks")).when(userEmailSettingService).connectThroughProvider(1L, SIMPLE_USER);
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/connect?emailConnectorId=1").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
  }

  /** A mailbox refusing the service account is a 500: nothing the user can correct. */
  @Test
  @SneakyThrows
  void connectThroughProviderAnswers500WhenTheMailboxRefuses() {
    doThrow(new IllegalStateException("refused")).when(userEmailSettingService).connectThroughProvider(1L, SIMPLE_USER);
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/connect?emailConnectorId=1").with(testSimpleUser()))
           .andExpect(status().isInternalServerError());
  }

  /** The endpoint is for platform users: an identity without the users role never reaches the service. */
  @Test
  @SneakyThrows
  void connectThroughProviderIsRefusedWithoutTheUsersRole() {
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/connect?emailConnectorId=1").with(user(SIMPLE_USER).password(TEST_PASSWORD)
                                                                                                    .authorities(new SimpleGrantedAuthority("guests"))))
           .andExpect(status().isForbidden());
    verify(userEmailSettingService, never()).connectThroughProvider(anyLong(), anyString());
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

  /**
   * The Undo send preference (EXO-90837) is the caller's: read with the offered waits,
   * stored as given, and a wait not offered is a 400 with its code.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void undoSendPreference() throws Exception {
    when(emailScheduledSendService.getUndoSendSettings(SIMPLE_USER)).thenReturn(new UndoSendSettings(10, List.of(0, 5, 10, 20, 30)));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/undo-send").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.delaySeconds").value(10))
           .andExpect(jsonPath("$.allowedDelays[4]").value(30));

    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/undo-send").with(testSimpleUser())
                                                                .content("{\"delaySeconds\":20}")
                                                                .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailScheduledSendService).saveUndoSendSettings(SIMPLE_USER, new UndoSendSettings(20, null));

    when(emailScheduledSendService.saveUndoSendSettings(eq(SIMPLE_USER), any())).thenThrow(new IllegalArgumentException(EmailScheduledSendService.UNDO_SEND_INVALID_DELAY));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/undo-send").with(testSimpleUser())
                                                                .content("{\"delaySeconds\":7}")
                                                                .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(EmailScheduledSendService.UNDO_SEND_INVALID_DELAY));
  }

  /**
   * EXO-90841: the remote-content choices are read and written for the authenticated
   * caller only, an address that is not one answers 400 with its code, and a switch
   * request without a body answers 400.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void remoteContentChoices() throws Exception {
    when(emailSecurityService.getSettings(SIMPLE_USER)).thenReturn(new RemoteContentSettings(true, List.of("news@shop.example")));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/remote-content").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.blockRemoteContent").value(true))
           .andExpect(jsonPath("$.trustedSenders[0]").value("news@shop.example"));

    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/remote-content").with(testSimpleUser())
                                                                    .content("{\"blockRemoteContent\":false,\"trustedSenders\":[\"x@evil.example\"]}")
                                                                    .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailSecurityService).setBlockRemoteContent(SIMPLE_USER, false);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/remote-content").with(testSimpleUser()).contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest());

    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/remote-content/trusted-senders").param("address", "News@Shop.example")
                                                                                    .with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailSecurityService).trustSender(SIMPLE_USER, "News@Shop.example");
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/remote-content/trusted-senders").param("address", "news@shop.example")
                                                                                      .with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailSecurityService).forgetSender(SIMPLE_USER, "news@shop.example");

    when(emailSecurityService.trustSender(SIMPLE_USER, "nobody")).thenThrow(new IllegalArgumentException(EmailSecurityService.INVALID_SENDER));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/remote-content/trusted-senders").param("address", "nobody").with(testSimpleUser()))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(EmailSecurityService.INVALID_SENDER));
    when(emailSecurityService.forgetSender(SIMPLE_USER, "nobody")).thenThrow(new IllegalArgumentException(EmailSecurityService.INVALID_SENDER));
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/remote-content/trusted-senders").param("address", "nobody").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
  }

  // ---------------------------------------------------------------------------------
  // Automatic reply (EXO-90642): the caller's own mailbox only, and the refusals as the
  // statuses the contract promises, code as message.
  // ---------------------------------------------------------------------------------

  /**
   * The section is read and written for the caller; the body reaches the service, and
   * republish is forwarded.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void absenceReadAndWrite() throws Exception {
    AbsenceSettings settings = new AbsenceSettings(null, "sieve", null, VacationState.NONE, null, 7, null);
    when(emailAbsenceService.getAbsence(SIMPLE_USER, null, null, true)).thenReturn(settings);
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.engine").value("sieve"))
           .andExpect(jsonPath("$.vacationState").value("NONE"));
    AbsenceSettings forwarding = new AbsenceSettings(null, "sieve", null, VacationState.NONE, null, 7,
                                                     ForwardingSetting.mayForwardByScript("roundcube")
                                                                      .withManageUrl("https://webmail.example.org"));
    when(emailAbsenceService.getAbsence(SIMPLE_USER, null, "UTC", true)).thenReturn(forwarding);
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence?timeZone=UTC").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.forwarding.state").value("MAY_FORWARD_BY_SCRIPT"))
           .andExpect(jsonPath("$.forwarding.scriptName").value("roundcube"))
           .andExpect(jsonPath("$.forwarding.destinations").isEmpty())
           .andExpect(jsonPath("$.forwarding.manageUrl").value("https://webmail.example.org"));
    when(emailAbsenceService.getAbsence(SIMPLE_USER, null, "Europe/Paris", false)).thenReturn(settings);
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence?timeZone=Europe/Paris&forwarding=false").with(testSimpleUser()))
           .andExpect(status().isOk());
    verify(emailAbsenceService).getAbsence(SIMPLE_USER, null, "Europe/Paris", false);

    when(emailAbsenceService.setVacation(eq(SIMPLE_USER), eq(null), any(), eq(true))).thenReturn(settings);
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/vacation?republish=true").with(testSimpleUser())
                                                                                      .content("{\"enabled\":true,\"subject\":\"Away\",\"text\":\"Back soon\",\"start\":\"2026-10-01\",\"timeZone\":\"Europe/Paris\"}")
                                                                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isOk());
    verify(emailAbsenceService).setVacation(SIMPLE_USER,
                                            null,
                                            new VacationSetting(true, "2026-10-01", null, "Europe/Paris", "Away", "Back soon", 0, null),
                                            true);

    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/absence/vacation").with(testSimpleUser()))
           .andExpect(status().isNoContent());
    verify(emailAbsenceService).disableVacation(SIMPLE_USER, null);

    when(emailAbsenceService.getStatus(SIMPLE_USER, null)).thenReturn(new AbsenceStatus(true, "2026-10-01", null, "Europe/Paris", "EXO", 1L, 2L));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence/status").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.enabled").value(true))
           .andExpect(jsonPath("$.start").value("2026-10-01"));
  }

  /**
   * A request made from a shared mailbox answers 403 with the own-mailbox code, on every
   * verb, and the share id reaches the service to be refused there.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void absenceFromASharedMailboxIsForbidden() throws Exception {
    IllegalAccessException refusal = new IllegalAccessException(EmailAbsenceService.OWN_MAILBOX_ONLY);
    when(emailAbsenceService.getAbsence(SIMPLE_USER, 12L, null, true)).thenThrow(refusal);
    when(emailAbsenceService.setVacation(eq(SIMPLE_USER), eq(12L), any(), eq(false))).thenThrow(refusal);
    doThrow(refusal).when(emailAbsenceService).disableVacation(SIMPLE_USER, 12L);
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence?delegationId=12").with(testSimpleUser()))
           .andExpect(status().isForbidden())
           .andExpect(status().reason(EmailAbsenceService.OWN_MAILBOX_ONLY));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/vacation?delegationId=12").with(testSimpleUser())
                                                                                       .content("{\"enabled\":true}")
                                                                                       .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isForbidden());
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/absence/vacation?delegationId=12").with(testSimpleUser()))
           .andExpect(status().isForbidden());
  }

  /**
   * The status read with a share answers the owner's dates through the delegate's read,
   * never the caller's own summary, and each refusal of the share its status: 404 for no
   * such share of the caller's, 403 for a pending share or a caller who may not use mail,
   * 410 for a share that ended.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void absenceStatusOfASharedMailboxOwner() throws Exception {
    when(emailAbsenceService.getOwnerAbsenceForDelegate(SIMPLE_USER, 12L))
        .thenReturn(new OwnerAbsenceStatus(true, "2026-10-01", "2026-10-15", "Europe/Paris", 1L, 2L, true));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence/status?delegationId=12").with(testSimpleUser()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.enabled").value(true))
           .andExpect(jsonPath("$.end").value("2026-10-15"))
           .andExpect(jsonPath("$.stale").value(true))
           .andExpect(jsonPath("$.source").doesNotExist());
    verify(emailAbsenceService, never()).getStatus(any(), any());

    when(emailAbsenceService.getOwnerAbsenceForDelegate(SIMPLE_USER, 13L)).thenThrow(new ObjectNotFoundException("not yours"));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence/status?delegationId=13").with(testSimpleUser()))
           .andExpect(status().isNotFound());
    when(emailAbsenceService.getOwnerAbsenceForDelegate(SIMPLE_USER, 14L))
        .thenThrow(new IllegalAccessException(EmailAbsenceService.SHARE_NOT_ACCEPTED));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence/status?delegationId=14").with(testSimpleUser()))
           .andExpect(status().isForbidden())
           .andExpect(status().reason(EmailAbsenceService.SHARE_NOT_ACCEPTED));
    when(emailAbsenceService.getOwnerAbsenceForDelegate(SIMPLE_USER, 15L))
        .thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence/status?delegationId=15").with(testSimpleUser()))
           .andExpect(status().isGone())
           .andExpect(status().reason(DelegationRevokedException.REVOKED));
  }

  /**
   * Each refusal of a write answers its status: 400 for a value or an unsupported
   * connector, 404 when nothing is connected, 409 with the code and the script's name,
   * 502 with the transport's code.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void absenceWriteStatuses() throws Exception {
    String body = "{\"enabled\":true,\"subject\":\"Away\",\"text\":\"Back soon\"}";
    when(emailAbsenceService.setVacation(eq(SIMPLE_USER), eq(null), any(), eq(false)))
                                                                                   .thenThrow(new IllegalArgumentException(EmailAbsenceService.INVALID_SUBJECT))
                                                                                   .thenThrow(new ServerRuleUnsupportedException(ServerRuleUnsupportedException.VACATION_UNSUPPORTED))
                                                                                   .thenThrow(new ObjectNotFoundException(EmailAbsenceService.NOT_CONNECTED))
                                                                                   .thenThrow(new ServerRuleConflictException(ServerRuleConflictException.MANAGED_ELSEWHERE,
                                                                                                                              "roundcube"))
                                                                                   .thenThrow(new ServerRuleUnavailableException(ServerRuleUnavailableException.TLS_HOST_NAME));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/vacation").with(testSimpleUser())
                                                                      .content(body)
                                                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(EmailAbsenceService.INVALID_SUBJECT));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/vacation").with(testSimpleUser())
                                                                      .content(body)
                                                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason(ServerRuleUnsupportedException.VACATION_UNSUPPORTED));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/vacation").with(testSimpleUser())
                                                                      .content(body)
                                                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isNotFound());
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/vacation").with(testSimpleUser())
                                                                      .content(body)
                                                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message").value(ServerRuleConflictException.MANAGED_ELSEWHERE))
           .andExpect(jsonPath("$.scriptName").value("roundcube"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/vacation").with(testSimpleUser())
                                                                      .content(body)
                                                                      .contentType(MediaType.APPLICATION_JSON))
           .andExpect(status().isBadGateway())
           .andExpect(status().reason(ServerRuleUnavailableException.TLS_HOST_NAME));
  }

  /**
   * Switching off answers 409 with the script's name when eXo's script changed outside
   * eXo, and a read answers 502 with the transport's code.
   *
   * @throws Exception when the request cannot be performed
   */
  @Test
  void absenceSwitchOffAndReadStatuses() throws Exception {
    doThrow(new ServerRuleConflictException(ServerRuleConflictException.MODIFIED_OUTSIDE, "exo-rules")).when(emailAbsenceService)
                                                                                                     .disableVacation(SIMPLE_USER, null);
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/absence/vacation").with(testSimpleUser()))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message").value(ServerRuleConflictException.MODIFIED_OUTSIDE))
           .andExpect(jsonPath("$.scriptName").value("exo-rules"));
    when(emailAbsenceService.getAbsence(SIMPLE_USER, null, null, true)).thenThrow(new ServerRuleUnavailableException(ServerRuleUnavailableException.SERVER_UNREACHABLE));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence").with(testSimpleUser()))
           .andExpect(status().isBadGateway())
           .andExpect(status().reason(ServerRuleUnavailableException.SERVER_UNREACHABLE));
    when(emailAbsenceService.getStatus(SIMPLE_USER, null)).thenThrow(new ObjectNotFoundException(EmailAbsenceService.DISABLED));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence/status").with(testSimpleUser()))
           .andExpect(status().isNotFound());
  }

  /**
   * The forwarding endpoints answer their refusals as the add-on does: forwarding off, a
   * domain not allowed, a destination not confirmed, a refusal of the generator, too many
   * codes or wrong tries -- 403; a destination that is not an address, a wrong or expired
   * code, a server that cannot keep a copy -- 400; another client's forward or an outside
   * edit -- 409 with the script; an unreachable server or relay -- 502.
   *
   * @throws Exception on failure
   */
  @Test
  void forwardingRefusalsAnswerTheirStatuses() throws Exception {
    String body = "{\"destination\":\"bob@example.org\",\"code\":\"123456\"}";
    when(emailForwardingService.sendCode(SIMPLE_USER, null, "bob@example.org"))
        .thenThrow(new IllegalAccessException("emailConnector.forwarding.disabled"));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/absence/forwarding/code").with(testSimpleUser())
                                                                             .contentType(MediaType.APPLICATION_JSON)
                                                                             .content(body))
           .andExpect(status().isForbidden())
           .andExpect(status().reason("emailConnector.forwarding.disabled"));
    when(emailForwardingService.confirm(SIMPLE_USER, null, "bob@example.org", "123456"))
        .thenThrow(new IllegalArgumentException("emailConnector.forwarding.code.invalid"));
    mockMvc.perform(post(USER_EMAIL_SETTING_PATH + "/absence/forwarding/confirm").with(testSimpleUser())
                                                                                .contentType(MediaType.APPLICATION_JSON)
                                                                                .content(body))
           .andExpect(status().isBadRequest())
           .andExpect(status().reason("emailConnector.forwarding.code.invalid"));
    when(emailForwardingService.setForwarding(SIMPLE_USER, null, "bob@example.org", "123456", false))
        .thenThrow(new ForwardingRefusedException(ForwardingRefusedException.NOT_AUTHORIZED));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/forwarding").with(testSimpleUser())
                                                                       .contentType(MediaType.APPLICATION_JSON)
                                                                       .content(body))
           .andExpect(status().isForbidden())
           .andExpect(status().reason(ForwardingRefusedException.NOT_AUTHORIZED));
    when(emailForwardingService.setForwarding(SIMPLE_USER, null, "bob@example.org", "123456", true))
        .thenThrow(new ServerRuleConflictException(ServerRuleConflictException.FORWARDED_ELSEWHERE, "roundcube"));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/forwarding?republish=true").with(testSimpleUser())
                                                                                        .contentType(MediaType.APPLICATION_JSON)
                                                                                        .content(body))
           .andExpect(status().isConflict())
           .andExpect(jsonPath("$.message").value(ServerRuleConflictException.FORWARDED_ELSEWHERE))
           .andExpect(jsonPath("$.scriptName").value("roundcube"));
    doThrow(new ServerRuleUnsupportedException(ServerRuleUnsupportedException.FORWARDING_UNSUPPORTED)).when(emailForwardingService)
                                                                                                   .removeForwarding(SIMPLE_USER, null, false);
    mockMvc.perform(delete(USER_EMAIL_SETTING_PATH + "/absence/forwarding").with(testSimpleUser()))
           .andExpect(status().isBadRequest());
    when(emailForwardingService.getStatus(SIMPLE_USER, 4L)).thenThrow(new IllegalAccessException(EmailAbsenceService.OWN_MAILBOX_ONLY));
    mockMvc.perform(get(USER_EMAIL_SETTING_PATH + "/absence/forwarding/status?delegationId=4").with(testSimpleUser()))
           .andExpect(status().isForbidden());
    // The reply's write answers a forward the generator refuses as a refusal too.
    when(emailAbsenceService.setVacation(eq(SIMPLE_USER), isNull(), any(), eq(false)))
        .thenThrow(new ForwardingRefusedException(ForwardingRefusedException.NOT_AUTHORIZED));
    mockMvc.perform(put(USER_EMAIL_SETTING_PATH + "/absence/vacation").with(testSimpleUser())
                                                                     .contentType(MediaType.APPLICATION_JSON)
                                                                     .content("{\"enabled\":true,\"subject\":\"s\",\"text\":\"t\"}"))
           .andExpect(status().isForbidden());
  }
}
