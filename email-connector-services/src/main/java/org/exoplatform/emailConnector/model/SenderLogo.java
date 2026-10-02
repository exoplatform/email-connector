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

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * A mail domain's brand logo as the server last resolved it (EXO-90893): the image,
 * already checked and cleaned, with the type it is served as, or the fact that the
 * domain has none. Kept in the platform cache per domain, the "none" answer included,
 * which is why it is never null.
 */
public final class SenderLogo implements Serializable {

  /** The logo the domain published in its DNS (BIMI). */
  public static final String SOURCE_BIMI = "BIMI";

  /** The domain's own site icon. */
  public static final String SOURCE_ICON = "ICON";

  private static final long serialVersionUID = 4471337705187263451L;

  private final byte[]      data;

  private final String      contentType;

  private final String      source;

  private final long        resolvedAt;

  /**
   * A resolved logo, or none when {@code data} is null.
   *
   * @param data the image bytes, or null when the domain has no logo
   * @param contentType the type the image is served as, or null with no image
   * @param source where it came from: {@link #SOURCE_BIMI} or {@link #SOURCE_ICON}, or
   *          null with no image
   * @param resolvedAt when it was resolved, in ms since the epoch
   */
  public SenderLogo(byte[] data, String contentType, String source, long resolvedAt) {
    this.data = data == null ? null : data.clone();
    this.contentType = contentType;
    this.source = source;
    this.resolvedAt = resolvedAt;
  }

  /**
   * The answer for a domain that has no logo.
   *
   * @param resolvedAt when that was found, in ms since the epoch
   * @return the "none" answer
   */
  public static SenderLogo none(long resolvedAt) {
    return new SenderLogo(null, null, null, resolvedAt);
  }

  /**
   * Whether the domain has a logo.
   *
   * @return true when there is an image
   */
  public boolean isPresent() {
    return data != null;
  }

  /**
   * The image bytes.
   *
   * @return a copy of the bytes, or null when there is no logo
   */
  public byte[] getData() {
    return data == null ? null : data.clone();
  }

  /**
   * The type the image is served as.
   *
   * @return the media type, or null when there is no logo
   */
  public String getContentType() {
    return contentType;
  }

  /**
   * Where the logo came from.
   *
   * @return {@link #SOURCE_BIMI}, {@link #SOURCE_ICON}, or null when there is no logo
   */
  public String getSource() {
    return source;
  }

  /**
   * When the answer was resolved.
   *
   * @return ms since the epoch
   */
  public long getResolvedAt() {
    return resolvedAt;
  }

  /**
   * Compares by content.
   *
   * @param other the other object
   * @return true when both carry the same answer
   */
  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof SenderLogo logo)) {
      return false;
    }
    return resolvedAt == logo.resolvedAt && Arrays.equals(data, logo.data) && Objects.equals(contentType, logo.contentType)
        && Objects.equals(source, logo.source);
  }

  /**
   * Hashes by content.
   *
   * @return the hash
   */
  @Override
  public int hashCode() {
    return Objects.hash(Arrays.hashCode(data), contentType, source, resolvedAt);
  }

  /**
   * Describes the answer without its bytes.
   *
   * @return the description
   */
  @Override
  public String toString() {
    return isPresent() ? "SenderLogo[" + source + ", " + contentType + ", " + data.length + " bytes]" : "SenderLogo[none]";
  }
}
