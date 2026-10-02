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
package org.exoplatform.emailConnector.constant;

import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * The kinds of file a mailbox search can ask a message's attachments to be (EXO-90910),
 * each defined by the MIME types and the file extensions that make an attachment one of
 * them. An attachment is of a kind when its stored MIME type (parameters left out) is one
 * of the kind's, or when its name ends with one of the kind's extensions: a mail client
 * often sends an office file as {@code application/octet-stream}, which only its name
 * then tells. The webapp's chips name these keys (EmailConnectorMailBoxSearchCriteria.js,
 * ATTACHMENT_TYPES), and only these.
 */
public enum SearchAttachmentType {

  PDF(Set.of("application/pdf", "application/x-pdf"), Set.of(), Set.of("pdf")),

  // text/plain is left out: a CSV, a log or a vCard is often sent as one, and only the
  // .txt name makes a text file a document.
  DOCUMENT(Set.of("application/msword",
                  "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                  "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
                  "application/vnd.ms-word.document.macroenabled.12",
                  "application/vnd.oasis.opendocument.text",
                  "application/rtf",
                  "text/rtf"),
           Set.of(),
           Set.of("doc", "docx", "docm", "dot", "dotx", "odt", "ott", "rtf", "txt")),

  SPREADSHEET(Set.of("application/vnd.ms-excel",
                     "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                     "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
                     "application/vnd.ms-excel.sheet.macroenabled.12",
                     "application/vnd.oasis.opendocument.spreadsheet",
                     "text/csv"),
              Set.of(),
              Set.of("xls", "xlsx", "xlsm", "xlt", "xltx", "ods", "ots", "csv")),

  PRESENTATION(Set.of("application/vnd.ms-powerpoint",
                      "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                      "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
                      "application/vnd.ms-powerpoint.presentation.macroenabled.12",
                      "application/vnd.oasis.opendocument.presentation"),
               Set.of(),
               Set.of("ppt", "pptx", "pptm", "pps", "ppsx", "pot", "potx", "odp", "otp")),

  IMAGE(Set.of(), Set.of("image/"), Set.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "tif", "tiff", "heic")),

  ARCHIVE(Set.of("application/zip",
                 "application/x-zip-compressed",
                 "application/vnd.rar",
                 "application/x-rar-compressed",
                 "application/x-7z-compressed",
                 "application/x-tar",
                 "application/gzip",
                 "application/x-gzip",
                 "application/x-bzip2"),
          Set.of(),
          Set.of("zip", "rar", "7z", "tar", "gz", "tgz", "bz2"));

  // The MIME types of the kind, lower-cased, no parameter.
  private final Set<String> mimeTypes;

  // The MIME type prefixes of the kind (image/ for every image), lower-cased.
  private final Set<String> mimeTypePrefixes;

  // The file extensions of the kind, lower-cased, no dot.
  private final Set<String> extensions;

  /**
   * A kind of file, by the MIME types and the extensions that make an attachment one.
   *
   * @param mimeTypes the exact MIME types, lower-cased
   * @param mimeTypePrefixes the MIME type prefixes, lower-cased
   * @param extensions the file extensions, lower-cased, without their dot
   */
  SearchAttachmentType(Set<String> mimeTypes, Set<String> mimeTypePrefixes, Set<String> extensions) {
    this.mimeTypes = mimeTypes;
    this.mimeTypePrefixes = mimeTypePrefixes;
    this.extensions = extensions;
  }

  /**
   * Whether an attachment eXo stores is of this kind: by its MIME type or by its name's
   * extension, either one sufficing. Case does not matter, nor a parameter of the MIME
   * type ({@code application/pdf; name=a.pdf}).
   *
   * @param name the attachment's file name, may be null
   * @param mimeType the attachment's stored MIME type, may be null
   * @return true when the MIME type or the extension is one of this kind's
   */
  public boolean matches(String name, String mimeType) {
    String baseType = StringUtils.trimToEmpty(StringUtils.substringBefore(mimeType, ";")).toLowerCase(Locale.ROOT);
    if (!baseType.isEmpty()
        && (mimeTypes.contains(baseType) || mimeTypePrefixes.stream().anyMatch(baseType::startsWith))) {
      return true;
    }
    String fileName = StringUtils.trimToEmpty(name);
    int dot = fileName.lastIndexOf('.');
    return dot >= 0 && dot < fileName.length() - 1 && extensions.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
  }

  /**
   * The kind a search names by its key, case ignored.
   *
   * @param key the key, as the webapp sends it (PDF, DOCUMENT, ...)
   * @return the kind, or null when the key names none
   */
  public static SearchAttachmentType fromKey(String key) {
    String trimmed = StringUtils.trimToNull(key);
    if (trimmed == null) {
      return null;
    }
    for (SearchAttachmentType type : values()) {
      if (type.name().equalsIgnoreCase(trimmed)) {
        return type;
      }
    }
    return null;
  }
}
