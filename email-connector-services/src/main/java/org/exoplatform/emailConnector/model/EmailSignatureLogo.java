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
}
