/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.mail.internet.InternetAddress;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.exoplatform.commons.utils.ListAccess;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.organization.OrganizationService;
import org.exoplatform.services.organization.Query;
import org.exoplatform.services.organization.User;
import org.exoplatform.services.organization.UserHandler;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * Who a mail address belongs to on the platform (EXO-90891): the account's address
 * whatever its case, else a connected mailbox's; enabled, undeleted users only; one
 * resolution per address for the cache's lifetime, shared by the mail list's batch and
 * the reader. Then, for an address with no platform photo, the viewing user's own
 * contact's picture (EXO-90908), kept per viewer.
 */
@ExtendWith(MockitoExtension.class)
class EmailSenderProfileServiceTest {

  @Mock
  private OrganizationService     organizationService;

  @Mock
  private UserHandler             userHandler;

  @Mock
  private IdentityManager         identityManager;

  @Mock
  private UserEmailSettingService userEmailSettingService;

  @Mock
  private EmailBoxStorage         emailBoxStorage;

  @Mock
  private EmailContactService     emailContactService;

  @Mock
  private SenderLogoService       senderLogoService;

  @InjectMocks
  private EmailSenderProfileService service;

  // What the directory holds: account address as stored -> login; and the queries it was asked.
  private final Map<String, String> accounts = new HashMap<>();

  private final List<String>        queries  = new ArrayList<>();

  /**
   * A directory that answers an address by equality on the stored value, case included
   * (a case-sensitive collation), and an address with a trailing * as a lower-cased
   * prefix -- the two paths the platform's user store takes.
   *
   * @throws Exception never
   */
  @BeforeEach
  void directory() throws Exception {
    lenient().when(organizationService.getUserHandler()).thenReturn(userHandler);
    lenient().when(userHandler.findUsersByQuery(any(Query.class))).thenAnswer(invocation -> {
      String email = ((Query) invocation.getArgument(0)).getEmail();
      queries.add(email);
      List<User> found = new ArrayList<>();
      accounts.forEach((stored, login) -> {
        boolean matches = email.endsWith("*") ? stored.toLowerCase().startsWith(email.substring(0, email.length() - 1).toLowerCase())
                                              : stored.equals(email);
        if (matches) {
          found.add(user(login, stored));
        }
      });
      return listOf(found.toArray(new User[0]));
    });
    lenient().when(userEmailSettingService.getConnectedMailboxOwners()).thenReturn(Map.of());
  }

  @AfterEach
  void forgetResolver() {
    EmailConnectorUtils.setSenderProfileResolver(null);
    EmailConnectorUtils.setContactPhotoResolver(null);
  }

  /** The platform account's address is matched whatever the case either side writes it in. */
  @Test
  void anAccountAddressIsMatchedWhateverItsCase() {
    accounts.put("Bob.Martin@Example.org", "bob");
    accounts.put("bob.martin@example.org.uk", "impostor");
    Profile bob = enabledUser("bob", "/portal/rest/v1/social/users/12/avatar?byId=true");

    assertSame(bob, service.resolveSenderProfile("BOB.martin@example.ORG"));
    // Equality first, then the case-insensitive prefix, narrowed to the same address.
    assertEquals(List.of("bob.martin@example.org", "bob.martin@example.org*"), queries);
  }

  /** An account found by equality is not asked for again with the wildcard. */
  @Test
  void anExactAccountAddressCostsOneQuery() {
    accounts.put("ann@example.org", "ann");
    Profile ann = enabledUser("ann", "ann-avatar");

    assertSame(ann, service.resolveSenderProfile("ann@example.org"));
    assertEquals(List.of("ann@example.org"), queries);
  }

  /** A colleague writing from the mailbox they connected, not their account's address, is recognised. */
  @Test
  void aConnectedMailboxAddressNamesItsOwner() {
    accounts.put("carol@acme.org", "carol");
    when(userEmailSettingService.getConnectedMailboxOwners()).thenReturn(Map.of("carol.private@gmail.com", "carol"));
    Profile carol = enabledUser("carol", "carol-avatar");

    assertSame(carol, service.resolveSenderProfile("Carol.Private@Gmail.com"));
    // The in-memory mailboxes answer before the case-insensitive scan is run.
    assertEquals(List.of("carol.private@gmail.com"), queries);
  }

  /**
   * In a batch, a connected mailbox's address names its owner only to a user whose
   * mailbox holds mail from it: the address lives in its owner's private settings. An
   * account address names its user to anybody.
   */
  @Test
  void theBatchNamesAConnectedMailboxOnlyToAUserHoldingMailFromIt() {
    accounts.put("carol@acme.org", "carol");
    when(userEmailSettingService.getConnectedMailboxOwners()).thenReturn(Map.of("carol.private@gmail.com", "carol"));
    enabledUser("carol", "carol-avatar");
    when(emailBoxStorage.hasMailFrom("rita", "carol.private@gmail.com")).thenReturn(true);

    assertEquals(Map.of("carol.private@gmail.com", "carol-avatar"), service.resolveAvatars(List.of("carol.private@gmail.com"), "rita"));
    assertEquals(Map.of(), service.resolveAvatars(List.of("carol.private@gmail.com"), "mallory"));
    assertEquals(Map.of("carol@acme.org", "carol-avatar"), service.resolveAvatars(List.of("carol@acme.org"), "mallory"));
    verify(emailBoxStorage, never()).hasMailFrom(any(), org.mockito.ArgumentMatchers.eq("carol@acme.org"));
  }

  /** A directory that cannot be read answers nobody this time, and is asked again next time. */
  @Test
  void aDirectoryFailureIsNotRememberedAsNobody() throws Exception {
    accounts.put("bob@example.org", "bob");
    Profile bob = enabledUser("bob", "bob-avatar");
    when(userHandler.findUsersByQuery(any(Query.class))).thenThrow(new IllegalStateException("down"))
                                                        .thenAnswer(invocation -> listOf(user("bob", "bob@example.org")));

    assertNull(service.resolveSenderProfile("bob@example.org"));
    assertSame(bob, service.resolveSenderProfile("bob@example.org"));
  }

  /** An address nobody holds answers nobody, and so does a deleted or disabled user's. */
  @Test
  void anUnknownAddressOrAnInactiveUserAnswersNobody() {
    assertNull(service.resolveSenderProfile("stranger@client.org"));

    accounts.put("gone@example.org", "gone");
    Identity deleted = mock(Identity.class);
    when(deleted.isDeleted()).thenReturn(true);
    when(identityManager.getOrCreateUserIdentity("gone")).thenReturn(deleted);
    assertNull(service.resolveSenderProfile("gone@example.org"));

    accounts.put("off@example.org", "off");
    Identity disabled = mock(Identity.class);
    when(disabled.isEnable()).thenReturn(false);
    when(identityManager.getOrCreateUserIdentity("off")).thenReturn(disabled);
    assertNull(service.resolveSenderProfile("off@example.org"));

    assertNull(service.resolveSenderProfile(null));
    assertNull(service.resolveSenderProfile("not an address"));
  }

  /** One resolution per address, nobody included: the list scrolled again and the reader ask the directory nothing more. */
  @Test
  void anAddressIsResolvedOnceForTheCachesLifetime() throws Exception {
    accounts.put("bob@example.org", "bob");
    enabledUser("bob", "bob-avatar");

    service.resolveSenderProfile("bob@example.org");
    service.resolveSenderProfile("BOB@example.org");
    service.resolveAvatars(List.of("bob@example.org", "stranger@client.org"), "viewer");
    service.resolveAvatars(List.of("stranger@client.org"), "viewer");

    assertEquals(List.of("bob@example.org", "stranger@client.org", "stranger@client.org*"), queries);
    verify(userEmailSettingService, times(1)).getConnectedMailboxOwners();
  }

  /** The batch answers the colleagues' pictures by normalized address, skips the rest, each address once. */
  @Test
  void theBatchAnswersTheColleaguesPicturesOnly() {
    accounts.put("bob@example.org", "bob");
    enabledUser("bob", "/portal/rest/v1/social/users/12/avatar?byId=true");

    Map<String, String> avatars = service.resolveAvatars(Arrays.asList(" BOB@example.org", "bob@example.org", "ann@client.org",
                                                                    "", null, "not-an-address", "half@typed"), "viewer");

    assertEquals(Map.of("bob@example.org", "/portal/rest/v1/social/users/12/avatar?byId=true"), avatars);
    assertEquals(List.of("bob@example.org", "ann@client.org", "ann@client.org*"), queries);
  }

  /**
   * EXO-90893 -- an address no platform user holds is answered its brand logo when the
   * asking user holds genuine mail from it, and only then; a colleague's photo always
   * wins, and an address the logo service cannot offer costs no mail query.
   */
  @Test
  void theBatchAnswersABrandLogoForGenuineMailOnly() {
    String logo = SenderLogoService.LOGO_PATH + "brand.example?t=x";
    when(senderLogoService.mayOffer(anyString())).thenAnswer(invocation -> !"noise@other.example".equals(invocation.getArgument(0)));
    when(emailBoxStorage.hasVerifiedMailFrom("viewer", "news@brand.example")).thenReturn(true);
    when(senderLogoService.logoUrlFor("news@brand.example", true, "viewer")).thenReturn(logo);

    Map<String, String> avatars = service.resolveAvatars(List.of("news@brand.example", "spoof@bank.example", "noise@other.example"),
                                                         "viewer");

    assertEquals(Map.of("news@brand.example", logo), avatars);
    verify(senderLogoService).logoUrlFor("spoof@bank.example", false, "viewer");
    verify(emailBoxStorage, never()).hasVerifiedMailFrom("viewer", "noise@other.example");
    accounts.put("bob@example.org", "bob");
    enabledUser("bob", "bob-avatar");
    assertEquals(Map.of("bob@example.org", "bob-avatar"), service.resolveAvatars(List.of("bob@example.org"), "viewer"));
    verify(senderLogoService, never()).logoUrlFor(eq("bob@example.org"), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
  }

  /**
   * EXO-90893 with EXO-90908 -- the avatar order: the viewer's own contact photo comes
   * before a brand logo, and a platform user without a photo keeps the platform's
   * generated picture, never a brand.
   */
  @Test
  void aContactPhotoAndAColleagueComeBeforeTheBrand() {
    String logo = SenderLogoService.LOGO_PATH + "brand.example?t=x";
    lenient().when(senderLogoService.mayOffer(anyString())).thenReturn(true);
    lenient().when(emailBoxStorage.hasVerifiedMailFrom(anyString(), anyString())).thenReturn(true);
    lenient().when(senderLogoService.logoUrlFor(anyString(), org.mockito.ArgumentMatchers.anyBoolean(), anyString())).thenReturn(logo);
    when(emailContactService.getContactPhotoUrls(eq("viewer"), any())).thenReturn(Map.of("ann@brand.example", "ann-contact"));
    accounts.put("carl@brand.example", "carl");
    defaultAvatarUser("carl", "carl-generated");

    Map<String, String> avatars = service.resolveAvatars(List.of("ann@brand.example", "carl@brand.example", "news@brand.example"),
                                                         "viewer");

    assertEquals("ann-contact", avatars.get("ann@brand.example"), "the viewer's contact first");
    assertEquals("carl-generated", avatars.get("carl@brand.example"), "a colleague is never a brand");
    assertEquals(logo, avatars.get("news@brand.example"));
    verify(senderLogoService, never()).logoUrlFor(eq("ann@brand.example"), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
    verify(senderLogoService, never()).logoUrlFor(eq("carl@brand.example"), org.mockito.ArgumentMatchers.anyBoolean(), anyString());
  }

  /** Past the cap the batch is refused before a single directory query; the cap itself is taken. */
  @Test
  void theBatchRefusesMoreAddressesThanTheCap() throws Exception {
    List<String> tooMany = new ArrayList<>();
    for (int i = 0; i <= EmailSenderProfileService.AVATARS_MAX_ADDRESSES; i++) {
      tooMany.add("user" + i + "@example.org");
    }
    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> service.resolveAvatars(tooMany, "viewer"));
    assertEquals(EmailSenderProfileService.AVATARS_TOO_MANY, refused.getMessage());
    verify(userHandler, never()).findUsersByQuery(any(Query.class));

    assertTrue(service.resolveAvatars(tooMany.subList(0, EmailSenderProfileService.AVATARS_MAX_ADDRESSES), "viewer").isEmpty());
    assertTrue(service.resolveAvatars(null, "viewer").isEmpty());
  }

  /** A wildcard written into the address finds nobody else: the answer must hold that very address. */
  @Test
  void aWildcardInTheAddressFindsNobodyElse() throws Exception {
    // The store reads a * as a wildcard: a*@example.org finds Alice.
    ListAccess<User> alice = listOf(user("alice", "alice@example.org"));
    when(userHandler.findUsersByQuery(argThat(query -> query != null && query.getEmail().contains("*")))).thenReturn(alice);

    assertNull(service.resolveSenderProfile("a*@example.org"));
  }

  /** The reader resolves its sender through the service once it is registered: a connected mailbox's owner shows. */
  @Test
  void theReaderResolvesItsSenderThroughTheService() throws Exception {
    when(userEmailSettingService.getConnectedMailboxOwners()).thenReturn(Map.of("carol.private@gmail.com", "carol"));
    Profile carol = enabledUser("carol", "carol-avatar");
    lenient().when(carol.getUrl()).thenReturn("carol-profile");

    EmailConnectorUtils.setSenderProfileResolver(service::resolveSenderProfile);
    EmailSender sender = EmailConnectorUtils.getEmailSender(new InternetAddress("Carol.Private@gmail.com", "Carol"), true);

    assertEquals("carol-avatar", sender.getAvatarUrl());
    assertEquals("carol-profile", sender.getProfileUrl());
  }

  /** The reader's recipients go through the service too: the reading user's own connected mailbox reads as them. */
  @Test
  void theReadersRecipientsResolveThroughTheService() throws Exception {
    when(userEmailSettingService.getConnectedMailboxOwners()).thenReturn(Map.of("rita.private@gmail.com", "rita"));
    Profile rita = enabledUser("rita", "rita-avatar");
    Identity ritaIdentity = mock(Identity.class);
    lenient().when(ritaIdentity.getRemoteId()).thenReturn("rita");
    lenient().when(rita.getIdentity()).thenReturn(ritaIdentity);
    lenient().when(rita.getUrl()).thenReturn("rita-profile");

    EmailConnectorUtils.setSenderProfileResolver(service::resolveSenderProfile);
    List<org.exoplatform.emailConnector.model.EmailRecipient> recipients =
        EmailConnectorUtils.getEmailRecipients(new InternetAddress[] { new InternetAddress("Rita.Private@gmail.com") }, "rita", true);

    assertEquals("rita-profile", recipients.get(0).getProfileUrl());
    assertTrue(recipients.get(0).isCurrentUser());
  }

  /**
   * The service plugs itself into the reader when the container builds it, through its
   * annotated entry point, and unplugs when it goes.
   *
   * @throws Exception never
   */
  @Test
  void theServicePlugsItselfIntoTheReader() throws Exception {
    try (org.mockito.MockedStatic<EmailConnectorUtils> utils = org.mockito.Mockito.mockStatic(EmailConnectorUtils.class)) {
      service.register();
      utils.verify(() -> EmailConnectorUtils.setSenderProfileResolver(org.mockito.ArgumentMatchers.notNull()));
      utils.verify(() -> EmailConnectorUtils.setContactPhotoResolver(org.mockito.ArgumentMatchers.notNull()));
      service.unregister();
      utils.verify(() -> EmailConnectorUtils.setSenderProfileResolver(null));
      utils.verify(() -> EmailConnectorUtils.setContactPhotoResolver(null));
    }
    assertTrue(EmailSenderProfileService.class.getMethod("register").isAnnotationPresent(jakarta.annotation.PostConstruct.class));
    assertTrue(EmailSenderProfileService.class.getMethod("unregister").isAnnotationPresent(jakarta.annotation.PreDestroy.class));
  }

  /** The two entry points set the container they need: a job or an MCP call may reach them with none. */
  @Test
  void theEntryPointsSetTheirContainer() throws Exception {
    assertTrue(EmailSenderProfileService.class.getMethod("getSenderProfile", String.class)
                                              .isAnnotationPresent(io.meeds.common.ContainerTransactional.class));
    assertTrue(EmailSenderProfileService.class.getMethod("getAvatars", List.class, String.class)
                                              .isAnnotationPresent(io.meeds.common.ContainerTransactional.class));
    assertTrue(EmailSenderProfileService.class.getMethod("getContactPhotoUrl", String.class, String.class)
                                              .isAnnotationPresent(io.meeds.common.ContainerTransactional.class));
  }

  /**
   * The order of the batch's pictures (EXO-90908): a platform user's own photo; else
   * the viewer's own contact's; else the platform's generated picture of a user with
   * no photo; else nothing, for the client's initials. The contacts are asked once,
   * for the addresses with no platform photo only.
   */
  @Test
  void theBatchPicksThePlatformPhotoThenTheViewersContactThenTheGeneratedPicture() {
    accounts.put("bob@example.org", "bob");
    enabledUser("bob", "bob-photo");
    accounts.put("dan@example.org", "dan");
    defaultAvatarUser("dan", "dan-generated");
    accounts.put("eve@example.org", "eve");
    defaultAvatarUser("eve", "eve-generated");
    when(emailContactService.getContactPhotoUrls("alice", List.of("dan@example.org", "eve@example.org",
                                                                  "ann@client.org", "nobody@client.org")))
        .thenReturn(Map.of("bob@example.org", "bob-contact", "dan@example.org", "dan-contact", "ann@client.org", "ann-contact"));

    Map<String, String> avatars = service.resolveAvatars(List.of("bob@example.org", "dan@example.org", "eve@example.org",
                                                                 "ann@client.org", "nobody@client.org"), "alice");

    assertEquals(Map.of("bob@example.org", "bob-photo",
                        "dan@example.org", "dan-contact",
                        "eve@example.org", "eve-generated",
                        "ann@client.org", "ann-contact"), avatars);
    verify(emailContactService, times(1)).getContactPhotoUrls(any(), any());
  }

  /**
   * A contact's picture is the viewer's: another user asking for the same address
   * reads their own store, never the first viewer's answer from the cache, and each
   * viewer's answer, a picture or none, is kept for the cache's lifetime.
   */
  @Test
  void contactPicturesAreKeptPerViewer() {
    when(emailContactService.getContactPhotoUrls("alice", List.of("ann@client.org"))).thenReturn(Map.of("ann@client.org", "alice-ann"));

    assertEquals(Map.of("ann@client.org", "alice-ann"), service.resolveAvatars(List.of("ann@client.org"), "alice"));
    assertEquals(Map.of(), service.resolveAvatars(List.of("ann@client.org"), "mallory"));
    assertEquals(Map.of("ann@client.org", "alice-ann"), service.resolveAvatars(List.of("ANN@client.org"), "alice"));
    assertEquals(Map.of(), service.resolveAvatars(List.of("ann@client.org"), "mallory"));
    assertEquals("alice-ann", service.resolveContactPhotoUrl("alice", "Ann@Client.org"));
    assertNull(service.resolveContactPhotoUrl("mallory", "ann@client.org"));

    verify(emailContactService, times(1)).getContactPhotoUrls("alice", List.of("ann@client.org"));
    verify(emailContactService, times(1)).getContactPhotoUrls("mallory", List.of("ann@client.org"));
  }

  /** A store that could not be read shows no contact picture this time, and is read again next time. */
  @Test
  void aContactStoreFailureIsNotRememberedAsNoPicture() {
    when(emailContactService.getContactPhotoUrls("alice", List.of("ann@client.org"))).thenThrow(new IllegalStateException("down"))
                                                                                     .thenReturn(Map.of("ann@client.org", "alice-ann"));

    assertEquals(Map.of(), service.resolveAvatars(List.of("ann@client.org"), "alice"));
    assertEquals(Map.of("ann@client.org", "alice-ann"), service.resolveAvatars(List.of("ann@client.org"), "alice"));
  }

  /**
   * The reader's sender takes the same order, with the reading user's contacts: a
   * platform photo; else the reader's contact's picture, for a colleague with no photo
   * as for an outsider; else the generated picture, else the initials. Without a
   * reader no contact is read.
   *
   * @throws Exception never
   */
  @Test
  void theReadersSenderTakesTheReadersContactPictureAfterThePlatformPhoto() throws Exception {
    accounts.put("bob@example.org", "bob");
    enabledUser("bob", "bob-photo");
    accounts.put("dan@example.org", "dan");
    defaultAvatarUser("dan", "dan-generated");
    accounts.put("eve@example.org", "eve");
    defaultAvatarUser("eve", "eve-generated");
    lenient().when(emailContactService.getContactPhotoUrls(any(), any())).thenReturn(Map.of());
    when(emailContactService.getContactPhotoUrls("alice", List.of("dan@example.org"))).thenReturn(Map.of("dan@example.org", "dan-contact"));
    when(emailContactService.getContactPhotoUrls("alice", List.of("ann@client.org"))).thenReturn(Map.of("ann@client.org", "ann-contact"));
    EmailConnectorUtils.setSenderProfileResolver(service::resolveSenderProfile);
    EmailConnectorUtils.setContactPhotoResolver(service::resolveContactPhotoUrl);

    assertEquals("bob-photo", EmailConnectorUtils.getEmailSender(new InternetAddress("bob@example.org", "Bob"), true, "alice").getAvatarUrl());
    assertEquals("dan-contact", EmailConnectorUtils.getEmailSender(new InternetAddress("dan@example.org", "Dan"), true, "alice").getAvatarUrl());
    assertEquals("eve-generated", EmailConnectorUtils.getEmailSender(new InternetAddress("eve@example.org", "Eve"), true, "alice").getAvatarUrl());
    assertEquals("ann-contact", EmailConnectorUtils.getEmailSender(new InternetAddress("ann@client.org", "Ann"), true, "alice").getAvatarUrl());
    assertTrue(EmailConnectorUtils.getEmailSender(new InternetAddress("ann@client.org", "Ann"), true, "mallory")
                                  .getAvatarUrl()
                                  .startsWith("data:"));
    assertTrue(EmailConnectorUtils.getEmailSender(new InternetAddress("ann@client.org", "Ann"), true).getAvatarUrl().startsWith("data:"));
    verify(emailContactService, never()).getContactPhotoUrls(any(), org.mockito.ArgumentMatchers.eq(List.of("bob@example.org")));
  }

  /**
   * An enabled user with a profile and a picture.
   *
   * @param login the eXo login
   * @param avatarUrl the profile's picture
   * @return the profile
   */
  private Profile enabledUser(String login, String avatarUrl) {
    Identity identity = mock(Identity.class);
    lenient().when(identity.isEnable()).thenReturn(true);
    Profile profile = mock(Profile.class);
    lenient().when(profile.getAvatarUrl()).thenReturn(avatarUrl);
    lenient().when(identity.getProfile()).thenReturn(profile);
    lenient().when(identityManager.getOrCreateUserIdentity(login)).thenReturn(identity);
    return profile;
  }

  /**
   * An enabled user whose profile carries the platform's generated picture, no photo of
   * their own.
   *
   * @param login the eXo login
   * @param avatarUrl the generated picture's URL
   * @return the profile
   */
  private Profile defaultAvatarUser(String login, String avatarUrl) {
    Profile profile = enabledUser(login, avatarUrl);
    lenient().when(profile.isDefaultAvatar()).thenReturn(true);
    return profile;
  }

  /**
   * A directory user.
   *
   * @param login the eXo login
   * @param email the account's address, as stored
   * @return the user
   */
  private User user(String login, String email) {
    User user = mock(User.class);
    lenient().when(user.getUserName()).thenReturn(login);
    lenient().when(user.getEmail()).thenReturn(email);
    return user;
  }

  /**
   * The directory's answer, as the platform's IDM list access behaves: it reports its
   * size, and refuses a load past it ("Try to get more than number users can retrieve").
   *
   * @param users the users found
   * @return the list access
   */
  private ListAccess<User> listOf(User... users) {
    return new ListAccess<User>() {
      /**
       * Loads a slice, refused past the size as the IDM list refuses it.
       *
       * @param index the first user
       * @param length how many users
       * @return the slice
       */
      @Override
      public User[] load(int index, int length) {
        if (index + length > users.length) {
          throw new IllegalArgumentException("Try to get more than number users can retrieve");
        }
        return java.util.Arrays.copyOfRange(users, index, index + length);
      }

      /**
       * The number of users found.
       *
       * @return the size
       */
      @Override
      public int getSize() {
        return users.length;
      }
    };
  }

  /**
   * EXO-90893 -- a directory query matching fewer users than the candidates read is
   * loaded up to its own size, never past it, which the IDM list refuses: the address
   * names its user instead of failing as "nobody this time", and an empty answer reads
   * nobody without a load at all.
   */
  @Test
  void aDirectoryAnswerIsLoadedWithinItsSize() {
    accounts.put("Bob@Example.org", "bob");
    enabledUser("bob", "bob-avatar");
    assertEquals("bob-avatar", service.resolveAvatars(List.of("bob@example.org"), "viewer").get("bob@example.org"));
    assertNull(service.resolveSenderProfile("nobody@example.org"));
  }
}
