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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.model.EmailSignature;
import org.exoplatform.emailConnector.model.EmailSignatureLogo;
import org.exoplatform.portal.branding.BrandingService;
import org.exoplatform.portal.branding.model.Logo;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import lombok.SneakyThrows;

/**
 * The signature service in isolation: what the default is composed of, how
 * hostile profile values are neutralised, what the custom markup is allowed to
 * be, and what a mailbox disconnect leaves behind (nothing).
 */
@SpringBootTest(classes = { EmailSignatureService.class })
@ExtendWith(MockitoExtension.class)
class EmailSignatureServiceTest {

  private static final String TEST_USER = "testuser";

  @MockitoBean
  private SettingService      settingService;

  @MockitoBean
  private IdentityManager     identityManager;

  @MockitoBean
  private BrandingService     brandingService;

  @MockitoBean
  private FileService         fileService;

  @MockitoBean
  private UploadService       uploadService;

  @Autowired
  private EmailSignatureService emailSignatureService;

  /**
   * The default signature composed from a fully filled profile: the name linked
   * to the absolute profile page, the position and company on one line, the
   * city and country, the phone the platform is configured to display, and the
   * company logo pointing at the signature-image endpoint.
   */
  @Test
  void theDefaultSignatureIsComposedFromTheWholeProfile() {
    givenAProfile(profile -> {
      profile.setProperty(Profile.FULL_NAME, "Ada Lovelace");
      profile.setProperty(Profile.POSITION, "CTO");
      profile.setProperty(Profile.COMPANY, "Analytical Engines");
      profile.setProperty(Profile.CITY, "London");
      profile.setProperty(Profile.COUNTRY, "UK");
      profile.setUrl("/portal/dw/profile/ada");
    });
    givenADisplayedPhoneProperty("workPhone", "+44 123 456");
    givenACompanyLogo("company logo bytes");

    String defaultHtml = emailSignatureService.getEmailSignature(TEST_USER).getDefaultHtml();

    assertTrue(defaultHtml.contains("Ada Lovelace"), "the name is there");
    // Bold text, NOT a link. An internal profile address is upcast by the composer's
    // content-link plugin into a removable chip -- the name arrived sitting in a blue
    // box with a cross beside it -- and a profile page redirects an outside recipient
    // to a login screen, so the link was worth nothing to them either.
    assertTrue(defaultHtml.contains("<strong>Ada Lovelace</strong>"), "the name is bold");
    assertFalse(defaultHtml.contains("/portal/dw/profile/ada"), "and is not a link to the profile page");
    assertTrue(defaultHtml.contains("CTO"), "the position is there");
    assertTrue(defaultHtml.contains("Analytical Engines"), "the company is there");
    assertTrue(defaultHtml.contains("London, UK"), "city and country share a line");
    assertTrue(defaultHtml.contains("+44 123 456"), "the displayed phone is resolved through the platform's setting");
    // Blocks, not one run of lines: who, how to reach them, where, then the picture.
    // An empty block disappears rather than leaving a gap.
    assertEquals(4, defaultHtml.split("<p>", -1).length - 1, "who, how to reach them, where, then the picture");
    // The picture is INSIDE the text, and last. Inside, because that is what lets it
    // be dragged next to the name, resized, or deleted for a signature with no picture
    // at all -- none of which is possible for something appended after the text.
    assertTrue(defaultHtml.contains("<img"), "the picture is part of the signature the user edits");
    assertTrue(defaultHtml.indexOf("<img") > defaultHtml.indexOf("Ada Lovelace"), "and it comes after the words");
    assertTrue(defaultHtml.contains(EmailSignatureService.SIGNATURE_IMAGE_PATH),
               "pointing at the address the send path swaps for an embedded part");
    // Also handed over on its own, so the drawer can put it back once deleted.
    assertTrue(emailSignatureService.getEmailSignature(TEST_USER).getLogoHtml().contains(EmailSignatureService.SIGNATURE_IMAGE_PATH),
               "the drawer is given the markup it needs to re-insert the picture");
  }

  /**
   * An almost-empty profile earns an almost-empty signature: the name and the
   * logo, and not one dangling separator or empty line for the fields that are
   * not filled.
   */
  @Test
  void anAlmostEmptyProfileEarnsAnAlmostEmptySignature() {
    givenAProfile(profile -> profile.setProperty(Profile.FULL_NAME, "Ada"));
    givenACompanyLogo("company logo bytes");

    String defaultHtml = emailSignatureService.getEmailSignature(TEST_USER).getDefaultHtml();

    assertTrue(defaultHtml.contains("Ada"), "the name is there");
    assertFalse(defaultHtml.contains("·"), "no separator with nothing on either side of it");
    assertFalse(defaultHtml.contains(", "), "no half-empty location line");
    assertFalse(defaultHtml.contains("null"), "no absent field rendered as the word null");
  }

  /**
   * The position-concat defence: the platform is known to overwrite the
   * position property with a comma-joined concatenation of experiences, and the
   * signature must show a job title, not a career history.
   */
  @Test
  void aConcatenatedPositionIsReducedToItsFirstSegment() {
    givenAProfile(profile -> {
      profile.setProperty(Profile.FULL_NAME, "Ada");
      profile.setProperty(Profile.POSITION, "CTO, Analytical Engines, Differential Dreams Ltd");
    });

    String defaultHtml = emailSignatureService.getEmailSignature(TEST_USER).getDefaultHtml();

    assertTrue(defaultHtml.contains("CTO"), "the first segment is the position");
    assertFalse(defaultHtml.contains("Differential Dreams"), "the concatenated tail stays out of the signature");
  }

  /**
   * The location fallback: with no structured city/country, the free-text
   * location property carries the line; with the structured pair present, it
   * wins and the free text stays out.
   */
  @Test
  void theLocationLineFallsBackToThePlainLocationProperty() {
    givenAProfile(profile -> {
      profile.setProperty(Profile.FULL_NAME, "Ada");
      profile.setProperty(Profile.LOCATION, "Somewhere-upon-Thames");
    });

    String defaultHtml = emailSignatureService.getEmailSignature(TEST_USER).getDefaultHtml();

    assertTrue(defaultHtml.contains("Somewhere-upon-Thames"), "the plain location property is the fallback");
  }

  /**
   * The phone indirection resolves a multivalued property too: a phones-shaped
   * property stores a list of {@code key/value} maps, and the signature reads
   * the first value rather than the list's toString.
   */
  @Test
  void aMultivaluedPhonePropertyIsReadAsItsFirstValue() {
    givenAProfile(profile -> {
      profile.setProperty(Profile.FULL_NAME, "Ada");
      profile.setProperty("phones", List.of(Map.of("key", "work", "value", "+44 987"), Map.of("key", "home", "value", "+44 111")));
    });
    when(settingService.get(eq(Context.GLOBAL), any(Scope.class), eq("UserDisplayedPhonePropertySetting")))
                                                                                                           .thenReturn((SettingValue) SettingValue.create("phones"));

    String defaultHtml = emailSignatureService.getEmailSignature(TEST_USER).getDefaultHtml();

    assertTrue(defaultHtml.contains("+44 987"), "the first stored value is the displayed phone");
    assertFalse(defaultHtml.contains("+44 111"), "and only the first");
    assertFalse(defaultHtml.contains("{"), "never the list's own toString");
  }

  /**
   * No phone setting configured means no phone line — the setting is the ONLY
   * thing that says which property is a phone, so its absence cannot be guessed
   * around.
   */
  @Test
  void noDisplayedPhoneSettingMeansNoPhoneLine() {
    givenAProfile(profile -> {
      profile.setProperty(Profile.FULL_NAME, "Ada");
      profile.setProperty("workPhone", "+44 123 456");
    });

    String defaultHtml = emailSignatureService.getEmailSignature(TEST_USER).getDefaultHtml();

    assertFalse(defaultHtml.contains("+44 123 456"), "no setting, no phone line");
  }

  /**
   * The XSS defence the EXO-89334 lesson demands: profile values are
   * user-editable strings, and a full name that IS an attack must land in the
   * signature as text — visible, harmless, and demonstrably not an element.
   */
  @Test
  void aHostileFullNameArrivesAsTextNotAsAnElement() {
    givenAProfile(profile -> profile.setProperty(Profile.FULL_NAME, "<img src=x onerror=alert(1)>"));

    String defaultHtml = emailSignatureService.getEmailSignature(TEST_USER).getDefaultHtml();

    assertFalse(defaultHtml.contains("<img src=x"), "the payload never becomes markup");
    assertTrue(defaultHtml.contains("&lt;img src=x onerror=alert(1)&gt;"), "it reads as the string it is");
  }

  /**
   * Custom markup is sanitized at save: a script pasted into the signature
   * editor is stripped before the document is stored, so every render that
   * follows reads an already-safe string.
   */
  @Test
  void aCustomSignatureIsSanitizedAtSave() {
    emailSignatureService.saveEmailSignature(TEST_USER,
                                             new EmailSignature(true,
                                                                "<p>fine</p><script>alert(1)</script>",
                                                                null,
                                                                false, null));

    ArgumentCaptor<SettingValue> stored = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(eq(Context.USER.id(TEST_USER)),
                               eq(UserEmailSettingService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailSignatureService.EMAIL_SIGNATURE_KEY),
                               stored.capture());
    String storedJson = stored.getValue().getValue().toString();
    assertTrue(storedJson.contains("fine"), "the honest part of the markup survives");
    assertFalse(storedJson.contains("<script>"), "the script does not");
    assertFalse(storedJson.contains("alert(1)"), "not even as text");
  }

  /**
   * The size cap: markup past 20KB answers the 400-contract exception with its
   * message code, and stores nothing.
   */
  @Test
  void aTooLongCustomSignatureIsRefused() {
    String hugeHtml = "<p>" + "x".repeat(EmailSignatureService.MAX_SIGNATURE_HTML_LENGTH) + "</p>";

    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailSignatureService.saveEmailSignature(TEST_USER,
                                                                                                 new EmailSignature(true,
                                                                                                                    hugeHtml,
                                                                                                                    null,
                                                                                                                    false, null)));

    assertEquals("emailConnector.signature.tooLong", refusal.getMessage(), "the message code the front-end translates");
    verify(settingService, never()).set(any(), any(), anyString(), any());
  }

  /**
   * The save/get round trip through the settings store: what was saved is what
   * comes back, on the SEPARATE key that keeps it clear of the mailbox
   * settings document and its copy-every-field rebuild.
   */
  @Test
  void whatWasSavedIsWhatComesBack() {
    ArgumentCaptor<SettingValue> stored = ArgumentCaptor.forClass(SettingValue.class);
    emailSignatureService.saveEmailSignature(TEST_USER, new EmailSignature(false, "<p>mine</p>", null, false, null));
    verify(settingService).set(eq(Context.USER.id(TEST_USER)),
                               eq(UserEmailSettingService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailSignatureService.EMAIL_SIGNATURE_KEY),
                               stored.capture());
    when(settingService.get(Context.USER.id(TEST_USER),
                            UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                            EmailSignatureService.EMAIL_SIGNATURE_KEY)).thenReturn(stored.getValue());
    givenAProfile(profile -> profile.setProperty(Profile.FULL_NAME, "Ada"));

    EmailSignature readBack = emailSignatureService.getEmailSignature(TEST_USER);

    assertEquals(false, readBack.getEnabled(), "the switch came back");
    assertEquals("<p>mine</p>", readBack.getCustomHtml(), "the markup came back");
    assertNotNull(readBack.getDefaultHtml(), "and the default is computed alongside, never stored");
  }

  /**
   * The image the send path embeds: the user's own file when they set one, the
   * branding logo otherwise, and null when neither has bytes — the send path's
   * signal to remove the image rather than mail a broken frame.
   */
  @Test
  @SneakyThrows
  void theSignatureLogoIsTheCustomFileThenTheCompanyLogoThenNothing() {
    givenACompanyLogo("company logo bytes");
    EmailSignatureLogo companyLogo = emailSignatureService.getSignatureLogo(TEST_USER);
    assertEquals("company logo bytes", new String(companyLogo.bytes(), StandardCharsets.UTF_8),
                 "with nothing stored, the branding logo is the image");

    givenAStoredSetting("{\"enabled\":true,\"logoFileId\":42}");
    when(fileService.getFile(42L)).thenReturn(fileItemOf(42L, "my own logo", "image/jpeg"));
    EmailSignatureLogo customLogo = emailSignatureService.getSignatureLogo(TEST_USER);
    assertEquals("my own logo", new String(customLogo.bytes(), StandardCharsets.UTF_8), "the custom file wins");
    assertEquals("image/jpeg", customLogo.mimeType(), "with its honest content type");

    givenAStoredSetting(null);
    when(brandingService.getLogo()).thenReturn(null);
    assertNull(emailSignatureService.getSignatureLogo(TEST_USER), "no custom file and no branding logo is no image at all");
  }

  /**
   * Uploading an image through the cropper: a fresh file is written (the file
   * id doubles as the URL's cache version), the replaced one is deleted, and
   * the new id is stored.
   */
  @Test
  @SneakyThrows
  void aCroppedUploadReplacesTheSignatureImage(@TempDir Path tempDir) {
    givenAStoredSetting("{\"enabled\":true,\"logoFileId\":42}");
    givenAnUpload("up1", tempDir, "cropped bytes", "image/png");
    when(fileService.writeFile(any(FileItem.class))).thenReturn(fileItemOf(43L, "cropped bytes", "image/png"));

    emailSignatureService.saveSignatureLogo(TEST_USER, "up1");

    verify(fileService).deleteFile(42L);
    ArgumentCaptor<SettingValue> stored = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(eq(Context.USER.id(TEST_USER)),
                               eq(UserEmailSettingService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailSignatureService.EMAIL_SIGNATURE_KEY),
                               stored.capture());
    assertTrue(stored.getValue().getValue().toString().contains("43"), "the fresh file's id is what the document holds");
  }

  /**
   * The two refusals of the image upload: an upload that is gone, and a file
   * that is not an image — both the caller's 400, neither a stored change.
   */
  @Test
  @SneakyThrows
  void aDeadOrNonImageUploadIsRefused(@TempDir Path tempDir) {
    assertThrows(IllegalArgumentException.class, () -> emailSignatureService.saveSignatureLogo(TEST_USER, "gone"),
                 "an expired upload cannot become the signature image");

    givenAnUpload("up2", tempDir, "not a picture", "application/pdf");
    assertThrows(IllegalArgumentException.class, () -> emailSignatureService.saveSignatureLogo(TEST_USER, "up2"),
                 "a PDF cannot become the signature image");
    verify(settingService, never()).set(any(), any(), anyString(), any());
  }

  /**
   * The mailbox-disconnect cleanup: the stored document goes, and the user's
   * own image file goes with it — nothing else would ever delete that file.
   */
  @Test
  void disconnectingTheMailboxDeletesTheSignatureAndItsImageFile() {
    givenAStoredSetting("{\"enabled\":true,\"customHtml\":\"<p>mine</p>\",\"logoFileId\":42}");

    emailSignatureService.deleteEmailSignature(TEST_USER);

    verify(fileService).deleteFile(42L);
    verify(settingService).remove(Context.USER.id(TEST_USER),
                                  UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                  EmailSignatureService.EMAIL_SIGNATURE_KEY);
  }

  /**
   * A profile for the test user, shaped by the given customizer.
   *
   * @param customizer what the profile should carry
   */
  private void givenAProfile(java.util.function.Consumer<Profile> customizer) {
    Identity identity = new Identity("organization", TEST_USER);
    Profile profile = new Profile(identity);
    customizer.accept(profile);
    identity.setProfile(profile);
    when(identityManager.getOrCreateUserIdentity(TEST_USER)).thenReturn(identity);
  }

  /**
   * Configures the platform's displayed-phone indirection: the global setting
   * names the property, the profile carries the value.
   *
   * @param propertyName the profile property the setting names
   * @param phone the value that property holds
   */
  private void givenADisplayedPhoneProperty(String propertyName, String phone) {
    when(settingService.get(eq(Context.GLOBAL), any(Scope.class), eq("UserDisplayedPhonePropertySetting")))
                                                                                                           .thenReturn((SettingValue) SettingValue.create(propertyName));
    Identity identity = identityManager.getOrCreateUserIdentity(TEST_USER);
    identity.getProfile().setProperty(propertyName, phone);
  }

  /**
   * A branding logo with the given bytes.
   *
   * @param content what the logo file holds
   */
  private void givenACompanyLogo(String content) {
    Logo logo = new Logo();
    logo.setData(content.getBytes(StandardCharsets.UTF_8));
    when(brandingService.getLogo()).thenReturn(logo);
  }

  /**
   * A stored signature document, or none when null.
   *
   * @param json the stored document
   */
  private void givenAStoredSetting(String json) {
    when(settingService.get(Context.USER.id(TEST_USER),
                            UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                            EmailSignatureService.EMAIL_SIGNATURE_KEY))
                                                                      .thenReturn(json == null ? null
                                                                                               : (SettingValue) SettingValue.create(json));
  }

  /**
   * An upload the cropper would have produced, as a real temporary file so the
   * service's byte read is the real one.
   *
   * @param uploadId the upload's id
   * @param tempDir where the file may live
   * @param content the uploaded bytes
   * @param mimeType the uploaded type
   */
  @SneakyThrows
  private void givenAnUpload(String uploadId, Path tempDir, String content, String mimeType) {
    Path file = tempDir.resolve(uploadId + ".bin");
    Files.writeString(file, content);
    UploadResource upload = new UploadResource(uploadId);
    upload.setStoreLocation(file.toString());
    upload.setMimeType(mimeType);
    when(uploadService.getUploadResource(uploadId)).thenReturn(upload);
  }

  /**
   * A {@link FileItem} over the given text, with its file info carrying the id
   * and content type the service reads back.
   *
   * @param fileId the stored file's id
   * @param content the bytes the file holds
   * @param mimeType its content type
   * @return the file item
   */
  @SneakyThrows
  private FileItem fileItemOf(long fileId, String content, String mimeType) {
    FileItem fileItem = new FileItem(fileId,
                                     "signature-logo",
                                     mimeType,
                                     "emailConnector",
                                     content.length(),
                                     new Date(),
                                     TEST_USER,
                                     false,
                                     new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    FileInfo fileInfo = fileItem.getFileInfo();
    fileInfo.setId(fileId);
    return fileItem;
  }
}
