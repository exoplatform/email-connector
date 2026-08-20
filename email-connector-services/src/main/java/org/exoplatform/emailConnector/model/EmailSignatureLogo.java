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
package org.exoplatform.emailConnector.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * The image a signature carries, bytes and honest content type together —
 * either the file the user uploaded through the cropper or the platform's
 * branding logo. The bytes are here because this image must travel INSIDE the
 * outgoing message as a {@code multipart/related} part: a platform URL sits
 * behind a login and shows every external recipient a broken frame, and a
 * {@code data:} URI is stripped by Gmail and Outlook.
 *
 * @param bytes the image content
 * @param mimeType its content type
 * @param fileName the name the MIME part is labelled with
 */
public record EmailSignatureLogo(byte[] bytes, String mimeType, String fileName) {

  /**
   * The generated equals compares the byte array by identity, so two logos holding the same
   * image would read as different and a signature would look changed when it is not.
   *
   * @param o the object to compare with
   * @return whether both carry the same values, the image compared by content
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof EmailSignatureLogo other)) {
      return false;
    }
    return Objects.equals(mimeType, other.mimeType())
           && Objects.equals(fileName, other.fileName())
           && Arrays.equals(bytes, other.bytes());
  }

  /**
   * @return a hash consistent with {@link #equals(Object)}, the image hashed by content
   */
  @Override
  public int hashCode() {
    return 31 * Objects.hash(mimeType, fileName) + Arrays.hashCode(bytes);
  }

  /**
   * @return the values, the image rendered as its size so a log line stays readable
   */
  @Override
  public String toString() {
    return "EmailSignatureLogo[mimeType=" + mimeType
        + ", fileName=" + fileName
        + ", bytes=" + (bytes == null ? "none" : bytes.length + " bytes")
        + "]";
  }
}
