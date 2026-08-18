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

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.HTMLSanitizer;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.emailConnector.model.EmailSignature;
import org.exoplatform.emailConnector.model.EmailSignatureLogo;
import org.exoplatform.emailConnector.model.EmailSignatureSetting;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.portal.branding.BrandingService;
import org.exoplatform.portal.branding.model.Logo;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.social.util.JsonUtils;

/**
 * The user's email signature: the block the composer opens with at the bottom
 * of every message they write.
 * <p>
 * Two halves, and the split is the design. The DEFAULT signature is computed
 * here on every read, from the profile as it stands — name, position, company,
 * location, displayed phone — so it follows the profile with nothing to keep in
 * step; only the user's own edits are stored. The IMAGE is the company's
 * branding logo unless the user replaced it with one of their own, and its
 * bytes are handed to the send path to travel INSIDE the message (see
 * {@link #getSignatureLogo}) — a platform URL is behind a login and a
 * {@code data:} URI is stripped by Gmail and Outlook, so embedding the bytes as
 * a {@code cid:} part is the only form an external recipient renders.
 * <p>
 * Storage is a settings document of its own — see
 * {@link org.exoplatform.emailConnector.model.EmailSignatureSetting} for why it
 * is not a field of {@code userEmailSetting}. No table, no schema change.
 */
@Service
public class EmailSignatureService {

  /** Where the signature preference lives, in the add-on's own settings scope. */
  public static final String  EMAIL_SIGNATURE_KEY          = "userEmailSignature";

  /**
   * The composer-facing address of the signature image — the effective one,
   * custom when the user uploaded their own and the branding logo otherwise.
   * The send path recognises exactly this path in an outgoing body and swaps it
   * for the {@code cid:} of the part that carries the bytes.
   */
  public static final String  SIGNATURE_IMAGE_PATH         = "/email-connector/rest/user-email-setting/signature/image";

  /**
   * The cap on a custom signature's markup. A signature is a business card,
   * not a page; past this size it is almost certainly a pasted document — or a
   * pasted {@code data:} image, which would be stripped by the recipient's
   * client anyway.
   */
  public static final int     MAX_SIGNATURE_HTML_LENGTH    = 20 * 1024;

  private static final String SIGNATURE_TOO_LONG_MESSAGE   = "emailConnector.signature.tooLong";

  private static final String LOGO_UPLOAD_GONE_MESSAGE     = "emailConnector.signature.logo.uploadGone";

  private static final String LOGO_NOT_AN_IMAGE_MESSAGE    = "emailConnector.signature.logo.notAnImage";

  private static final String SIGNATURE_LOGO_FILE_NAME     = "signature-logo";

  /** The user-card settings scope the platform stores its display choices in. */
  private static final String USER_CARD_SETTINGS_SCOPE_ID  = "UserCardSettings";

  /**
   * The global setting naming WHICH profile property holds a user's displayed
   * phone — an indirection an administrator configures, so the signature reads
   * the same number the user card does.
   */
  private static final String DISPLAYED_PHONE_SETTING_KEY  = "UserDisplayedPhonePropertySetting";

  private static final String DEFAULT_LOGO_MIME_TYPE       = "image/png";

  private static final Log    LOG                          = ExoLogger.getLogger(EmailSignatureService.class);

  @Autowired
  private SettingService      settingService;

  @Autowired
  private IdentityManager     identityManager;

  @Autowired
  private BrandingService     brandingService;

  @Autowired
  private FileService         fileService;

  @Autowired
  private UploadService       uploadService;

  /**
   * The caller's signature as the settings screen and the composer consume it:
   * the stored preference plus the default computed from the profile as it
   * stands right now.
   *
   * @param username the signature's owner
   * @return the signature, never null
   */
  public EmailSignature getEmailSignature(String username) {
    EmailSignatureSetting setting = getStoredSetting(username);
    return new EmailSignature(setting.getEnabled(),
                              setting.getCustomHtml(),
                              buildDefaultSignature(username, setting),
                              setting.getLogoFileId() != null,
                              logoImage(setting));
  }

  /**
   * Stores the caller's signature preference: the on/off switch and their own
   * markup, sanitized on the way in because it will be rendered in the composer
   * and mailed to other people. A null (or blank) {@code customHtml} means
   * "back to the default", which then keeps following the profile.
   *
   * @param username the signature's owner
   * @param signature the preference to store; only {@code enabled} and
   *          {@code customHtml} are read from it
   * @throws IllegalArgumentException when the custom markup exceeds
   *           {@link #MAX_SIGNATURE_HTML_LENGTH}
   */
  public void saveEmailSignature(String username, EmailSignature signature) {
    EmailSignatureSetting setting = getStoredSetting(username);
    String customHtml = signature == null ? null : StringUtils.trimToNull(signature.getCustomHtml());
    if (customHtml != null && customHtml.length() > MAX_SIGNATURE_HTML_LENGTH) {
      throw new IllegalArgumentException(SIGNATURE_TOO_LONG_MESSAGE);
    }
    setting.setEnabled(signature == null ? null : signature.getEnabled());
    setting.setCustomHtml(sanitize(customHtml));
    storeSetting(username, setting);
  }

  /**
   * Replaces the signature's image with one the user uploaded through the
   * platform's cropper, stored as a file of the add-on's own namespace. Always
   * a fresh file, never an update in place — the file id doubles as the cache
   * version of the image URL, so replacing bytes under a stable id would leave
   * every browser showing the old picture.
   *
   * @param username the signature's owner
   * @param uploadId the upload id the crop drawer produced
   * @throws IllegalArgumentException when the upload is gone or is not an image
   */
  public void saveSignatureLogo(String username, String uploadId) {
    UploadResource uploadResource = uploadService.getUploadResource(uploadId);
    if (uploadResource == null || uploadResource.getStoreLocation() == null) {
      throw new IllegalArgumentException(LOGO_UPLOAD_GONE_MESSAGE);
    }
    String mimeType = uploadResource.getMimeType();
    if (StringUtils.isBlank(mimeType) || !mimeType.toLowerCase().startsWith("image/")) {
      throw new IllegalArgumentException(LOGO_NOT_AN_IMAGE_MESSAGE);
    }
    Long storedFileId = writeLogoFile(uploadResource, mimeType);
    if (storedFileId == null) {
      throw new IllegalStateException(String.format("The signature image of user %s could not be written", username));
    }
    EmailSignatureSetting setting = getStoredSetting(username);
    deleteLogoFile(setting.getLogoFileId());
    setting.setLogoFileId(storedFileId);
    storeSetting(username, setting);
  }

  /**
   * Puts the signature image back to the company logo, deleting the user's own
   * file.
   *
   * @param username the signature's owner
   */
  public void deleteSignatureLogo(String username) {
    EmailSignatureSetting setting = getStoredSetting(username);
    if (setting.getLogoFileId() == null) {
      return;
    }
    deleteLogoFile(setting.getLogoFileId());
    setting.setLogoFileId(null);
    storeSetting(username, setting);
  }

  /**
   * The image the caller's signature carries — their own uploaded file when
   * they set one, the platform's branding logo otherwise, and null when neither
   * has any bytes. This is what the send path embeds as a
   * {@code multipart/related} part: the ONLY form of the image every recipient
   * can render, since a platform URL answers a login page to anyone outside and
   * a {@code data:} URI is stripped by Gmail and Outlook.
   *
   * @param username the signature's owner
   * @return the image, or null when there is none to embed
   */
  public EmailSignatureLogo getSignatureLogo(String username) {
    EmailSignatureSetting setting = getStoredSetting(username);
    if (setting.getLogoFileId() != null) {
      try {
        FileItem fileItem = fileService.getFile(setting.getLogoFileId());
        if (fileItem != null && fileItem.getAsByte() != null) {
          String mimeType = fileItem.getFileInfo() == null ? null : fileItem.getFileInfo().getMimetype();
          return new EmailSignatureLogo(fileItem.getAsByte(),
                                        StringUtils.defaultIfBlank(mimeType, DEFAULT_LOGO_MIME_TYPE),
                                        SIGNATURE_LOGO_FILE_NAME);
        }
      } catch (Exception e) {
        LOG.warn("The signature image of user {} could not be read; falling back to the company logo", username, e);
      }
    }
    Logo companyLogo = brandingService.getLogo();
    if (companyLogo == null || companyLogo.getData() == null || companyLogo.getData().length == 0) {
      return null;
    }
    return new EmailSignatureLogo(companyLogo.getData(), DEFAULT_LOGO_MIME_TYPE, "logo");
  }

  /**
   * Forgets everything the signature stored for this user — the preference
   * document and their own image file. What disconnecting the mailbox implies:
   * the signature belongs to the mail account's composer, and a preference
   * about an account that no longer exists is a leak, not a memory.
   *
   * @param username the signature's owner
   */
  public void deleteEmailSignature(String username) {
    EmailSignatureSetting setting = getStoredSetting(username);
    deleteLogoFile(setting.getLogoFileId());
    settingService.remove(Context.USER.id(username), UserEmailSettingService.EMAIL_CONNECTOR_SCOPE, EMAIL_SIGNATURE_KEY);
  }

  /**
   * The signature this user gets without writing one, composed from what the platform
   * already knows about them.
   * <p>
   * Laid out the way a work signature conventionally is -- who, then how to reach
   * them, then where they work -- in three blocks rather than one run of lines, so it
   * reads as a signature instead of a list. Every line is dropped when the profile has
   * nothing for it, and an entire block disappears when all of its lines do: a
   * half-filled profile produces a shorter signature, never a "Position:" with
   * nothing after it.
   * <p>
   * The logo goes last, as part of the text. It is also handed over separately on
   * {@code EmailSignature#logoHtml}, not to be rendered twice but so the drawer has
   * something to insert when the user has deleted it and wants it back.
   *
   * @param username the user the signature is for
   * @param setting the user's stored signature settings
   * @return the composed markup, or an empty string when the profile says nothing
   */
  private String buildDefaultSignature(String username, EmailSignatureSetting setting) {
    Identity identity = identityManager == null ? null : identityManager.getOrCreateUserIdentity(username);
    Profile profile = identity == null ? null : identity.getProfile();
    if (profile == null) {
      return "";
    }
    String who = block(nameLine(profile),
                       escapeHtml(firstPositionSegment(profile.getPosition())),
                       escapeHtml(profileText(profile, Profile.COMPANY)));
    String reach = block(emailLine(profile), escapeHtml(displayedPhone(profile)));
    String where = block(locationLine(profile));
    // The logo last, and INSIDE the text rather than bolted on after it. The editor
    // that edits this has an image plugin, so the picture is a thing the user can pick
    // up and move, resize, or delete outright -- which is the only way to put it
    // beside the name instead of under it, and the only way to have a signature with
    // no picture at all. Put it back with the drawer's insert action.
    String logo = block(logoImage(setting));
    return StringUtils.defaultString(who) + StringUtils.defaultString(reach) + StringUtils.defaultString(where)
        + StringUtils.defaultString(logo);
  }

  /**
   * One paragraph of the signature, or nothing at all when it has no lines.
   *
   * @param lines the candidate lines, nulls and blanks ignored
   * @return the paragraph's markup, or null when every line was empty
   */
  private String block(String... lines) {
    List<String> present = new ArrayList<>();
    for (String line : lines) {
      if (StringUtils.isNotBlank(line)) {
        present.add(line);
      }
    }
    return present.isEmpty() ? null : "<p>" + String.join("<br>", present) + "</p>";
  }

  /**
   * The user's address, as something a reader can click rather than retype.
   *
   * @param profile the user's profile
   * @return the mailto link, or null when the profile carries no address
   */
  private String emailLine(Profile profile) {
    String email = escapeHtml(profileText(profile, Profile.EMAIL));
    return email == null ? null : "<a href=\"mailto:" + email + "\">" + email + "</a>";
  }

  /**
   * The name at the top of the signature: bold, and deliberately NOT a link.
   * <p>
   * It used to link to the author's profile page, which went wrong twice over. In the
   * composer the link is an internal platform address, and the editor's content-link
   * plugin upcasts those into a removable chip -- so the signature opened with the
   * author's name sitting in a blue box with a cross next to it, looking like a
   * mistake and one keystroke from being deleted. And for the recipient the link was
   * worth nothing anyway: a profile page redirects anyone outside the platform to a
   * login screen.
   * <p>
   * Plain bold text is also what a work signature conventionally does -- the links
   * that earn their place are the address and the company's site, not the sender's
   * name.
   *
   * @param profile the user's profile
   * @return the name's markup, or null when the profile has no name
   */
  private String nameLine(Profile profile) {
    String fullName = escapeHtml(profile.getFullName());
    return fullName == null ? null : "<strong>" + fullName + "</strong>";
  }

  /**
   * The location line: city and country when the profile carries them, the
   * plain free-text location property otherwise.
   *
   * @param profile the sender's profile
   * @return the line's markup, or null when the profile says nowhere
   */
  private String locationLine(Profile profile) {
    String cityAndCountry = joinNonBlank(", ",
                                         escapeHtml(profileText(profile, Profile.CITY)),
                                         escapeHtml(profileText(profile, Profile.COUNTRY)));
    if (cityAndCountry != null) {
      return cityAndCountry;
    }
    return escapeHtml(profileText(profile, Profile.LOCATION));
  }

  /**
   * The signature image element, pointing at {@link #SIGNATURE_IMAGE_PATH} —
   * the address the composer can render (the caller is logged in there) and the
   * send path recognises and replaces with the {@code cid:} of the embedded
   * part. The version parameter is the custom file's id or the branding's own
   * update time, so replacing the image is a new URL rather than a stale cache.
   *
   * @param setting the stored preference, carrying the custom file id if any
   * @return the {@code <img>} markup, or null when there is no image to show
   */
  private String logoImage(EmailSignatureSetting setting) {
    long version;
    if (setting.getLogoFileId() != null) {
      version = setting.getLogoFileId();
    } else {
      Logo companyLogo = brandingService.getLogo();
      if (companyLogo == null || companyLogo.getData() == null || companyLogo.getData().length == 0) {
        return null;
      }
      version = brandingService.getLastUpdatedTime();
    }
    String companyName = escapeHtml(brandingService.getCompanyName());
    return "<img src=\"" + SIGNATURE_IMAGE_PATH + "?v=" + version + "\" alt=\""
        + StringUtils.defaultString(companyName) + "\" height=\"48\" style=\"max-height:48px;\">";
  }

  /**
   * The phone number the platform is configured to display for a user: the
   * global {@code UserDisplayedPhonePropertySetting} names WHICH profile
   * property holds it, and that property is then read off the profile. No
   * setting, or no value, is simply no phone line.
   *
   * @param profile the sender's profile
   * @return the phone as text, or null
   */
  private String displayedPhone(Profile profile) {
    try {
      SettingValue<?> phonePropertySetting = settingService.get(Context.GLOBAL,
                                                                Scope.GLOBAL.id(USER_CARD_SETTINGS_SCOPE_ID),
                                                                DISPLAYED_PHONE_SETTING_KEY);
      if (phonePropertySetting == null || phonePropertySetting.getValue() == null) {
        return null;
      }
      String propertyName = String.valueOf(phonePropertySetting.getValue());
      return StringUtils.trimToNull(profileText(profile, propertyName));
    } catch (Exception e) {
      // A phone that cannot be resolved is a line the signature does without,
      // never a reason to fail composing the rest of it.
      LOG.debug("The displayed phone could not be resolved for the signature", e);
      return null;
    }
  }

  /**
   * One profile property as displayable text, whatever shape it is stored in: a
   * plain string as itself, a multivalued property (a list of {@code key/value}
   * maps, the shape phones and IMs use) as its first value.
   *
   * @param profile the profile to read
   * @param propertyName the property to read
   * @return the property as text, or null when absent or unreadable
   */
  private String profileText(Profile profile, String propertyName) {
    if (StringUtils.isBlank(propertyName)) {
      return null;
    }
    Object value = profile.getProperty(propertyName);
    if (value instanceof String text) {
      return StringUtils.trimToNull(text);
    }
    if (value instanceof List<?> values && !values.isEmpty()) {
      Object first = values.get(0);
      if (first instanceof Map<?, ?> entry && entry.get("value") != null) {
        return StringUtils.trimToNull(String.valueOf(entry.get("value")));
      }
      return first == null ? null : StringUtils.trimToNull(String.valueOf(first));
    }
    return null;
  }

  /**
   * The position alone, when the profile's position field has been overwritten
   * by a comma-joined concatenation of experiences — a known platform quirk.
   * The first segment is the one the field was originally about.
   *
   * @param position the position property as stored
   * @return its first comma-separated segment, or null when blank
   */
  private String firstPositionSegment(String position) {
    if (StringUtils.isBlank(position)) {
      return null;
    }
    return StringUtils.trimToNull(StringUtils.substringBefore(position, ","));
  }

  /**
   * Joins the given parts with a separator, skipping the blank ones — so a
   * missing company never leaves a dangling separator after the position.
   *
   * @param separator what goes between two present parts
   * @param parts the candidate parts, blanks and nulls welcome
   * @return the joined line, or null when every part was blank
   */
  private String joinNonBlank(String separator, String... parts) {
    List<String> present = new ArrayList<>();
    for (String part : parts) {
      if (StringUtils.isNotBlank(part)) {
        present.add(part);
      }
    }
    return present.isEmpty() ? null : String.join(separator, present);
  }

  /**
   * Escapes a profile value so it lands in the signature as TEXT. Profile
   * fields are user-editable strings — the EXO-89334 lesson — and a full name
   * of {@code <img src=x onerror=alert(1)>} must read as that string, not run.
   *
   * @param text the value to escape
   * @return the escaped text, or null when there was nothing
   */
  private String escapeHtml(String text) {
    if (StringUtils.isBlank(text)) {
      return null;
    }
    return text.replace("&", "&amp;")
               .replace("<", "&lt;")
               .replace(">", "&gt;")
               .replace("\"", "&quot;")
               .replace("'", "&#39;");
  }

  /**
   * Sanitizes the user's own markup with the platform's OWASP-backed
   * sanitizer — the one that actually strips scripts, not the link-transform
   * facade. Sanitized at SAVE so the stored document is already safe for every
   * render that follows.
   *
   * @param customHtml the markup as the user submitted it, may be null
   * @return the sanitized markup, or null when there was none
   */
  private String sanitize(String customHtml) {
    if (customHtml == null) {
      return null;
    }
    try {
      return StringUtils.trimToNull(HTMLSanitizer.sanitize(customHtml));
    } catch (Exception e) {
      throw new IllegalStateException("The signature markup could not be sanitized", e);
    }
  }

  /**
   * The stored preference, or a blank one when the user never touched the
   * feature or the stored document cannot be read — an unreadable preference is
   * a preference lost, never a composer that fails to open.
   *
   * @param username the signature's owner
   * @return the stored setting, never null
   */
  private EmailSignatureSetting getStoredSetting(String username) {
    SettingValue<?> value = settingService.get(Context.USER.id(username),
                                               UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                                               EMAIL_SIGNATURE_KEY);
    if (value == null || value.getValue() == null) {
      return new EmailSignatureSetting();
    }
    try {
      EmailSignatureSetting setting = JsonUtils.fromJsonString(value.getValue().toString(), EmailSignatureSetting.class);
      return setting == null ? new EmailSignatureSetting() : setting;
    } catch (Exception e) {
      LOG.warn("The stored email signature of user {} could not be read, starting from the default", username, e);
      return new EmailSignatureSetting();
    }
  }

  /**
   * Writes the preference document back under its own key.
   *
   * @param username the signature's owner
   * @param setting the preference to store
   */
  private void storeSetting(String username, EmailSignatureSetting setting) {
    settingService.set(Context.USER.id(username),
                       UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                       EMAIL_SIGNATURE_KEY,
                       SettingValue.create(JsonUtils.toJsonString(setting)));
  }

  /**
   * Writes an uploaded image into the add-on's file namespace, following the
   * contact-photo shape.
   *
   * @param uploadResource the upload to consume
   * @param mimeType its content type, already checked to be an image
   * @return the stored file id, or null when the file store wrote nothing
   */
  private Long writeLogoFile(UploadResource uploadResource, String mimeType) {
    try {
      byte[] bytes = IOUtil.getFileContentAsBytes(uploadResource.getStoreLocation());
      FileItem fileItem = new FileItem(null,
                                       SIGNATURE_LOGO_FILE_NAME,
                                       mimeType,
                                       EmailConnectorStorage.NAME_SPACE,
                                       bytes.length,
                                       new Date(),
                                       null,
                                       false,
                                       new ByteArrayInputStream(bytes));
      FileItem stored = fileService.writeFile(fileItem);
      return stored == null || stored.getFileInfo() == null ? null : stored.getFileInfo().getId();
    } catch (Exception e) {
      LOG.warn("The signature image could not be written to the file store", e);
      return null;
    }
  }

  /**
   * Deletes a stored signature image file, tolerating a file that is already
   * gone — the cleanup of a replaced or reset image must never fail its caller.
   *
   * @param logoFileId the file id, ignored when null or unset
   */
  private void deleteLogoFile(Long logoFileId) {
    if (logoFileId == null || logoFileId <= 0) {
      return;
    }
    try {
      fileService.deleteFile(logoFileId);
    } catch (Exception e) {
      LOG.warn("The replaced signature image file {} could not be deleted", logoFileId, e);
    }
  }
}
