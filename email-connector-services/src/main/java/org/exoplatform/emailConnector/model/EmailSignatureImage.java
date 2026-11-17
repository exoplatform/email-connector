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
 * The image a signature carries together with the version its address names
 * ({@code ?v=}): the file id of the user's own image when that is the image
 * served, the company logo's file id when the company logo is. Read together,
 * so the version always describes the bytes it comes with.
 *
 * @param logo the image
 * @param version the version of the address naming this image, null when none
 */
public record EmailSignatureImage(EmailSignatureLogo logo, Long version) {
}
